package org.elasticsearch.nalbind.injector.impl;

import org.elasticsearch.nalbind.injector.ProxyBytecodeGenerator;
import org.elasticsearch.nalbind.injector.ProxyFactoryImpl;
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
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import static java.lang.invoke.MethodHandles.lookup;
import static java.lang.invoke.MethodType.methodType;
import static java.lang.reflect.Modifier.isStatic;
import static java.util.Objects.requireNonNull;
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
	private static final Map<String, MutableCallSite> callSites = new ConcurrentHashMap<>();
	private static final AtomicInteger numCallSites = new AtomicInteger(0);
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
        generateDelegatingMethods(interfaceType, interfacesAlreadySeen, methodName, cw);

        cw.visitEnd();
        byte[] bytecodes = cw.toByteArray();
        return new ProxyBytecodeInfo(classInternalName, bytecodes, callSite);
    }

    private static <T> void generateDelegatingMethods(
        Class<T> interfaceType,
        HashSet<Class<?>> alreadySeen,
        String methodName,
        ClassWriter cw
    ) {
		if (alreadySeen.add(interfaceType)) {
			//LOGGER.trace("generateDelegatingMethods for {}", interfaceType);
		} else {
			return;
		}

		for (Class<?> s: interfaceType.getInterfaces()) {
			generateDelegatingMethods(s, alreadySeen, methodName, cw);
		}

		for (Method m: interfaceType.getDeclaredMethods()) {
			generateDelegatingMethod(m, interfaceType, methodName, cw);
		}
	}

	private static <T> void generateDelegatingMethod(Method m, Class<T> targetType, String targetMethodName, ClassWriter cw) {
		//LOGGER.trace("generateDelegatingMethod {}", m);

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

			getInternalName(ProxyBytecodeGeneratorImpl.class),
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
                .findStatic(ProxyBytecodeGeneratorImpl.class, "notYetSet", methodType(void.class))
                .asType(type));
        } catch (NoSuchMethodException | IllegalAccessException e) {
            throw new AssertionError("Method should be accessible", e);
        }
    }

    public static void notYetSet() {
        throw new IllegalStateException(
            "Cannot invoke method on object that is not fully constructed. " +
                "Use the @Now annotation on your method's parameter to indicate that you need to call a method on it");
    }

    @SuppressWarnings("unused")
	public static CallSite bootstrap(MethodHandles.Lookup caller, String name, MethodType type) {
		return requireNonNull(callSites.remove(name), ()->"CallSite not found: \"" + name + "\"");
	}

    //private static final Logger LOGGER = LogManager.getLogger(ProxyFactoryImpl.class);


}
