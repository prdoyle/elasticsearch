/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the "Elastic License
 * 2.0", the "GNU Affero General Public License v3.0 only", and the "Server Side
 * Public License v 1"; you may not use this file except in compliance with, at
 * your election, the "Elastic License 2.0", the "GNU Affero General Public
 * License v3.0 only", or the "Server Side Public License, v 1".
 */

package org.elasticsearch.telemetry.apm.internal.export.otelsdk;

import io.opentelemetry.api.metrics.Meter;

import org.elasticsearch.common.settings.SecureString;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.telemetry.apm.internal.APMAgentSettings;
import org.elasticsearch.telemetry.apm.internal.export.MeterSupplier;

/**
 * A {@link MeterSupplier} that delegates to {@link OtelSdkTelemetryResources} when Elasticsearch owns
 * OTLP metrics export.
 *
 * @see OtelSdkSettings
 * @see org.elasticsearch.telemetry.apm.internal.export.agent.AgentExportMeterSupplier
 */
public final class OtelSdkExportMeterSupplier implements MeterSupplier {

    private final OtelSdkTelemetryResources resources;

    /**
     * @throws IllegalArgumentException if {@code resources} does not export metrics
     */
    public OtelSdkExportMeterSupplier(OtelSdkTelemetryResources resources) {
        if (resources.exportsMetrics() == false) {
            throw new IllegalArgumentException("OTel metrics export requires metrics-enabled OTel SDK resources");
        }
        this.resources = resources;
    }

    @Override
    public Meter get() {
        return resources.getMeter();
    }

    @Override
    public void attemptFlushMetrics() {
        resources.attemptFlushMetrics();
    }

    @Override
    public void close() {
        // {@link OtelSdkTelemetryResources} lifecycle is owned by {@link org.elasticsearch.telemetry.apm.internal.APMMeterService}.
    }

    /**
     * Authorization header for OTLP HTTP requests when API key or secret token is configured.
     */
    public static String buildOtlpAuthorizationHeader(Settings settings) {
        try (SecureString apiKey = APMAgentSettings.TELEMETRY_API_KEY_SETTING.get(settings)) {
            if (apiKey.isEmpty() == false) {
                return "ApiKey " + apiKey;
            }
        }
        try (SecureString secretToken = APMAgentSettings.TELEMETRY_SECRET_TOKEN_SETTING.get(settings)) {
            if (secretToken.isEmpty() == false) {
                return "Bearer " + secretToken;
            }
        }
        return null;
    }
}
