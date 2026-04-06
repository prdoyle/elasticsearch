/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the "Elastic License
 * 2.0", the "GNU Affero General Public License v3.0 only", and the "Server Side
 * Public License v 1"; you may not use this file except in compliance with, at
 * your election, the "Elastic License 2.0", the "GNU Affero General Public
 * License v3.0 only", or the "Server Side Public License, v 1".
 */

package org.elasticsearch.telemetry.apm.internal.export.otelsdk;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.metrics.Meter;
import io.opentelemetry.api.metrics.MeterProvider;
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import io.opentelemetry.context.propagation.ContextPropagators;
import io.opentelemetry.exporter.otlp.http.metrics.OtlpHttpMetricExporter;
import io.opentelemetry.exporter.otlp.http.metrics.OtlpHttpMetricExporterBuilder;
import io.opentelemetry.exporter.otlp.http.trace.OtlpHttpSpanExporter;
import io.opentelemetry.exporter.otlp.http.trace.OtlpHttpSpanExporterBuilder;
import io.opentelemetry.instrumentation.runtimetelemetry.RuntimeTelemetry;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.common.InternalTelemetryVersion;
import io.opentelemetry.sdk.metrics.SdkMeterProvider;
import io.opentelemetry.sdk.metrics.export.AggregationTemporalitySelector;
import io.opentelemetry.sdk.metrics.export.PeriodicMetricReader;
import io.opentelemetry.sdk.resources.Resource;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.BatchSpanProcessor;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import io.opentelemetry.sdk.trace.samplers.Sampler;

import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.core.Booleans;
import org.elasticsearch.core.TimeValue;
import org.elasticsearch.logging.LogManager;
import org.elasticsearch.logging.Logger;

import java.util.Objects;
import java.util.concurrent.TimeUnit;

import static org.elasticsearch.telemetry.TelemetryProvider.OTEL_METRICS_ENABLED_SYSTEM_PROPERTY;
import static org.elasticsearch.telemetry.TelemetryProvider.OTEL_TRACES_ENABLED_SYSTEM_PROPERTY;

/**
 * Shared OpenTelemetry SDK resources when Elasticsearch owns OTLP export for metrics and/or traces.
 * Uses a single {@link OpenTelemetrySdk} when both are enabled.
 */
public final class OtelSdkTelemetryResources implements AutoCloseable {

    private static final Logger logger = LogManager.getLogger(OtelSdkTelemetryResources.class);

    private final Settings settings;
    private final boolean exportMetrics;
    private final boolean exportTraces;
    private final SdkMeterProvider systemMeterProvider;
    private final SdkMeterProvider meterHealthMeterProvider;
    private final SdkTracerProvider sdkTracerProvider;
    private final OpenTelemetrySdk openTelemetrySdk;
    private final RuntimeTelemetry runtimeTelemetry;
    private static final Object mutex = new Object();

    /**
     * @return resources when at least one of {@link org.elasticsearch.telemetry.TelemetryProvider#OTEL_METRICS_ENABLED_SYSTEM_PROPERTY}
     *         or {@link org.elasticsearch.telemetry.TelemetryProvider#OTEL_TRACES_ENABLED_SYSTEM_PROPERTY} is {@code true};
     *         otherwise {@code null}.
     */
    public static OtelSdkTelemetryResources maybeCreate(Settings settings) {
        boolean exportMetrics = Booleans.parseBoolean(System.getProperty(OTEL_METRICS_ENABLED_SYSTEM_PROPERTY, "false"));
        boolean exportTraces = Booleans.parseBoolean(System.getProperty(OTEL_TRACES_ENABLED_SYSTEM_PROPERTY, "false"));
        if (exportMetrics == false && exportTraces == false) {
            return null;
        }
        return new OtelSdkTelemetryResources(settings, exportMetrics, exportTraces);
    }

    private OtelSdkTelemetryResources(Settings settings, boolean exportMetrics, boolean exportTraces) {
        this.settings = settings;
        this.exportMetrics = exportMetrics;
        this.exportTraces = exportTraces;

        Resource resource = Resource.builder().put("service.name", "elasticsearch").build();

        SdkMeterProvider healthMp = null;
        SdkMeterProvider sysMp = null;
        SdkTracerProvider trProv = null;

        if (exportMetrics) {
            TimeValue intervalTimeValue = OtelSdkSettings.TELEMETRY_OTEL_METRICS_INTERVAL.get(settings);
            var metricHealthReader = PeriodicMetricReader.builder(createMetricOtelExporter(MeterProvider.noop()))
                .setInterval(intervalTimeValue.toDuration())
                .build();
            healthMp = sdkMeterProvider(metricHealthReader, resource);

            var reader = PeriodicMetricReader.builder(createMetricOtelExporter(healthMp))
                .setInterval(intervalTimeValue.toDuration())
                .build();
            sysMp = sdkMeterProvider(reader, resource);
        }

        if (exportTraces) {
            String endpoint = OtelSdkSettings.TELEMETRY_OTEL_TRACES_ENDPOINT.get(settings);
            if (endpoint == null || endpoint.isEmpty()) {
                throw new IllegalStateException(
                    OTEL_TRACES_ENABLED_SYSTEM_PROPERTY
                        + "=true requires "
                        + OtelSdkSettings.TELEMETRY_OTEL_TRACES_ENDPOINT.getKey()
                        + " to be configured"
                );
            }
            TimeValue traceBatchDelay = OtelSdkSettings.TELEMETRY_OTEL_TRACES_INTERVAL.get(settings);
            SpanExporter spanExporter = createSpanOtelExporter();
            trProv = SdkTracerProvider.builder()
                .setResource(resource)
                .setSampler(Sampler.alwaysOn())
                .addSpanProcessor(BatchSpanProcessor.builder(spanExporter).setScheduleDelay(traceBatchDelay.toDuration()).build())
                .build();
            logger.debug(
                "OpenTelemetry SDK trace export initialized: OTLP endpoint [{}], batch schedule delay [{}]",
                endpoint,
                traceBatchDelay
            );
        }

        var sdkBuilder = OpenTelemetrySdk.builder().setPropagators(ContextPropagators.create(W3CTraceContextPropagator.getInstance()));
        if (sysMp != null) {
            sdkBuilder.setMeterProvider(sysMp);
        }
        if (trProv != null) {
            sdkBuilder.setTracerProvider(trProv);
        }
        this.systemMeterProvider = sysMp;
        this.meterHealthMeterProvider = healthMp;
        this.sdkTracerProvider = trProv;
        this.openTelemetrySdk = sdkBuilder.build();

        if (exportMetrics && OtelSdkSettings.TELEMETRY_OTEL_METRICS_ENABLED.get(settings)) {
            this.runtimeTelemetry = RuntimeTelemetry.create(openTelemetrySdk);
        } else {
            this.runtimeTelemetry = null;
        }

        // Validate fields for metrics path
        if (exportMetrics) {
            Objects.requireNonNull(systemMeterProvider, "systemMeterProvider");
            Objects.requireNonNull(meterHealthMeterProvider, "meterHealthMeterProvider");
        }
    }

    private static SdkMeterProvider sdkMeterProvider(PeriodicMetricReader reader, Resource resource) {
        return SdkMeterProvider.builder().setResource(resource).registerMetricReader(reader).build();
    }

    private OtlpHttpMetricExporter createMetricOtelExporter(MeterProvider healthExportMeterProvider) {
        String endpoint = OtelSdkSettings.TELEMETRY_OTEL_METRICS_ENDPOINT.get(settings);
        if (endpoint == null || endpoint.isEmpty()) {
            throw new IllegalStateException(
                OTEL_METRICS_ENABLED_SYSTEM_PROPERTY
                    + "=true requires "
                    + OtelSdkSettings.TELEMETRY_OTEL_METRICS_ENDPOINT.getKey()
                    + " to be configured"
            );
        }
        OtlpHttpMetricExporterBuilder builder = OtlpHttpMetricExporter.builder()
            .setEndpoint(endpoint)
            .setMeterProvider(() -> healthExportMeterProvider)
            .setAggregationTemporalitySelector(AggregationTemporalitySelector.deltaPreferred())
            .setInternalTelemetryVersion(InternalTelemetryVersion.LATEST);
        String authHeader = OtelSdkExportMeterSupplier.buildOtlpAuthorizationHeader(settings);
        if (authHeader != null) {
            builder.addHeader("Authorization", authHeader);
        }
        return builder.build();
    }

    private OtlpHttpSpanExporter createSpanOtelExporter() {
        String endpoint = OtelSdkSettings.TELEMETRY_OTEL_TRACES_ENDPOINT.get(settings);
        OtlpHttpSpanExporterBuilder builder = OtlpHttpSpanExporter.builder().setEndpoint(endpoint);
        String authHeader = OtelSdkExportMeterSupplier.buildOtlpAuthorizationHeader(settings);
        if (authHeader != null) {
            builder.addHeader("Authorization", authHeader);
        }
        return builder.build();
    }

    public Meter getMeter() {
        assert exportMetrics;
        return systemMeterProvider.get("elasticsearch");
    }

    public OpenTelemetry getOpenTelemetry() {
        assert exportTraces;
        return openTelemetrySdk;
    }

    public boolean exportsMetrics() {
        return exportMetrics;
    }

    public boolean exportsTraces() {
        return exportTraces;
    }

    public void attemptFlushMetrics() {
        synchronized (mutex) {
            if (exportMetrics && systemMeterProvider != null) {
                systemMeterProvider.forceFlush().join(10, TimeUnit.SECONDS);
                meterHealthMeterProvider.forceFlush().join(10, TimeUnit.SECONDS);
                systemMeterProvider.forceFlush().join(10, TimeUnit.SECONDS);
                meterHealthMeterProvider.forceFlush().join(10, TimeUnit.SECONDS);
            }
        }
    }

    public void attemptFlushTraces() {
        synchronized (mutex) {
            if (exportTraces && sdkTracerProvider != null) {
                logger.debug("Flushing OpenTelemetry SdkTracerProvider (waiting up to 10s for OTLP export)");
                sdkTracerProvider.forceFlush().join(10, TimeUnit.SECONDS);
                logger.debug("SdkTracerProvider.forceFlush completed");
            } else {
                logger.debug(
                    "Skipping OpenTelemetry trace flush: exportTraces [{}], sdkTracerProvider present [{}]",
                    exportTraces,
                    sdkTracerProvider != null
                );
            }
        }
    }

    @Override
    public void close() {
        synchronized (mutex) {
            if (runtimeTelemetry != null) {
                runtimeTelemetry.close();
            }
            if (systemMeterProvider != null) {
                systemMeterProvider.close();
            }
            if (meterHealthMeterProvider != null) {
                meterHealthMeterProvider.close();
            }
            if (sdkTracerProvider != null) {
                sdkTracerProvider.close();
            }
        }
    }
}
