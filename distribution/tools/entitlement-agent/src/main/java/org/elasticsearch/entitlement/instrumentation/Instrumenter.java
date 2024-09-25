/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the "Elastic License
 * 2.0", the "GNU Affero General Public License v3.0 only", and the "Server Side
 * Public License v 1"; you may not use this file except in compliance with, at
 * your election, the "Elastic License 2.0", the "GNU Affero General Public
 * License v3.0 only", or the "Server Side Public License, v 1".
 */

package org.elasticsearch.entitlement.instrumentation;

import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.util.Map;

import static org.objectweb.asm.ClassWriter.COMPUTE_FRAMES;
import static org.objectweb.asm.ClassWriter.COMPUTE_MAXS;
import static org.objectweb.asm.Opcodes.ACC_STATIC;
import static org.objectweb.asm.Opcodes.GETSTATIC;
import static org.objectweb.asm.Opcodes.INVOKESTATIC;
import static org.objectweb.asm.Opcodes.INVOKEVIRTUAL;

public class Instrumenter {
    /**
     * To avoid class name collisions during testing. Should be an empty string in production.
     */
    private final String classNameSuffix;
    private final Map<MethodKey, Method> instrumentationMethods;

    public Instrumenter(String classNameSuffix, Map<MethodKey, Method> instrumentationMethods) {
        this.classNameSuffix = classNameSuffix;
        this.instrumentationMethods = instrumentationMethods;
    }

    public ClassFileInfo instrumentClassFile(Class<?> clazz) throws IOException {
        ClassFileInfo initial = getClassFileInfo(clazz);
        return new ClassFileInfo(initial.fileName(), instrumentClass(Type.getInternalName(clazz), initial.bytecodes()));
    }

    public static ClassFileInfo getClassFileInfo(Class<?> clazz) throws IOException {
        String internalName = Type.getInternalName(clazz);
        String fileName = "/" + internalName + ".class";
        byte[] originalBytecodes;
        try (InputStream classStream = clazz.getResourceAsStream(fileName)) {
            if (classStream == null) {
                throw new IllegalStateException("Classfile not found in jar: " + fileName);
            }
            originalBytecodes = classStream.readAllBytes();
        }
        return new ClassFileInfo(fileName, originalBytecodes);
    }

    public byte[] instrumentClass(String className, byte[] classfileBuffer) {
        ClassReader reader = new ClassReader(classfileBuffer);
        ClassWriter writer = new ClassWriter(reader, COMPUTE_FRAMES | COMPUTE_MAXS);
        ClassVisitor visitor = new EntitlementClassVisitor(Opcodes.ASM9, writer, className);
        reader.accept(visitor, 0);
        return writer.toByteArray();
    }

    class EntitlementClassVisitor extends ClassVisitor {
        final String className;

        EntitlementClassVisitor(int api, ClassVisitor classVisitor, String className) {
            super(api, classVisitor);
            this.className = className;
        }

        @Override
        public void visit(int version, int access, String name, String signature, String superName, String[] interfaces) {
            super.visit(version, access, name + classNameSuffix, signature, superName, interfaces);
        }

        @Override
        public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
            var mv = super.visitMethod(access, name, descriptor, signature, exceptions);
            boolean isStatic = (access & ACC_STATIC) != 0;
            var voidDescriptor = Type.getMethodDescriptor(Type.VOID_TYPE, Type.getArgumentTypes(descriptor));
            var key = new MethodKey(className, name, voidDescriptor, isStatic);
            var instrumentationMethod = instrumentationMethods.get(key);
            if (instrumentationMethod != null) {
                // LOGGER.debug("Will instrument method {}", key);
                return new EntitlementMethodVisitor(Opcodes.ASM9, mv, isStatic, descriptor, instrumentationMethod);
            } else {
                // LOGGER.trace("Will not instrument method {}", key);
            }
            return mv;
        }
    }

    static class EntitlementMethodVisitor extends MethodVisitor {
        private final boolean instrumentedMethodIsStatic;
        private final String instrumentedMethodDescriptor;
        private final Method instrumentationMethod;
        private boolean hasCallerSensitiveAnnotation = false;

        EntitlementMethodVisitor(
            int api,
            MethodVisitor methodVisitor,
            boolean instrumentedMethodIsStatic,
            String instrumentedMethodDescriptor,
            Method instrumentationMethod
        ) {
            super(api, methodVisitor);
            this.instrumentedMethodIsStatic = instrumentedMethodIsStatic;
            this.instrumentedMethodDescriptor = instrumentedMethodDescriptor;
            this.instrumentationMethod = instrumentationMethod;
        }

        @Override
        public AnnotationVisitor visitAnnotation(String descriptor, boolean visible) {
            if (visible && descriptor.endsWith("CallerSensitive;")) {
                hasCallerSensitiveAnnotation = true;
            }
            return super.visitAnnotation(descriptor, visible);
        }

        @Override
        public void visitCode() {
            pushCallerClass();
            forwardIncomingArguments();
            invokeInstrumentationMethod();
            super.visitCode();
        }

        private void pushCallerClass() {
            if (hasCallerSensitiveAnnotation) {
                mv.visitMethodInsn(INVOKESTATIC, "jdk/internal/reflect/Reflection", "getCallerClass", "()Ljava/lang/Class;", false);
            } else {
                mv.visitFieldInsn(GETSTATIC, "java/lang/StackWalker$Option", "RETAIN_CLASS_REFERENCE", "Ljava/lang/StackWalker$Option;");
                mv.visitMethodInsn(
                    INVOKESTATIC,
                    "java/lang/StackWalker",
                    "getInstance",
                    "(Ljava/lang/StackWalker$Option;)Ljava/lang/StackWalker;",
                    false
                );
                mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/StackWalker", "getCallerClass", "()Ljava/lang/Class;", false);
            }
        }

        private void forwardIncomingArguments() {
            int localVarIndex = 0;
            if (instrumentedMethodIsStatic) {
                // To keep things consistent between static and virtual methods, we pass a null in here,
                // analogous to how Field and Method accept nulls for static fields/methods.
                mv.visitInsn(Opcodes.ACONST_NULL);
            } else {
                mv.visitVarInsn(Opcodes.ALOAD, localVarIndex++);
            }
            for (Type type : Type.getArgumentTypes(instrumentedMethodDescriptor)) {
                mv.visitVarInsn(type.getOpcode(Opcodes.ILOAD), localVarIndex);
                localVarIndex += type.getSize();
            }

        }

        private void invokeInstrumentationMethod() {
            mv.visitMethodInsn(
                INVOKESTATIC,
                Type.getInternalName(instrumentationMethod.getDeclaringClass()),
                instrumentationMethod.getName(),
                Type.getMethodDescriptor(instrumentationMethod),
                false
            );
        }
    }

    // private static final Logger LOGGER = LogManager.getLogger(Instrumenter.class);

    public record ClassFileInfo(String fileName, byte[] bytecodes) {}
}
