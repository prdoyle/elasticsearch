/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the "Elastic License
 * 2.0", the "GNU Affero General Public License v3.0 only", and the "Server Side
 * Public License v 1"; you may not use this file except in compliance with, at
 * your election, the "Elastic License 2.0", the "GNU Affero General Public
 * License v3.0 only", or the "Server Side Public License, v 1".
 */

package org.elasticsearch.test.apmintegration;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.core.config.Configurator;
import org.elasticsearch.test.cluster.ElasticsearchCluster;
import org.elasticsearch.test.cluster.util.resource.Resource;
import org.junit.ClassRule;
import org.junit.rules.TestRule;

/**
 * Trace export via Elasticsearch-owned OpenTelemetry SDK and OTLP HTTP.
 */
public class OtelSdkTracesIT extends AbstractTracesIT {

    static {
        // Test JVM: enable DEBUG before @ClassRule starts the mock server and cluster.
        Configurator.setLevel("org.elasticsearch.test.apmintegration", Level.DEBUG);
    }

    public static ElasticsearchCluster cluster = AbstractTracesIT.baseTracesClusterBuilder()
        .systemProperty("telemetry.otel.traces.enabled", "true")
        .setting("telemetry.otel.traces.endpoint", () -> "http://" + recordingApmServer.getHttpAddress() + "/v1/traces")
        .setting("telemetry.otel.traces.interval", "10m")
        .setting("telemetry.agent.server_url", () -> "http://" + recordingApmServer.getHttpAddress())
        // Elasticsearch node: DEBUG for APM / OTel SDK integration (see also otel-jul-logging.properties).
        .setting("logger.org.elasticsearch.telemetry.apm", "DEBUG")
        .configFile("otel-jul-logging.properties", Resource.fromClasspath("otel-jul-logging.properties"))
        .systemProperty("java.util.logging.config.file", "${ES_PATH_CONF}/otel-jul-logging.properties")
        .build();

    @ClassRule
    public static TestRule ruleChain = AbstractTracesIT.buildTracesRuleChain(recordingApmServer, cluster);

    @Override
    protected String getTestRestCluster() {
        return cluster.getHttpAddresses();
    }
}
