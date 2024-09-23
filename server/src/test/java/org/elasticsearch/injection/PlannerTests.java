/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the "Elastic License
 * 2.0", the "GNU Affero General Public License v3.0 only", and the "Server Side
 * Public License v 1"; you may not use this file except in compliance with, at
 * your election, the "Elastic License 2.0", the "GNU Affero General Public
 * License v3.0 only", or the "Server Side Public License, v 1".
 */

package org.elasticsearch.injection;

import org.elasticsearch.injection.api.Proxy;
import org.elasticsearch.injection.spec.MethodHandleSpec;
import org.elasticsearch.injection.spec.SubtypeSpec;
import org.elasticsearch.injection.step.CreateCollectionProxyStep;
import org.elasticsearch.injection.step.CreateInstanceProxyStep;
import org.elasticsearch.injection.step.InjectionStep;
import org.elasticsearch.injection.step.InstantiateStep;
import org.elasticsearch.injection.step.ResolveCollectionProxyStep;
import org.elasticsearch.injection.step.ResolveInstanceProxyStep;
import org.elasticsearch.injection.step.RollupStep;
import org.elasticsearch.test.ESTestCase;

import java.lang.invoke.MethodHandles;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static java.util.Collections.emptyList;
import static java.util.Collections.emptyMap;
import static java.util.Collections.emptySet;

public class PlannerTests extends ESTestCase {

    public void testNoOp() {
        var planner = new Planner(emptyMap(), emptySet());
        assertEquals(emptyList(), planner.computeInjectionPlan());
    }

    public void testSimpleDependency() throws NoSuchMethodException, IllegalAccessException {
        record Service(){}
        record Client(Service service){}
        var serviceSpec = MethodHandleSpec.forConstructor(MethodHandles.lookup(), Service.class.getDeclaredConstructor());
        var clientSpec = MethodHandleSpec.forConstructor(MethodHandles.lookup(), Client.class.getDeclaredConstructor(Service.class));
        var planner = new Planner(Map.of(
            Service.class, serviceSpec,
            Client.class, clientSpec
        ), Set.of(Client.class));
        List<InjectionStep> expected = List.of(
            new InstantiateStep(serviceSpec),
            new InstantiateStep(clientSpec)
        );
        assertEquals(expected, planner.computeInjectionPlan());
    }

    public void testOneListener() throws NoSuchMethodException, IllegalAccessException {
        interface Listener {}
        record Service(Iterable<Listener> listeners){}
        record Listener1(Service service) implements Listener {}
        var serviceSpec = MethodHandleSpec.forConstructor(MethodHandles.lookup(), Service.class.getDeclaredConstructor(Iterable.class));
        var listener1Spec = MethodHandleSpec.forConstructor(MethodHandles.lookup(), Listener1.class.getDeclaredConstructor(Service.class));
        var planner = new Planner(Map.of(
            Service.class, serviceSpec,
            Listener1.class, listener1Spec,
            Listener.class, new SubtypeSpec(Listener.class, Listener1.class)
        ), Set.of(Service.class));
        List<InjectionStep> expected = List.of(
            // We process Service itself first. Because its argument is proxyable, we don't need to
            // recurse and plan it first; just make the proxy and move on.
            new CreateCollectionProxyStep(Listener.class),
            new InstantiateStep(serviceSpec),

            // Now we need to resolve the proxy, which entails planning Listener1.
            new InstantiateStep(listener1Spec),
            new RollupStep(Listener1.class, Listener.class),
            new ResolveCollectionProxyStep(Listener.class)
        );
        assertEquals(expected, planner.computeInjectionPlan());
    }

    public void testProxy() throws NoSuchMethodException, IllegalAccessException {
        interface Service {}
        record ServiceImpl() implements Service {}
        record Client(@Proxy Service service) {}
        var serviceSpec = new SubtypeSpec(Service.class, ServiceImpl.class);
        var serviceImplSpec = MethodHandleSpec.forConstructor(MethodHandles.lookup(), ServiceImpl.class.getDeclaredConstructor());
        var clientSpec = MethodHandleSpec.forConstructor(MethodHandles.lookup(), Client.class.getDeclaredConstructor(Service.class));
        var planner = new Planner(Map.of(
            Service.class, serviceSpec,
            ServiceImpl.class, serviceImplSpec,
            Client.class, clientSpec
        ), Set.of(Client.class));
        List<InjectionStep> expected = List.of(
            // Plan Client itself first. Because its parameter is proxyable, we don't need to
            // recurse and plan it first; just make the proxy and move on.
            new CreateInstanceProxyStep(Service.class),
            new InstantiateStep(clientSpec),

            // Now resolve the proxy
            new InstantiateStep(serviceImplSpec),
            new RollupStep(ServiceImpl.class, Service.class),
            new ResolveInstanceProxyStep(Service.class)
        );
        assertEquals(expected, planner.computeInjectionPlan());
    }
}
