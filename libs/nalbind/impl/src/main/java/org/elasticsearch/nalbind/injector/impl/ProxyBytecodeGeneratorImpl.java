/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the "Elastic License
 * 2.0", the "GNU Affero General Public License v3.0 only", and the "Server Side
 * Public License v 1"; you may not use this file except in compliance with, at
 * your election, the "Elastic License 2.0", the "GNU Affero General Public
 * License v3.0 only", or the "Server Side Public License, v 1".
 */

package org.elasticsearch.nalbind.injector.impl;

import org.elasticsearch.nalbind.injector.runtime.ProxyRuntime;
import org.elasticsearch.nalbind.injector.spi.ProxyBytecodeGenerator;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Handle;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

import java.lang.invoke.CallSite;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.invoke.MutableCallSite;
import java.lang.reflect.Method;
import java.util.HashSet;

import static java.lang.invoke.MethodHandles.lookup;
import static java.lang.invoke.MethodType.methodType;
import static java.lang.reflect.Modifier.isStatic;
import static org.elasticsearch.nalbind.injector.runtime.ProxyRuntime.callSites;
import static org.elasticsearch.nalbind.injector.runtime.ProxyRuntime.numCallSites;
import static org.objectweb.asm.ClassWriter.COMPUTE_FRAMES;
import static org.objectweb.asm.ClassWriter.COMPUTE_MAXS;
import static org.objectweb.asm.Opcodes.ACC_FINAL;
import static org.objectweb.asm.Opcodes.ACC_PUBLIC;
import static org.objectweb.asm.Opcodes.ALOAD;
import static org.objectweb.asm.Opcodes.ILOAD;
import static org.objectweb.asm.Opcodes.INVOKEINTERFACE;
import static org.objectweb.asm.Opcodes.INVOKESPECIAL;
import static org.objectweb.asm.Opcodes.INVOKESTATIC;
import static org.objectweb.asm.Opcodes.INVOKEVIRTUAL;
import static org.objectweb.asm.Opcodes.IRETURN;
import static org.objectweb.asm.Opcodes.RETURN;
import static org.objectweb.asm.Opcodes.V1_8;
import static org.objectweb.asm.Type.getDescriptor;
import static org.objectweb.asm.Type.getInternalName;
import static org.objectweb.asm.Type.getMethodDescriptor;
import static org.objectweb.asm.Type.getReturnType;
import static org.objectweb.asm.Type.getType;

public class ProxyBytecodeGeneratorImpl implements ProxyBytecodeGenerator {
    private static final String PACKAGE_INTERNAL_NAME;

    static {
        String classInternalName = getInternalName(ProxyBytecodeGeneratorImpl.class);
        PACKAGE_INTERNAL_NAME = classInternalName.substring(0, classInternalName.lastIndexOf('/'));
    }

    public ProxyBytecodeGeneratorImpl(){}

    public <T> ProxyBytecodeInfo generateBytecodeFor(Class<T> interfaceType) {
        int callSiteNum = numCallSites.incrementAndGet();
        String methodName = "callSite_" + callSiteNum;
        MutableCallSite callSite = newCallSite(MethodType.methodType(interfaceType));
        callSites.put(methodName, callSite);

        String classInternalName = PACKAGE_INTERNAL_NAME + "/NALBIND_PROXY_" + methodName;
        ClassWriter cw = new ClassWriter(COMPUTE_MAXS | COMPUTE_FRAMES);
        cw.visit(
            V1_8,
            ACC_PUBLIC | ACC_FINAL,
            classInternalName,
            null,
            getInternalName(Object.class),
            new String[]{getInternalName(interfaceType)});

        generateConstructor(cw);
        HashSet<Class<?>> interfacesAlreadySeen = new HashSet<>();
        HashSet<DistinctMethod> distinctMethodsAlreadySeen = new HashSet<>();
        generateDelegatingMethods(interfaceType, interfacesAlreadySeen, distinctMethodsAlreadySeen, interfaceType, methodName, cw);

        cw.visitEnd();
        byte[] bytecodes = cw.toByteArray();
        return new ProxyBytecodeInfo(classInternalName, bytecodes, callSite);
    }

    private record DistinctMethod(String name, String descriptor){}

    private static <T> void generateDelegatingMethods(
        Class<?> interfaceType,
        HashSet<Class<?>> interfacesAlreadySeen,
        HashSet<DistinctMethod> distinctMethodsAlreadySeen,
        Class<?> targetType,
        String methodName,
        ClassWriter cw
    ) {
		if (interfacesAlreadySeen.add(interfaceType)) {
			//LOGGER.trace("generateDelegatingMethods for {}", interfaceType);
		} else {
			return;
		}

		for (Class<?> s: interfaceType.getInterfaces()) {
			generateDelegatingMethods(s, interfacesAlreadySeen, distinctMethodsAlreadySeen, targetType, methodName, cw);
		}

		for (Method m: interfaceType.getDeclaredMethods()) {
			generateDelegatingMethod(m, distinctMethodsAlreadySeen, targetType, methodName, cw);
		}
	}

	private static <T> void generateDelegatingMethod(Method m, HashSet<DistinctMethod> distinctMethodsAlreadySeen, Class<T> targetType, String targetMethodName, ClassWriter cw) {
        //LOGGER.trace("generateDelegatingMethod {}", m);

        var distinctMethod = new DistinctMethod(m.getName(), Type.getMethodDescriptor(m));
        if (distinctMethodsAlreadySeen.add(distinctMethod) == false) {
            //LOGGER.trace("Already generated: {}", distinctMethod);
            return;
        }

		MethodVisitor mv = cw.visitMethod(ACC_PUBLIC, m.getName(), Type.getMethodDescriptor(m), null, null);
		mv.visitCode();

		// Push delegation target object
		getTarget(targetType, mv, targetMethodName);

		// Push args
		int localSlot = 1;
		for (Class<?> pt: m.getParameterTypes()) {
			mv.visitVarInsn(getType(pt).getOpcode(ILOAD), localSlot);
			localSlot += Type.getType(pt).getSize();
		}

		// Invoke and return result
		invoke(m, mv);
		mv.visitInsn(getReturnType(m).getOpcode(IRETURN));

		mv.visitMaxs(0, 0);
		mv.visitEnd();
	}

	public static void invoke(Method method, MethodVisitor mv) {
		Class<?> type = method.getDeclaringClass();
		String typeName = Type.getInternalName(type);
		String methodName = method.getName();
		String signature = getMethodDescriptor(method);
		if (isStatic(method.getModifiers())) {
			// Static methods have no "this" argument
			mv.visitMethodInsn(INVOKESTATIC, typeName, methodName, signature, false);
		} else if (type.isInterface()) {
			mv.visitMethodInsn(INVOKEINTERFACE, typeName, methodName, signature, true);
		} else {
			mv.visitMethodInsn(INVOKEVIRTUAL, typeName, methodName, signature, false);
		}
	}

	private static <T> void getTarget(Class<T> interfaceType, MethodVisitor mv, String methodName) {
		Handle bootstrapMethodHandle = new Handle(
			Opcodes.H_INVOKESTATIC,

			getInternalName(ProxyRuntime.class),
			"bootstrap",
			MethodType.methodType(
                CallSite.class,
                MethodHandles.Lookup.class,
                String.class,
                MethodType.class)
                .toMethodDescriptorString(),
			false
		);
		mv.visitInvokeDynamicInsn(methodName, "()" + getDescriptor(interfaceType), bootstrapMethodHandle);
	}

	private static void generateConstructor(ClassWriter cw) {
		MethodVisitor mv = cw.visitMethod(ACC_PUBLIC, "<init>", "()V", null, null);
		mv.visitCode();
		mv.visitVarInsn(ALOAD, 0);
		mv.visitMethodInsn(INVOKESPECIAL, getInternalName(Object.class), "<init>", "()V", false);
		mv.visitInsn(RETURN);
		mv.visitMaxs(0, 0);
		mv.visitEnd();
	}

    private static MutableCallSite newCallSite(MethodType type) {
        try {
            return new MutableCallSite(lookup()
                .findStatic(ProxyRuntime.class, "notYetSet", methodType(void.class))
                .asType(type));
        } catch (NoSuchMethodException | IllegalAccessException e) {
            throw new AssertionError("Method should be accessible", e);
        }
    }

    //private static final Logger LOGGER = LogManager.getLogger(ProxyFactoryImpl.class);


}
