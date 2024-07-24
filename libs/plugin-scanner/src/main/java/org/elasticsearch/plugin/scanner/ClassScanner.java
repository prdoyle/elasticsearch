/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0 and the Server Side Public License, v 1; you may not use this file except
 * in compliance with, at your election, the Elastic License 2.0 or the Server
 * Side Public License, v 1.
 */

package org.elasticsearch.plugin.scanner;

import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassReader;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.function.Function;

import static java.util.Collections.unmodifiableMap;
import static java.util.Objects.requireNonNull;

public class ClassScanner {
    private final AnnotatedHierarchyVisitor annotatedHierarchyVisitor;
    private final Map<String, Map<String, String>> foundClassMapsByAnnotation;

    public ClassScanner(Map<String, BiFunction<String, Map<String, String>, AnnotationVisitor>> mapUpdatersByAnnotationDescriptor) {
        var visitorMap = new LinkedHashMap<String, Function<String, AnnotationVisitor>>();
        var foundClassMap = new LinkedHashMap<String, Map<String, String>>();
        mapUpdatersByAnnotationDescriptor.forEach((annotation, updater) -> {
            var foundClasses = new HashMap<String, String>();
            foundClassMap.put(annotation, foundClasses);
            visitorMap.put(annotation, className -> updater.apply(className, foundClasses));
        });
        this.annotatedHierarchyVisitor = new AnnotatedHierarchyVisitor(unmodifiableMap(visitorMap));
        this.foundClassMapsByAnnotation = unmodifiableMap(foundClassMap);
    }

    public void visit(List<ClassReader> classReaders) {
        // First: find all annotated classes
        classReaders.forEach(classReader -> classReader.accept(annotatedHierarchyVisitor, ClassReader.SKIP_CODE));

        // Next: augment foundClasses
        this.foundClassMapsByAnnotation.values()
            .forEach(foundClasses -> addExtensibleDescendants(annotatedHierarchyVisitor.getClassHierarchy(), foundClasses));
    }

    public void addExtensibleDescendants(Map<String, Set<String>> classToSubclasses, Map<String, String> foundClasses) {
        Deque<Map.Entry<String, String>> toCheckDescendants = new ArrayDeque<>(foundClasses.entrySet());
        Set<String> processed = new HashSet<>();
        while (toCheckDescendants.isEmpty() == false) {
            var e = toCheckDescendants.removeFirst();
            String classname = e.getKey();
            if (processed.contains(classname)) {
                continue;
            }
            Set<String> subclasses = classToSubclasses.get(classname);
            if (subclasses == null) {
                continue;
            }

            for (String subclass : subclasses) {
                foundClasses.put(subclass, e.getValue());
                toCheckDescendants.addLast(Map.entry(subclass, e.getValue()));
            }
            processed.add(classname);
        }
    }

    public Map<String, String> getFoundClasses(String annotationDescriptor) {
        return requireNonNull(foundClassMapsByAnnotation.get(annotationDescriptor));
    }

}
