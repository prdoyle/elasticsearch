/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the "Elastic License
 * 2.0", the "GNU Affero General Public License v3.0 only", and the "Server Side
 * Public License v 1"; you may not use this file except in compliance with, at
 * your election, the "Elastic License 2.0", the "GNU Affero General Public
 * License v3.0 only", or the "Server Side Public License, v 1".
 */

package org.elasticsearch.entitlement.agent;

import org.elasticsearch.entitlement.runtime.api.EntitlementChecks;
import org.elasticsearch.entitlement.runtime.api.NotEntitledException;
import org.elasticsearch.entitlement.runtime.internals.EntitlementInternals;
import org.elasticsearch.test.ESTestCase;
import org.elasticsearch.test.ESTestCase.WithoutSecurityManager;
import org.junit.After;
import org.junit.Before;

import java.io.File;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * This is an end-to-end test that runs with the javaagent installed.
 * It should exhaustively test every instrumented method to make sure it passes with the entitlement
 * and fails without it.
 * See {@code build.gradle} for how we set the command line arguments for this test.
 */
@WithoutSecurityManager
public class EntitlementAgentTests extends ESTestCase {

//    @Before
//    public void loadSomeClasses() {
//        new NotEntitledException("warming up");
//    }

//    public void testAgentBooted() {
//        assertTrue(EntitlementChecks.isAgentBooted());
//    }

    @After
    public void deactivate() {
        // Without this, JUnit can't exit
        EntitlementInternals.isActive = false;
    }

    /**
     * We can't really check that this one passes because it will just exit the JVM.
     */
    public void test_exitNotEntitled_throws() throws NoSuchMethodException, InvocationTargetException, IllegalAccessException {
//        System.err.println("!!! Classpath: " + String.join("\n", System.getProperty("java.class.path").split(File.pathSeparator)));
        System.err.println("!!! test object class loader: " + getClass().getClassLoader());
        EntitlementChecks.activate();
        Class<NotEntitledException> expectedThrowable = NotEntitledException.class;
        var info = NotEntitledException.CLASS_LOADER_INFO;
        System.err.println("!!! expected classloader info: " + info);
        Throwable thrown = assertThrows(Throwable.class, () -> System.exit(123));
        System.err.println("!!! thrown classloader is " + thrown.getClass().getClassLoader());
        assertSame(expectedThrowable.getClassLoader(), thrown.getClass().getClassLoader());
    }

}
