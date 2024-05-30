/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0 and the Server Side Public License, v 1; you may not use this file except
 * in compliance with, at your election, the Elastic License 2.0 or the Server
 * Side Public License, v 1.
 */

package org.elasticsearch.nalbind.injector;

import org.elasticsearch.nalbind.injector.ProxyFactory.ProxyInfo;
import org.elasticsearch.nalbind.injector.impl.ProxyBytecodeGeneratorImpl;
import org.elasticsearch.test.ESTestCase;

public class ProxyTests extends ESTestCase {
    public void testUseAfterSetWorks() {
        var proxy = createProxy(TestInterface.class);
        proxy.setter().accept(new TestImplementation());
		assertEquals("Received testArg", proxy.proxyObject().testMethod("testArg"));
	}

	public void testUseBeforeSetThrows() {
        var proxy = createProxy(TestInterface.class);
		assertThrows(IllegalStateException.class, () -> proxy.proxyObject().testMethod("testArg"));
	}

	public void testSetAfterSetThrows() {
        var proxy = createProxy(TestInterface.class);
		proxy.setter().accept(new TestImplementation());
		assertThrows(IllegalStateException.class, () -> proxy.setter().accept(new TestImplementation()));
	}

    public void testMultiplyInheritedMethodWorks() {
        var proxy = createProxy(SofaBed.class);
        proxy.setter().accept(new SofaBedImpl());
        assertEquals(525.25, proxy.proxyObject().price(), 0.0);
        // (No need for a delta because the fraction part is a power of 2)
    }

    // Helpers

    private static <T> ProxyInfo<T> createProxy(Class<T> type) {
        return new ProxyFactoryImpl(new ProxyBytecodeGeneratorImpl()).generateFor(type);
    }

    public interface TestInterface {
		String testMethod(String arg);
	}

	public static class TestImplementation implements TestInterface {
		@Override
		public String testMethod(String arg) {
			return "Received " + arg;
		}
	}

    public interface Sofa {
        double price();
    }

    public interface Bed {
        double price();
    }

    public interface SofaBed extends Sofa, Bed {}

    public static final class SofaBedImpl implements SofaBed {

        @Override
        public double price() {
            return 525.25;
        }
    }
}
