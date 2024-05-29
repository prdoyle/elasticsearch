/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0 and the Server Side Public License, v 1; you may not use this file except
 * in compliance with, at your election, the Elastic License 2.0 or the Server
 * Side Public License, v 1.
 */

package org.elasticsearch.nalbind.injector.impl;

import org.elasticsearch.test.ESTestCase;

import java.lang.annotation.Retention;
import java.lang.annotation.Target;
import java.util.Set;

import static java.lang.annotation.ElementType.TYPE;
import static java.lang.annotation.RetentionPolicy.RUNTIME;

public class ClassFinderImplTest extends ESTestCase {
    public void testClassesOnClasspathWithAnnotation() {
        var actual = new ClassFinderImpl().classesOnClasspathWithAnnotation(TestAnnotation.class);
        Set<Class<?>> expected = Set.of(
            Class1.class,
            Interface2.class, Class2.class,
            Interface3.class, Class3.class
        );
        assertEquals(expected, Set.copyOf(actual));
    }

    @Target(TYPE)
    @Retention(RUNTIME)
    @interface TestAnnotation { }

    @TestAnnotation
    static class Class1 {}

    @TestAnnotation
    interface Interface2 {}

    static class Class2 implements Interface2 {}

    interface Interface3 extends Interface2 {}

    static class Class3 implements Interface3 {}
}
