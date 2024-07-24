/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0 and the Server Side Public License, v 1; you may not use this file except
 * in compliance with, at your election, the Elastic License 2.0 or the Server
 * Side Public License, v 1.
 */

package org.elasticsearch.plugins.scanners;

import org.elasticsearch.plugins.PluginDescriptor;
import org.elasticsearch.xcontent.XContentParserConfiguration;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static java.util.Objects.requireNonNull;
import static org.elasticsearch.xcontent.XContentType.JSON;

public class AutoInjectableReader {

    public Stream<Class<?>> autoInjectableClasses(Path rootDirectory, ClassLoader classLoader) throws IOException {
        Path fileName = rootDirectory.resolve(PluginDescriptor.AUTO_INJECT_FILENAME);
        if (fileName.toFile().exists()) {
            try (InputStream is = Files.newInputStream(fileName)) {
                return autoInjectableClasses(is, classLoader);
            }
        } else {
            return Stream.empty();
        }
    }

    public Stream<Class<?>> autoInjectableClasses(InputStream is, ClassLoader classLoader) throws IOException {
        List<?> classNameList;
        try (var json = new BufferedInputStream(requireNonNull(is));
             var parser = JSON.xContent().createParser(XContentParserConfiguration.EMPTY, json)
        ) {
            var map = parser.map();
            classNameList = (List<?>) map.get("classes");
        }
        return classNameList.stream()
            .map(String.class::cast)
            .map(name -> classForName(classLoader, name));
    }

    private static Class<?> classForName(ClassLoader pluginClassLoader, String name) {
        try {
            return Class.forName(name, false, pluginClassLoader);
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException(e);
        }
    }
}
