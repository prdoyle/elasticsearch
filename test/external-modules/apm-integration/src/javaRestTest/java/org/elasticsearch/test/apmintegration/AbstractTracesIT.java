/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the "Elastic License
 * 2.0", the "GNU Affero General Public License v3.0 only", and the "Server Side
 * Public License v 1"; you may not use this file except in compliance with, at
 * your election, the "Elastic License 2.0", the "GNU Affero General Public
 * License v3.0 only", or the "Server Side Public License, v 1".
 */

package org.elasticsearch.test.apmintegration;

import org.elasticsearch.client.Request;
import org.elasticsearch.client.RequestOptions;
import org.elasticsearch.logging.LogManager;
import org.elasticsearch.logging.Logger;
import org.elasticsearch.tasks.Task;
import org.elasticsearch.test.cluster.ElasticsearchCluster;
import org.elasticsearch.test.cluster.local.LocalClusterSpecBuilder;
import org.elasticsearch.test.cluster.local.distribution.DistributionType;
import org.elasticsearch.test.rest.ESRestTestCase;
import org.junit.runners.model.Statement;

import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import static org.elasticsearch.test.apmintegration.AbstractMetricsIT.TELEMETRY_TIMEOUT;
import static org.hamcrest.Matchers.is;

/**
 * Shared trace export scenarios for the Elastic APM Java agent path vs Elasticsearch-owned OTLP export.
 */
public abstract class AbstractTracesIT extends ESRestTestCase {

    private static final Logger logger = LogManager.getLogger(AbstractTracesIT.class);

    protected static RecordingApmServer recordingApmServer = new RecordingApmServer();

    protected static LocalClusterSpecBuilder<ElasticsearchCluster> baseTracesClusterBuilder() {
        return ElasticsearchCluster.local()
            .distribution(DistributionType.INTEG_TEST)
            .module("test-apm-integration")
            .module("apm")
            .setting("telemetry.tracing.enabled", "true")
            .setting("telemetry.metrics.enabled", "false");
    }

    protected static org.junit.rules.TestRule buildTracesRuleChain(RecordingApmServer server, ElasticsearchCluster cluster) {
        return org.junit.rules.RuleChain.outerRule(server).around(cluster).around((base, description) -> new Statement() {
            @Override
            public void evaluate() throws Throwable {
                try {
                    base.evaluate();
                } finally {
                    try {
                        closeClients();
                    } catch (IOException e) {
                        logger.error("failed to close REST clients after test", e);
                    }
                }
            }
        });
    }

    public void testRestRootSpanWithTraceParent() throws Exception {
        final String traceIdValue = "0af7651916cd43dd8448eb211c80319c";
        /** Parent id from {@code traceparent}; the HTTP span is a child of this remote span, not an OTLP/APM root. */
        final String parentSpanIdFromTraceParent = "b7ad6b7169203331";
        final String traceParentValue = "00-" + traceIdValue + "-" + parentSpanIdFromTraceParent + "-01";

        CountDownLatch finished = new CountDownLatch(1);

        Consumer<ReceivedTelemetry> messageConsumer = (ReceivedTelemetry msg) -> {
            if (msg instanceof ReceivedTelemetry.ReceivedSpan s
                && "GET /_nodes/stats".equals(s.name())
                && traceIdValue.equalsIgnoreCase(s.traceId())
                && s.parentSpanId().filter(id -> id.equalsIgnoreCase(parentSpanIdFromTraceParent)).isPresent()) {
                logger.info("Matching span received: {}", s);
                finished.countDown();
            }
        };

        recordingApmServer.addMessageConsumer(messageConsumer);

        Request nodeStatsRequest = new Request("GET", "/_nodes/stats");
        nodeStatsRequest.setOptions(RequestOptions.DEFAULT.toBuilder().addHeader(Task.TRACE_PARENT_HTTP_HEADER, traceParentValue).build());

        client().performRequest(nodeStatsRequest);
        client().performRequest(new Request("GET", "/_flush_telemetry"));

        assertThat(finished.await(TELEMETRY_TIMEOUT, TimeUnit.SECONDS), is(true));
    }
}
