/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the "Elastic License
 * 2.0", the "GNU Affero General Public License v3.0 only", and the "Server Side
 * Public License v 1"; you may not use this file except in compliance with, at
 * your election, the "Elastic License 2.0", the "GNU Affero General Public
 * License v3.0 only", or the "Server Side Public License, v 1".
 */

package org.elasticsearch.telemetry.apm.internal.export.otelsdk;

import org.elasticsearch.common.settings.MockSecureSettings;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.core.SuppressForbidden;
import org.elasticsearch.test.ESTestCase;
import org.junit.After;
import org.junit.Before;

import static org.elasticsearch.telemetry.TelemetryProvider.OTEL_METRICS_ENABLED_SYSTEM_PROPERTY;
import static org.elasticsearch.telemetry.TelemetryProvider.OTEL_TRACES_ENABLED_SYSTEM_PROPERTY;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.nullValue;

@SuppressForbidden(reason = "tests manipulate OTel JVM system properties")
public class OtelSdkExportMeterSupplierTests extends ESTestCase {

    private String previousOtelMetricsEnabled;
    private String previousOtelTracesEnabled;

    @Before
    public void saveSystemProperties() {
        previousOtelMetricsEnabled = System.getProperty(OTEL_METRICS_ENABLED_SYSTEM_PROPERTY);
        previousOtelTracesEnabled = System.getProperty(OTEL_TRACES_ENABLED_SYSTEM_PROPERTY);
        System.clearProperty(OTEL_TRACES_ENABLED_SYSTEM_PROPERTY);
        System.setProperty(OTEL_METRICS_ENABLED_SYSTEM_PROPERTY, "true");
    }

    @After
    public void restoreSystemProperties() {
        restoreSystemProperty(previousOtelMetricsEnabled, OTEL_METRICS_ENABLED_SYSTEM_PROPERTY);
        restoreSystemProperty(previousOtelTracesEnabled, OTEL_TRACES_ENABLED_SYSTEM_PROPERTY);
    }

    public void testGetWithoutEndpointThrows() {
        IllegalStateException e = expectThrows(IllegalStateException.class, () -> OtelSdkTelemetryResources.maybeCreate(Settings.EMPTY));
        assertThat(e.getMessage(), containsString(OTEL_METRICS_ENABLED_SYSTEM_PROPERTY));
        assertThat(e.getMessage(), containsString("telemetry.otel.metrics.endpoint"));
    }

    public void testGetWithEmptyEndpointThrows() {
        Settings settings = Settings.builder().put(OtelSdkSettings.TELEMETRY_OTEL_METRICS_ENDPOINT.getKey(), "").build();
        expectThrows(IllegalStateException.class, () -> OtelSdkTelemetryResources.maybeCreate(settings));
    }

    public void testBuildOtlpAuthorizationHeaderWithNeitherCredential() {
        assertThat(OtelSdkExportMeterSupplier.buildOtlpAuthorizationHeader(Settings.EMPTY), nullValue());
    }

    public void testBuildOtlpAuthorizationHeaderPrefersApiKeyOverSecretToken() {
        MockSecureSettings secureSettings = new MockSecureSettings();
        secureSettings.setString("telemetry.api_key", "a2V5");
        secureSettings.setString("telemetry.secret_token", "tok");
        Settings settings = Settings.builder().setSecureSettings(secureSettings).build();
        assertThat(OtelSdkExportMeterSupplier.buildOtlpAuthorizationHeader(settings), equalTo("ApiKey a2V5"));
    }

    public void testBuildOtlpAuthorizationHeaderWithSecretTokenOnly() {
        MockSecureSettings secureSettings = new MockSecureSettings();
        secureSettings.setString("telemetry.secret_token", "sec");
        Settings settings = Settings.builder().setSecureSettings(secureSettings).build();
        assertThat(OtelSdkExportMeterSupplier.buildOtlpAuthorizationHeader(settings), equalTo("Bearer sec"));
    }

    public void testBuildOtlpAuthorizationHeaderWithApiKeyOnly() {
        MockSecureSettings secureSettings = new MockSecureSettings();
        secureSettings.setString("telemetry.api_key", "xyz");
        Settings settings = Settings.builder().setSecureSettings(secureSettings).build();
        assertThat(OtelSdkExportMeterSupplier.buildOtlpAuthorizationHeader(settings), equalTo("ApiKey xyz"));
    }

    public void testCloseWithoutGetDoesNotThrow() {
        Settings settings = Settings.builder()
            .put(OtelSdkSettings.TELEMETRY_OTEL_METRICS_ENDPOINT.getKey(), "http://127.0.0.1:9/v1/metrics")
            .build();
        try (OtelSdkTelemetryResources resources = OtelSdkTelemetryResources.maybeCreate(settings)) {
            assertNotNull(resources);
        }
    }

    public void testDoubleCloseAfterGetDoesNotThrow() {
        String bogusUrl = "http://127.0.0.1:9/v1/metrics";
        Settings settings = Settings.builder().put(OtelSdkSettings.TELEMETRY_OTEL_METRICS_ENDPOINT.getKey(), bogusUrl).build();
        OtelSdkTelemetryResources resources = OtelSdkTelemetryResources.maybeCreate(settings);
        assertNotNull(resources);
        OtelSdkExportMeterSupplier supplier = new OtelSdkExportMeterSupplier(resources);
        supplier.get();
        supplier.close();
        supplier.close();
        resources.close();
    }

    @SuppressForbidden(reason = "Uses System.setProperty")
    private static void restoreSystemProperty(String value, String key) {
        if (value == null) {
            System.clearProperty(key);
        } else {
            System.setProperty(key, value);
        }
    }
}
