package org.elasticsearch.nalbind.injector.impl;

import org.elasticsearch.nalbind.injector.ProxyFactory;

import java.lang.invoke.CallSite;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.invoke.MutableCallSite;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.security.AccessController;
import java.security.PrivilegedAction;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static java.lang.invoke.MethodHandles.constant;
import static java.lang.invoke.MethodHandles.lookup;
import static java.lang.invoke.MethodType.methodType;
import static java.lang.invoke.MutableCallSite.syncAll;
import static java.util.Objects.requireNonNull;
import static org.elasticsearch.nalbind.injector.impl.ProxyBytecodeGeneratorImpl.generateBytecodeFor;
import static org.objectweb.asm.Type.getInternalName;

public class ProxyFactoryImpl implements ProxyFactory {
	private static final Map<String, MutableCallSite> callSites = new ConcurrentHashMap<>();
	private static final AtomicInteger numCallSites = new AtomicInteger(0);
    private static final String PACKAGE_INTERNAL_NAME;

    static {
        String classInternalName = getInternalName(ProxyFactoryImpl.class);
        PACKAGE_INTERNAL_NAME = classInternalName.substring(0, classInternalName.lastIndexOf('/'));
    }

    public ProxyFactoryImpl(){}

	/**
	 * The proxies we generate are optimized for run-time performance over generation efficiency.
	 * One result of this is that every proxy object requires generating and loading its on class,
	 * so they are expensive to create.
	 * The caller of this method should make an effort to reuse the resulting objects as much as possible.
	 */
    public <T> ProxyInfo<T> generateFor(Class<T> interfaceType) {
        if (interfaceType.isInterface() == false) {
            throw new IllegalArgumentException("Only interfaces can be proxied; cannot proxy " + interfaceType);
        }

        int callSiteNum = numCallSites.incrementAndGet();
        String methodName = "callSite_" + callSiteNum;
        MutableCallSite callSite = newCallSite(MethodType.methodType(interfaceType));
        callSites.put(methodName, callSite);

        ProxyBytecodeGeneratorImpl.ProxyBytecodeInfo proxyBytecodeInfo = generateBytecodeFor(interfaceType, methodName);

        T proxy = interfaceType.cast(instantiate(loadProxyClass(proxyBytecodeInfo.bytecodes(), proxyBytecodeInfo.classInternalName())));
        AtomicBoolean alreadySet = new AtomicBoolean(false);
        return new ProxyInfo<>(
            interfaceType,
            proxy,
            (T newValue) -> {
                if (alreadySet.getAndSet(true)) {
                    throw new IllegalStateException("Already set!");
                } else {
                    callSite.setTarget(constant(interfaceType, newValue));
                    syncAll(new MutableCallSite[]{callSite});
                }
            }
        );
    }


    private static Constructor<?> loadProxyClass(byte[] byteArray, String classInternalName) {
        return AccessController.doPrivileged((PrivilegedAction<Constructor<?>>) () -> new CustomClassLoader(ProxyBytecodeGeneratorImpl.class.getClassLoader())
            .loadThemBytes(classInternalName.replace('/', '.'), byteArray)
            .getConstructors()[0]);
    }

    private static Object instantiate(Constructor<?> ctor) {
        return AccessController.doPrivileged((PrivilegedAction<Object>) () -> {
            try {
                return ctor.newInstance();
            } catch (InstantiationException | IllegalAccessException | VerifyError | InvocationTargetException e) {
                throw new AssertionError("Should be able to instantiate the generated class", e);
            }
        });
	}

	private static MutableCallSite newCallSite(MethodType type) {
		try {
			return new MutableCallSite(lookup()
				.findStatic(ProxyFactoryImpl.class, "notYetSet", methodType(void.class))
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

	private static final class CustomClassLoader extends ClassLoader {
		CustomClassLoader(ClassLoader parentClassLoader) {
			super(parentClassLoader);
		}

		public Class<?> loadThemBytes(String dottyName, byte[] b) {
			return defineClass(dottyName, b, 0, b.length);
		}
	}

    //private static final Logger LOGGER = LogManager.getLogger(ProxyFactoryImpl.class);
}
