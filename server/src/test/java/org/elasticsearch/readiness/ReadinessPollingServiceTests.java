/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the "Elastic License
 * 2.0", the "GNU Affero General Public License v3.0 only", and the "Server Side
 * Public License v 1"; you may not use this file except in compliance with, at
 * your election, the "Elastic License 2.0", the "GNU Affero General Public
 * License v3.0 only", or the "Server Side Public License, v 1".
 */

package org.elasticsearch.readiness;

import org.elasticsearch.TransportVersion;
import org.elasticsearch.action.ActionListener;
import org.elasticsearch.action.support.ActionFilters;
import org.elasticsearch.cluster.ClusterName;
import org.elasticsearch.cluster.ClusterState;
import org.elasticsearch.cluster.node.DiscoveryNode;
import org.elasticsearch.cluster.node.DiscoveryNodes;
import org.elasticsearch.cluster.node.VersionInformation;
import org.elasticsearch.cluster.service.ClusterService;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.util.concurrent.EsExecutors;
import org.elasticsearch.test.ClusterServiceUtils;
import org.elasticsearch.test.ESTestCase;
import org.elasticsearch.test.junit.annotations.TestLogging;
import org.elasticsearch.test.transport.MockTransportService;
import org.elasticsearch.threadpool.TestThreadPool;
import org.elasticsearch.threadpool.ThreadPool;
import org.elasticsearch.transport.TransportException;
import org.elasticsearch.transport.TransportService;
import org.junit.After;
import org.junit.Before;

import java.util.List;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;
import java.util.stream.IntStream;

import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.SECONDS;

public class ReadinessPollingServiceTests extends ESTestCase {
    /**
     * This is for tests that are not expecting a timeout to occur.
     * We use a large value to support single-step debugging.
     */
    private static final int LONG_TIMEOUT_MILLIS = 20 * 60 * 1000;

    /**
     * We're not actually waiting for nodes to boot, so use a short value to avoid wasting time.
     */
    private static final int QUICK_RETRY_MILLIS = 1;

    /**
     * This is for tests that <em>are</em> expecting a timeout to occur.
     * Use a short value so we don't waste a lot of time.
     */
    private static final int QUICK_TIMEOUT_MILLIS = 10 * QUICK_RETRY_MILLIS;

    private ThreadPool threadPool;
    private MockTransportService sourceTransport;
    private List<MockTransportService> targetTransports;
    private List<DiscoveryNode> targetNodes;
    private ClusterService clusterService;
    private ReadinessPollingService service;

    @Before
    public void setup() throws InterruptedException {
        threadPool = new TestThreadPool(getClass().getName());
        sourceTransport = newMockTransportService();
        targetTransports = IntStream.rangeClosed(1, 2).mapToObj(n -> newMockTransportService()).toList();
        targetNodes = targetTransports.stream().map(TransportService::getLocalNode).toList();

        // Connect all the target nodes to the source transport
        for (MockTransportService target : targetTransports) {
            CountDownLatch latch = new CountDownLatch(1);
            sourceTransport.connectionManager().connectToNode(target.getLocalNode(), null, (x,y,listener)->{listener.onResponse(null);}, ActionListener.wrap(
                connection -> latch.countDown(),
                e -> fail("Unexpected exception connecting to target node: " + e)
            ));
            if (!latch.await(LONG_TIMEOUT_MILLIS, MILLISECONDS)) {
                fail("Timed out waiting for target node to connect");
            }
        }

        DiscoveryNodes.Builder nodesBuilder = DiscoveryNodes.builder()
            .add(sourceTransport.getLocalNode());
        targetNodes.forEach(nodesBuilder::add);
        ClusterState clusterState = ClusterState.builder(new ClusterName("test-cluster")).nodes(nodesBuilder).build();
        clusterService = ClusterServiceUtils.createClusterService(clusterState, threadPool);

        // In general, the source node will always be ready
        registerReadinessActionOnOneNode(sourceTransport, node -> true);
    }

    private MockTransportService newMockTransportService() {
        MockTransportService newService = MockTransportService.createNewService(
            Settings.EMPTY,
            VersionInformation.CURRENT,
            TransportVersion.current(),
            threadPool
        );
        newService.start();
        newService.acceptIncomingRequests();
        return newService;
    }

    @After
    public void teardown() {
        clusterService.close();
        sourceTransport.close();
        targetTransports.forEach(MockTransportService::close);
        terminate(threadPool);
    }

    public void testSuccessImmediately() throws Exception {
        service = newReadinessPollingService(LONG_TIMEOUT_MILLIS);
        registerReadinessActions(node -> true);

        assertReadiness(true);
    }

    public void testSuccessAfterRetries() throws Exception {
        service = newReadinessPollingService(LONG_TIMEOUT_MILLIS);
        AtomicInteger remainingFailures = new AtomicInteger(2 * targetNodes.size()); // A couple of failures per node before success
        registerReadinessActions(node -> remainingFailures.getAndDecrement() <= 0);

        assertReadiness(true);
    }

    public void testSuccessOnOneNode() throws Exception {
        service = newReadinessPollingService(LONG_TIMEOUT_MILLIS);
        var goodNode = randomFrom(targetNodes);
        registerReadinessActions(goodNode::equals);

        assertReadiness(true);
    }

    public void testTimeout() throws Exception {
        service = newReadinessPollingService(QUICK_TIMEOUT_MILLIS);
        registerReadinessActions(node -> false);

        assertReadiness(false);
    }

    public void testTransportExceptionSameAsTimeout() throws Exception {
        service = newReadinessPollingService(QUICK_TIMEOUT_MILLIS);
        registerReadinessActions(node -> { throw new TransportException("test"); });

        assertReadiness(false);
    }

    public void testIllegalStateExceptionSameAsTimeout() throws Exception {
        service = newReadinessPollingService(QUICK_TIMEOUT_MILLIS);
        registerReadinessActions(node -> { throw new IllegalStateException("test"); });

        assertReadiness(false);
    }

    private ReadinessPollingService newReadinessPollingService(int quickTimeoutMillis) {
        return new ReadinessPollingService(
            clusterService,
            sourceTransport,
            threadPool,
            quickTimeoutMillis,
            QUICK_RETRY_MILLIS
        );
    }

    private void registerReadinessActions(Predicate<DiscoveryNode> isReady) {
        targetTransports.forEach(transport -> registerReadinessActionOnOneNode(transport, isReady));
    }

    private static void registerReadinessActionOnOneNode(MockTransportService transport, Predicate<DiscoveryNode> isReady) {
        TransportReadinessAction action = new TransportReadinessAction(new ActionFilters(Set.of()), null, Runnable::run, ()->isReady.test(transport.getLocalNode()) );
        transport.registerRequestHandler(
            TransportReadinessAction.TYPE.name(),
            EsExecutors.DIRECT_EXECUTOR_SERVICE,
            false,
            false,
            ReadinessRequest::new,
            (request, channel, task) -> action.execute(task, request, ActionListener.wrap(channel::sendResponse, channel::sendResponse))
        );
    }

    private void assertReadiness(Boolean expected) throws InterruptedException {
        BlockingQueue<Boolean> readiness = new LinkedBlockingQueue<>();
        service.execute(targetNodes::contains, ActionListener.wrap(readiness::add, e -> fail(e.toString())));
        assertEquals(expected, readiness.poll(LONG_TIMEOUT_MILLIS, MILLISECONDS));
    }

}
