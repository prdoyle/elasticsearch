/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the "Elastic License
 * 2.0", the "GNU Affero General Public License v3.0 only", and the "Server Side
 * Public License v 1"; you may not use this file except in compliance with, at
 * your election, the "Elastic License 2.0", the "GNU Affero General Public
 * License v3.0 only", or the "Server Side Public License, v 1".
 */

package org.elasticsearch.injection;

import org.elasticsearch.test.ESTestCase;

import java.util.Collection;
import java.util.List;
import java.util.Set;

/**
 * Tests of the entire {@link Injector} "workflow" to ensure the resulting object graphs are correct.
 */
public class FullInjectionTests extends ESTestCase {

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
        assertEquals(
            Superclass.class,
            Injector.create()
                .addClasses(List.of(Superclass.class, Subclass.class)) // Superclass first
                .inject(List.of(Superclass.class))
                .get(Superclass.class)
                .getClass()
        );
        assertEquals(
            Superclass.class,
            Injector.create()
                .addClasses(List.of(Subclass.class, Superclass.class)) // Subclass first
                .inject(List.of(Superclass.class))
                .get(Superclass.class)
                .getClass()
        );
        assertEquals(
            Superclass.class,
            Injector.create()
                .addClasses(List.of(Subclass.class))
                .inject(List.of(Superclass.class)) // Superclass is not mentioned until here
                .get(Superclass.class)
                .getClass()
        );
    }

    public void testListeners() {
        SomeClient client = (SomeClient) Injector.create()
            .addClasses(List.of(SomeClient.class))
            .inject(List.of(SomeClient.class))
            .get(SomeClient.class);
        assertNotNull(client);
        var listeners = client.service().listeners();
        assertEquals(1, listeners.size());
        assertSame(client, client.service().listeners().iterator().next());
    }

    //
    // Sad paths
    //

    public void testBadInterfaceClass() {
        assertThrows(IllegalStateException.class, () -> {
            Injector.create().addClass(Listener.class).inject(List.of());
        });
    }

    public void testBadUnknownType() {
        // Injector knows only about Component4, discovers Listener, but can't find any subtypes
        Injector injector = Injector.create().addClass(Component4.class);

        assertThrows(IllegalStateException.class, () -> injector.inject(List.of()));
    }

    public void testBadCircularDependency() {
        assertThrows(IllegalStateException.class, () -> {
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
        assertThrows(IllegalStateException.class, () -> {
            Injector.create().addClass(UsesCircular1.class).inject(List.of());
        });
    }

    public void testBadCircularDependencyViaSupertype() {
        interface Service1 {}
        record Service2(Service1 service1) {}
        record Service3(Service2 service2) implements Service1 {}
        assertThrows(IllegalStateException.class, () -> {
            Injector injector = Injector.create();
            injector.addClasses(List.of(Service2.class, Service3.class)).inject(List.of());
        });
    }

    // Common injectable things

    public record Service1() {}

    public interface Listener {}

    public record Component3(Service1 service1) {}

    public record Component4(Listener listener) {}

    public record Circular1(Circular2 service2) {}

    public record Circular2(Circular1 service2) {}

    public static class Superclass {}

    public static class Subclass extends Superclass {}

    public interface SomeListener {}

    public record SomeService(Collection<SomeListener> listeners) {}

    public record SomeClient(SomeService service) implements SomeListener {}
}
