/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0 and the Server Side Public License, v 1; you may not use this file except
 * in compliance with, at your election, the Elastic License 2.0 or the Server
 * Side Public License, v 1.
 */

package org.elasticsearch.plugin.scanner;

import org.elasticsearch.nalbind.api.AutoInjectable;
import org.elasticsearch.xcontent.XContentBuilder;
import org.elasticsearch.xcontent.XContentFactory;
import org.objectweb.asm.ClassReader;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.List;
import java.util.Map;

import static org.objectweb.asm.Type.getDescriptor;

/**
 * Scans for classes that are annotated to indicate they should be included in the dependency injection
 * performed during node initialization.
 */
public class AutoInjectionScanner {

    public static void main(String[] args) throws IOException {
        Collection<String> classNames = scanForAutoInjectableClasses(ClassReaders.ofClassPath()); // TODO: Scan plugins too?
        Path outputFile = Path.of(args[0]);
        Files.createDirectories(outputFile.getParent());
        try (OutputStream outputStream = Files.newOutputStream(outputFile)) {
            writeTo(outputStream, classNames);
        }
    }

    public static void writeTo(OutputStream outputStream, Collection<String> classNames) throws IOException {
        try (XContentBuilder builder = XContentFactory.jsonBuilder(outputStream)) {
            builder.startObject();
            builder.startArray("classes");
            for (var className : classNames) {
                builder.value(className);
            }
            builder.endArray();
            builder.endObject();
        }
    }

    public static Collection<String> scanForAutoInjectableClasses(List<ClassReader> classReaders) {
        String autoInjectable = getDescriptor(AutoInjectable.class);
        ClassScanner scanner = new ClassScanner(Map.of(autoInjectable, (classDescriptor, map) -> {
            map.put(classDescriptor, classDescriptor);
            return null;
        }));

        scanner.visit(classReaders);
        return scanner.getFoundClasses(autoInjectable).keySet().stream().map(descriptor -> descriptor.replace('/', '.')).toList();
    }

}
