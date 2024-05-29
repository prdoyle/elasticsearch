/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0 and the Server Side Public License, v 1; you may not use this file except
 * in compliance with, at your election, the Elastic License 2.0 or the Server
 * Side Public License, v 1.
 */

package org.elasticsearch.nalbind.injector.impl;

import org.elasticsearch.nalbind.injector.spi.ClassFinder;
import org.elasticsearch.plugin.scanner.ClassReaders;
import org.elasticsearch.plugin.scanner.ClassScanner;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Type;

import java.io.IOException;
import java.lang.annotation.Annotation;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;

import static java.util.Collections.unmodifiableList;

public class ClassFinderImpl implements ClassFinder {
    @Override
    public Collection<Class<?>> classesOnClasspathWithAnnotation(Class<? extends Annotation> annotation) {
        var scanner = new ClassScanner(Type.getDescriptor(annotation), (className, map) -> {
            map.put(className, className);
            return null;
        });
        List<Class<?>> result = new ArrayList<>();
        try {
            scanner.visit(ClassReaders.ofClassPath());

            // getFoundClasses returns a map whose keys are the classes of interest
            // and the values are the supertype which had the annotation we're looking for.
            Set<String> foundClassInternalNames = scanner.getFoundClasses().keySet();
            for (String internalName : foundClassInternalNames) {
                String className = Type.getObjectType(internalName).getClassName();
                result.add(Class.forName(className));
            }
        } catch (IOException | ClassNotFoundException e) {
            throw new IllegalStateException("Unable to scan classes", e);
        }
        return unmodifiableList(result);
    }
}
