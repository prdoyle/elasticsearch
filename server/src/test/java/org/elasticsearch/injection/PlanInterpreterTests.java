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
import org.elasticsearch.injection.step.CreateCollectionProxyStep;
import org.elasticsearch.injection.step.CreateInstanceProxyStep;
import org.elasticsearch.injection.step.InstantiateStep;
import org.elasticsearch.injection.step.ResolveCollectionProxyStep;
import org.elasticsearch.injection.step.ResolveInstanceProxyStep;
import org.elasticsearch.injection.step.RollupStep;
import org.elasticsearch.test.ESTestCase;
import org.elasticsearch.test.junit.annotations.TestLogging;
import org.junit.Before;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static java.lang.invoke.MethodHandles.lookup;
import static java.util.Collections.emptyMap;
import static org.elasticsearch.injection.spec.MethodHandleSpec.forConstructor;

@TestLogging(value = "org.elasticsearch.injection:TRACE", reason = "because I say so")
public class PlanInterpreterTests extends ESTestCase {
    RecordingProxyPool recordingPool;

    /**
     * Remembers the proxies so we can make assertions about them
     */
    class RecordingProxyPool extends ProxyPool {
        final Map<Class<?>, Object> allInstanceProxies = new HashMap<>();
        final Map<Class<?>, Collection<?>> allCollectionProxies = new HashMap<>();

        @Override
        public Object createProxyInstance(Class<?> t) {
            Object result = super.createProxyInstance(t);
            var prev = allInstanceProxies.put(t, result);
            assert prev == null;
            return result;
        }

        @Override
        public Collection<?> createProxyCollection(Class<?> entryType) {
            Collection<?> result = super.createProxyCollection(entryType);
            var prev = allCollectionProxies.put(entryType, result);
            assert prev == null;
            return result;
        }
    }

    @Before
    public void initializeProxyPool() {
        recordingPool = new RecordingProxyPool();
    }

    public void testInitialInstances() {
        assertEquals(List.of(), new PlanInterpreter(emptyMap(), new ProxyPool()).instancesOf(String.class));

        {
            PlanInterpreter interpreter = new PlanInterpreter(Map.of(String.class, "Hello"), new ProxyPool());
            assertEquals(List.of("Hello"), interpreter.instancesOf(String.class));
            assertEquals(List.of(), interpreter.instancesOf(Long.class));
        }

        {
            PlanInterpreter interpreter = new PlanInterpreter(Map.of(String.class, "Hello", Long.class, 123L), new ProxyPool());
            assertEquals(List.of("Hello"), interpreter.instancesOf(String.class));
            assertEquals(List.of(123L), interpreter.instancesOf(Long.class));
            assertEquals("Inheritance should not just happen automatically", List.of(), interpreter.instancesOf(Object.class));
        }
    }

    public void testInstantiate() throws NoSuchMethodException, IllegalAccessException {
        record Service() {}
        record Client(Service service) {}

        var serviceCtor = Service.class.getDeclaredConstructor();
        var clientCtor = Client.class.getDeclaredConstructor(Service.class);
        {
            PlanInterpreter interpreter = new PlanInterpreter(Map.of(), new ProxyPool());
            interpreter.executePlan(List.of(
                new InstantiateStep(forConstructor(lookup(), serviceCtor))
            ));
            assertNotNull(interpreter.theInstanceOf(Service.class));
        }

        {
            PlanInterpreter interpreter = new PlanInterpreter(Map.of(), new ProxyPool());
            assertThrows("Nonexistent argument should be detected", IllegalStateException.class, () ->
                interpreter.executePlan(List.of(
                    new InstantiateStep(forConstructor(lookup(), clientCtor))
                ))
            );
        }

        {
            PlanInterpreter interpreter = new PlanInterpreter(Map.of(), new ProxyPool());
            interpreter.executePlan(List.of(
                new InstantiateStep(forConstructor(lookup(), serviceCtor)),
                new InstantiateStep(forConstructor(lookup(), clientCtor))
            ));
            assertSame(interpreter.theInstanceOf(Service.class), interpreter.theInstanceOf(Client.class).service);
        }
    }

    public void testRollup() {
        PlanInterpreter interpreter = new PlanInterpreter(Map.of(String.class, "Hello"), new ProxyPool());
        assertEquals("Available as String from the start", List.of("Hello"), interpreter.instancesOf(String.class));
        assertEquals("Not available as CharSequence before rollup", List.of(), interpreter.instancesOf(CharSequence.class));

        interpreter.executePlan(List.of(new RollupStep(String.class, CharSequence.class)));

        assertEquals("Still available as String after rollup", List.of("Hello"), interpreter.instancesOf(String.class));
        assertEquals("Available as CharSequence after rollup", List.of("Hello"), interpreter.instancesOf(CharSequence.class));
        assertSame(interpreter.theInstanceOf(String.class), interpreter.theInstanceOf(CharSequence.class));
    }

    public void testProxyOfExistingInstances() {
        PlanInterpreter interpreter = new PlanInterpreter(Map.of(String.class, "Hello"), recordingPool);
        interpreter.executePlan(List.of(
            new CreateCollectionProxyStep(String.class),
            new ResolveCollectionProxyStep(String.class)
        ));
        assertEquals(List.of("Hello"), List.copyOf(recordingPool.allCollectionProxies.get(String.class)));
    }

    public void testCollectionProxyOfInstantiatedClass() throws NoSuchMethodException, IllegalAccessException {
        record Test(String string){}
        PlanInterpreter interpreter = new PlanInterpreter(Map.of(String.class, "Hello"), recordingPool);
        interpreter.executePlan(List.of(
            new CreateCollectionProxyStep(Test.class),
            new InstantiateStep(forConstructor(lookup(), Test.class.getDeclaredConstructor(String.class))),
            new ResolveCollectionProxyStep(Test.class)
        ));
        var collection = recordingPool.allCollectionProxies.get(Test.class);
        assertNotNull(collection);
        assertEquals(1, collection.size());
        Test instance = interpreter.theInstanceOf(Test.class);
        assertSame(instance, collection.iterator().next());
        assertEquals("Hello", instance.string);
    }

    public interface Markable {
        Object marker();
    }

    public void testInstanceProxyOfInstantiatedClass() throws NoSuchMethodException, IllegalAccessException {
        Object marker = new Object(); // So we can test object identity
        record TestImpl(Object marker) implements Markable {}
        PlanInterpreter interpreter = new PlanInterpreter(Map.of(Object.class, marker), recordingPool);
        interpreter.executePlan(List.of(
            new CreateInstanceProxyStep(Markable.class),
            new InstantiateStep(forConstructor(lookup(), TestImpl.class.getDeclaredConstructor(Object.class))),
            new RollupStep(TestImpl.class, Markable.class),
            new ResolveInstanceProxyStep(Markable.class)
        ));
        var proxy = (Markable)recordingPool.allInstanceProxies.get(Markable.class);
        assertNotNull(proxy);
        Markable instance = interpreter.theInstanceOf(Markable.class);
        assertSame(marker, instance.marker());
        assertSame(proxy.marker(), instance.marker());
    }

    public void testUnresolvedInstanceProxy() {
        PlanInterpreter interpreter = new PlanInterpreter(Map.of(), recordingPool);
        interpreter.executePlan(List.of(
            new CreateInstanceProxyStep(Runnable.class)
        ));
        Runnable proxy = (Runnable)recordingPool.allInstanceProxies.get(Runnable.class);
        assertThrows(IllegalStateException.class, proxy::run);
    }

    public void testUnresolvedCollectionProxy() {
        PlanInterpreter interpreter = new PlanInterpreter(Map.of(), recordingPool);
        interpreter.executePlan(List.of(
            new CreateCollectionProxyStep(String.class)
        ));
        Collection<?> proxy = recordingPool.allCollectionProxies.get(String.class);
        assertThrows(IllegalStateException.class, proxy::size);
        assertThrows(IllegalStateException.class, proxy::iterator);
    }

    public interface Service {
        String name();
    }

    public void testInstanceProxy() throws NoSuchMethodException, IllegalAccessException {
        record ServiceImpl(String name) implements Service {}
        record Client(@Proxy Service service) {}

        PlanInterpreter interpreter = new PlanInterpreter(Map.of(String.class, "hello"), recordingPool);
        interpreter.executePlan(List.of(
            new CreateInstanceProxyStep(Service.class),
            new InstantiateStep(forConstructor(lookup(), Client.class.getDeclaredConstructor(Service.class))),

            new InstantiateStep(forConstructor(lookup(), ServiceImpl.class.getDeclaredConstructor(String.class))),
            new RollupStep(ServiceImpl.class, Service.class),
            new ResolveInstanceProxyStep(Service.class)
        ));
        var proxy = (Service)recordingPool.allInstanceProxies.get(Service.class);
        assertNotNull(proxy);
        assertEquals("hello", proxy.name());
        assertSame(proxy, interpreter.theInstanceOf(Client.class).service());
    }
}
