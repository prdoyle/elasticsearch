/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0 and the Server Side Public License, v 1; you may not use this file except
 * in compliance with, at your election, the Elastic License 2.0 or the Server
 * Side Public License, v 1.
 */

package org.elasticsearch.nalbind.injector;

import org.elasticsearch.nalbind.injector.spi.ProxyBytecodeGenerator;

import java.lang.invoke.MutableCallSite;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.util.concurrent.atomic.AtomicBoolean;

import static java.lang.invoke.MethodHandles.constant;
import static java.lang.invoke.MutableCallSite.syncAll;

class ProxyFactoryImpl implements ProxyFactory {
//	private static final Map<String, MutableCallSite> callSites = new ConcurrentHashMap<>();
//	private static final AtomicInteger numCallSites = new AtomicInteger(0);
    private final ProxyBytecodeGenerator bytecode;

    ProxyFactoryImpl(ProxyBytecodeGenerator bytecode) {
        this.bytecode = bytecode;
    }

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

        var proxyBytecodeInfo = bytecode.generateBytecodeFor(interfaceType);

        T proxy = interfaceType.cast(instantiate(loadProxyClass(proxyBytecodeInfo.bytecodes(), proxyBytecodeInfo.classInternalName())));
        AtomicBoolean alreadySet = new AtomicBoolean(false);
        return new ProxyInfo<>(
            interfaceType,
            proxy,
            (T newValue) -> {
                if (alreadySet.getAndSet(true)) {
                    throw new IllegalStateException("Already set!");
                } else {
                    proxyBytecodeInfo.callSite().setTarget(constant(interfaceType, newValue));
                    syncAll(new MutableCallSite[]{proxyBytecodeInfo.callSite()});
                }
            }
        );
    }


    private static Constructor<?> loadProxyClass(byte[] byteArray, String classInternalName) {
        return new CustomClassLoader(ProxyFactoryImpl.class.getClassLoader())
                .loadThemBytes(classInternalName.replace('/', '.'), byteArray)
                .getConstructors()[0];
    }

    private static Object instantiate(Constructor<?> ctor) {
        try {
            return ctor.newInstance();
        } catch (InstantiationException | IllegalAccessException | VerifyError | InvocationTargetException e) {
            throw new AssertionError("Should be able to instantiate the generated class", e);
        }
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
