/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0 and the Server Side Public License, v 1; you may not use this file except
 * in compliance with, at your election, the Elastic License 2.0 or the Server
 * Side Public License, v 1.
 */

package org.elasticsearch.plugin.scanner;

import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

import static java.util.Collections.emptySet;

/**
 * An ASM class visitor that captures the class hierarchy, as well as finds a specific annotation.
 */
public class AnnotatedHierarchyVisitor extends ClassVisitor {
    private String currentClassName;
    private final Map<String, Function<String, AnnotationVisitor>> annotationVisitorsByAnnotationDescriptor;
    private final Set<String> methodAnnotations = emptySet();
    private final Map<String, Set<String>> classToSubclasses = new HashMap<>();
    private static final String OBJECT_NAME = Object.class.getCanonicalName().replace('.', '/');

    /**
     * @param annotationVisitorsByAnnotationDescriptor map whose keys are type descriptors of annotation classes,
     *                                                 and whose values are functions that take the class name the
     *                                                 target annotation appeared on, and return an {@link AnnotationVisitor}
     *                                                 that can be used to capture annotation-specific args.
     */
    AnnotatedHierarchyVisitor(Map<String, Function<String, AnnotationVisitor>> annotationVisitorsByAnnotationDescriptor) {
        super(Opcodes.ASM9);
        this.annotationVisitorsByAnnotationDescriptor = annotationVisitorsByAnnotationDescriptor;
    }

    @Override
    public void visit(int version, int access, String name, String signature, String superName, String[] interfaces) {
        currentClassName = name;
        if (OBJECT_NAME.equals(superName) == false) {
            classToSubclasses.computeIfAbsent(superName, k -> new HashSet<>()).add(name);
        }

        for (String iface : interfaces) {
            classToSubclasses.computeIfAbsent(iface, k -> new HashSet<>()).add(name);
        }
    }

    @Override
    public AnnotationVisitor visitAnnotation(String descriptor, boolean visible) {
        var visitorFunction = annotationVisitorsByAnnotationDescriptor.get(descriptor);
        if (visitorFunction == null) {
            return null;
        } else {
            return visitorFunction.apply(currentClassName);
        }
    }

    @Override
    public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
        return methodAnnotationVisitor;
    }

    final MethodVisitor methodAnnotationVisitor = new MethodVisitor(Opcodes.ASM9) {
        @Override
        public AnnotationVisitor visitAnnotation(String descriptor, boolean visible) {
            // Handle method annotations the same as annotations on the class itself
            return AnnotatedHierarchyVisitor.this.visitAnnotation(descriptor, visible);
        }
    };

    /**
     * Returns a mapping of class name to subclasses of that class
     */
    public Map<String, Set<String>> getClassHierarchy() {
        return classToSubclasses;
    }
}
