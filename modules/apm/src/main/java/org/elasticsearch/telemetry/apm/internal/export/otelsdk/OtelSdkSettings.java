/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the "Elastic License
 * 2.0", the "GNU Affero General Public License v3.0 only", and the "Server Side
 * Public License v 1"; you may not use this file except in compliance with, at
 * your election, the "Elastic License 2.0", the "GNU Affero General Public
 * License v3.0 only", or the "Server Side Public License, v 1".
 */

package org.elasticsearch.telemetry.apm.internal.export.otelsdk;

import org.elasticsearch.common.settings.Setting;
import org.elasticsearch.core.TimeValue;

import static org.elasticsearch.common.settings.Setting.Property.Dynamic;
import static org.elasticsearch.common.settings.Setting.Property.NodeScope;

/**
 * Node settings for OpenTelemetry SDK metrics export ({@link OtelSdkExportMeterSupplier}).
 */
public final class OtelSdkSettings {

    private OtelSdkSettings() {}

    public static final Setting<String> TELEMETRY_OTEL_METRICS_ENDPOINT = Setting.simpleString(
        "telemetry.otel.metrics.endpoint",
        "",
        NodeScope
    );

    public static final Setting<TimeValue> TELEMETRY_OTEL_METRICS_INTERVAL = Setting.timeSetting(
        "telemetry.otel.metrics.interval",
        TimeValue.timeValueSeconds(10),
        NodeScope
    );

    public static final Setting<Boolean> TELEMETRY_OTEL_METRICS_ENABLED = Setting.boolSetting(
        "telemetry.otel.metrics.enabled",
        false,
        NodeScope
    );

    /**
     * OTLP HTTP endpoint for trace export (e.g. {@code https://collector:4318/v1/traces}).
     */
    public static final Setting<String> TELEMETRY_OTEL_TRACES_ENDPOINT = Setting.simpleString(
        "telemetry.otel.traces.endpoint",
        "",
        NodeScope
    );

    /**
     * Batch delay for {@link io.opentelemetry.sdk.trace.export.BatchSpanProcessor} (aligned with metrics interval pattern).
     */
    public static final Setting<TimeValue> TELEMETRY_OTEL_TRACES_INTERVAL = Setting.timeSetting(
        "telemetry.otel.traces.interval",
        TimeValue.timeValueSeconds(5),
        NodeScope
    );

    /**
     * Maximum number of child spans per transaction; {@code 0} matches Elastic APM agent {@code transaction_max_spans=0}
     * (only root / transaction spans; internal spans are not recorded).
     */
    public static final Setting<Integer> TELEMETRY_OTEL_TRACES_MAX_SPANS = Setting.intSetting(
        "telemetry.otel.traces.max_spans",
        0,
        0,
        NodeScope,
        Dynamic
    );

    /**
     * Maximum stack frames captured on span errors; {@code 0} matches Elastic APM agent {@code stack_trace_limit=0}
     * (no stack traces on spans; use status and exception attributes only).
     */
    public static final Setting<Integer> TELEMETRY_OTEL_TRACES_STACK_TRACE_LIMIT = Setting.intSetting(
        "telemetry.otel.traces.stack_trace_limit",
        0,
        0,
        NodeScope,
        Dynamic
    );

    public static final Setting<Boolean> TELEMETRY_OTEL_TRACES_ENABLED = Setting.boolSetting(
        "telemetry.otel.traces.enabled",
        false,
        NodeScope
    );
}
