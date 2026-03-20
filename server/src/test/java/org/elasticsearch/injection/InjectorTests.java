/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the "Elastic License
 * 2.0", the "GNU Affero General Public License v3.0 only", and the "Server Side
 * Public License v 1"; you may not use this file except in compliance with, at
 * your election, the "Elastic License 2.0", the "GNU Affero General Public
 * License v3.0 only", or the "Server Side Public License, v 1".
 */

package org.elasticsearch.injection;

import org.elasticsearch.injection.api.Actual;
import org.elasticsearch.injection.api.CyclicDependencyException;
import org.elasticsearch.injection.api.InjectionConfigurationException;
import org.elasticsearch.injection.api.UnresolvedProxyException;
import org.elasticsearch.test.ESTestCase;

import java.lang.invoke.MethodHandles;
import java.util.List;
import java.util.Set;

public class InjectorTests extends ESTestCase {

    public record First() {}

    public record Second(First first) {}

    public record Third(First first, Second second) {}

    public record ExistingInstances(First first, Second second) {}

    public void testMultipleResultsMap() {
        Injector injector = Injector.create().addClasses(List.of(Service1.class, Component3.class));
        var resultMap = injector.inject(List.of(Service1.class, Component3.class));
        assertEquals(Set.of(Service1.class, Component3.class), resultMap.keySet());
        Service1 service1 = (Service1) resultMap.get(Service1.class);
        Component3 component3 = (Component3) resultMap.get(Component3.class);
        assertSame(service1, component3.service1());
    }

    /**
     * In most cases, if there are two objects that are instances of a class, that's ambiguous.
     * However, if a concrete (non-abstract) superclass is configured directly, that is not ambiguous:
     * the instance of that superclass takes precedence over any instances of any subclasses.
     */
    public void testConcreteSubclass() {
        MethodHandles.lookup();
        assertEquals(
            Superclass.class,
            Injector.create()
                .addClasses(List.of(Superclass.class, Subclass.class)) // Superclass first
                .inject(List.of(Superclass.class))
                .get(Superclass.class)
                .getClass()
        );
        MethodHandles.lookup();
        assertEquals(
            Superclass.class,
            Injector.create()
                .addClasses(List.of(Subclass.class, Superclass.class)) // Subclass first
                .inject(List.of(Superclass.class))
                .get(Superclass.class)
                .getClass()
        );
        MethodHandles.lookup();
        assertEquals(
            Superclass.class,
            Injector.create()
                .addClasses(List.of(Subclass.class))
                .inject(List.of(Superclass.class)) // Superclass is not mentioned until here
                .get(Superclass.class)
                .getClass()
        );
    }

    //
    // List injection tests
    //

    public void testInjectionOfLists() {
        Injector injector = Injector.create();
        injector.addClasses(List.of(Component1.class, GoodService.class));
        GoodService service = injector.inject(GoodService.class);
        assertNotNull(service.components());
        assertEquals(1, service.components().size());
        assertNotNull(service.components().get(0));
    }

    public void testInjectionOfMultipleLists() {
        Injector injector = Injector.create();
        injector.addClasses(List.of(Component1.class, Component2.class, MultiService.class));
        MultiService service = injector.inject(MultiService.class);
        assertNotNull(service.component1s());
        assertEquals(1, service.component1s().size());
        assertNotNull(service.component2s());
        assertEquals(1, service.component2s().size());
    }

    public void testSupertypeList() {
        Injector injector = Injector.create();
        injector.addClasses(List.of(Component1.class, ListenerService.class));
        ListenerService service = injector.inject(ListenerService.class);
        assertNotNull(service.listeners());
        assertEquals(1, service.listeners().size());
        assertTrue(service.listeners().get(0) instanceof Component1);
    }

    public void testSupertypeListMultipleImplementations() {
        record Listener1() implements Listener {}
        record Listener2() implements Listener {}
        Injector injector = Injector.create();
        injector.addClasses(List.of(Listener1.class, Listener2.class, ListenerService.class));
        ListenerService service = injector.inject(ListenerService.class);
        assertEquals(List.of(new Listener1(), new Listener2()), service.listeners());
    }

    public void testMutualInjectionViaList() {
        // Alpha takes List<Listener>, Beta implements Listener and takes Alpha
        // The proxy list breaks the cycle
        Injector injector = Injector.create();
        injector.addClasses(List.of(Alpha.class, Beta.class));
        Alpha alpha = injector.inject(Alpha.class);
        assertNotNull(alpha.listeners());
        assertEquals(1, alpha.listeners().size());
        assertTrue(alpha.listeners().get(0) instanceof Beta);
        Beta beta = (Beta) alpha.listeners().get(0);
        assertSame(alpha, beta.alpha());
    }

    //
    // Single-result convenience method
    //

    public void testInjectSingleResult() {
        Injector injector = Injector.create();
        injector.addClass(Service1.class);
        Service1 result = injector.inject(Service1.class);
        assertNotNull(result);
    }

    //
    // addRecordContents
    //

    public void testInjectionOfRecordComponents() {
        record Config(String name, Integer count) {}
        record UsesConfig(String name, Integer count) {}

        Injector injector = Injector.create();
        injector.addRecordContents(new Config("hello", 42));
        injector.addClass(UsesConfig.class);
        UsesConfig result = injector.inject(UsesConfig.class);
        assertEquals("hello", result.name());
        assertEquals(Integer.valueOf(42), result.count());
    }

    //
    // Sad paths
    //

    public void testBadInterfaceClass() {
        assertThrows(InjectionConfigurationException.class, () -> {
            MethodHandles.lookup();
            Injector.create().addClass(Listener.class).inject(List.of());
        });
    }

    public void testBadUnknownType() {
        // Injector knows only about Component4, discovers Listener, but can't find any subtypes
        MethodHandles.lookup();
        Injector injector = Injector.create().addClass(Component4.class);

        assertThrows(InjectionConfigurationException.class, () -> injector.inject(List.of()));
    }

    public void testBadCircularDependency() {
        assertThrows(CyclicDependencyException.class, () -> {
            MethodHandles.lookup();
            Injector injector = Injector.create();
            injector.addClasses(List.of(Circular1.class, Circular2.class)).inject(List.of());
        });
    }

    /**
     * For this one, we don't explicitly tell the injector about the classes involved in the cycle;
     * it finds them on its own.
     */
    public void testBadCircularDependencyViaParameter() {
        record UsesCircular1(Circular1 circular1) {}
        assertThrows(CyclicDependencyException.class, () -> {
            MethodHandles.lookup();
            Injector.create().addClass(UsesCircular1.class).inject(List.of());
        });
    }

    public void testBadCircularDependencyViaSupertype() {
        interface Service1 {}
        record Service2(Service1 service1) {}
        record Service3(Service2 service2) implements Service1 {}
        assertThrows(CyclicDependencyException.class, () -> {
            MethodHandles.lookup();
            Injector injector = Injector.create();
            injector.addClasses(List.of(Service2.class, Service3.class)).inject(List.of());
        });
    }

    public void testBadUseOfListProxy() {
        // BadService tries to use the list during construction
        assertThrows(UnresolvedProxyException.class, () -> {
            Injector injector = Injector.create();
            injector.addClasses(List.of(Component1.class, BadService.class));
            injector.inject(BadService.class);
        });
    }

    public void testBadCircularDependencyViaActualList() {
        // @Actual List creates a hard ordering constraint that exposes the cycle
        assertThrows(CyclicDependencyException.class, () -> {
            Injector injector = Injector.create();
            injector.addClasses(List.of(Gamma.class, Delta.class));
            injector.inject(Gamma.class);
        });
    }

    // Common injectable things

    public record Service1() {}

    public interface Listener {}

    public record Component1() implements Listener {}

    public record Component2(Component1 component1) {}

    public record Component3(Service1 service1) {}

    public record Component4(Listener listener) {}

    public record GoodService(List<Component1> components) {}

    public record BadService(List<Component1> components) {
        public BadService {
            // Shouldn't be using the component list here!
            assert components.isEmpty() == false;
        }
    }

    public record MultiService(List<Component1> component1s, List<Component2> component2s) {}

    public record ListenerService(List<Listener> listeners) {}

    public record Circular1(Circular2 service2) {}

    public record Circular2(Circular1 service2) {}

    public static class Superclass {}

    public static class Subclass extends Superclass {}

    // For mutual injection via list test
    public record Alpha(List<Listener> listeners) {}

    public record Beta(Alpha alpha) implements Listener {}

    // For @Actual circular dependency test
    public record Gamma(@Actual List<Listener> listeners) {}

    public record Delta(Gamma gamma) implements Listener {}

}
