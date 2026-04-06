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
import org.junit.ClassRule;
import org.junit.rules.TestRule;

/**
 * Trace export via the Elastic APM Java agent ({@code GlobalOpenTelemetry}).
 */
public class ApmAgentTracesIT extends AbstractTracesIT {

    static {
        Configurator.setLevel("org.elasticsearch.test.apmintegration", Level.DEBUG);
    }

    public static ElasticsearchCluster cluster = AbstractTracesIT.baseTracesClusterBuilder()
        .systemProperty("telemetry.otel.traces.enabled", "false")
        .setting("telemetry.agent.metrics_interval", "1s")
        .setting("telemetry.agent.server_url", () -> "http://" + recordingApmServer.getHttpAddress())
        .build();

    @ClassRule
    public static TestRule ruleChain = AbstractTracesIT.buildTracesRuleChain(recordingApmServer, cluster);

    @Override
    protected String getTestRestCluster() {
        return cluster.getHttpAddresses();
    }
}
