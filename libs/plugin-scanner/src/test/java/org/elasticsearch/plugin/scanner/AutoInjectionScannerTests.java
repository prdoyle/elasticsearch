/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0 and the Server Side Public License, v 1; you may not use this file except
 * in compliance with, at your election, the Elastic License 2.0 or the Server
 * Side Public License, v 1.
 */

package org.elasticsearch.plugin.scanner;

import org.elasticsearch.nalbind.api.AutoInjectable;
import org.elasticsearch.test.ESTestCase;
import org.objectweb.asm.ClassReader;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Collection;
import java.util.List;
import java.util.Set;

import static java.nio.charset.StandardCharsets.UTF_8;

public class AutoInjectionScannerTests extends ESTestCase {

    public void testScanForAutoInjectableClasses() throws IOException {
        @AutoInjectable interface TestInterface {}
        class TestClass implements TestInterface {}
        class UninjectableClass {}

        Collection<String> actual = AutoInjectionScanner.scanForAutoInjectableClasses(
            List.of(
                new ClassReader(TestInterface.class.getName()),
                new ClassReader(TestClass.class.getName()),
                new ClassReader(UninjectableClass.class.getName())
            )
        );
        Set<String> expected = Set.of(TestInterface.class.getName(), TestClass.class.getName());
        assertEquals(expected, Set.copyOf(actual));
    }

    public void testWriteTo() throws IOException {
        var outputStream = new ByteArrayOutputStream();
        try (outputStream) {
            AutoInjectionScanner.writeTo(outputStream, List.of("org.example.Class1"));
        }
        var actual = outputStream.toString(UTF_8);
        assertEquals("{\"classes\":[\"org.example.Class1\"]}", actual);
    }

}
