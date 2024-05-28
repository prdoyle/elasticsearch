package org.elasticsearch.nalbind.injector.impl;

import org.elasticsearch.nalbind.injector.ProxyFactory.ProxyInfo;
import org.elasticsearch.test.ESTestCase;
import org.junit.Before;

public class ProxyTests extends ESTestCase {
	static ProxyInfo<TestInterface> proxy;

    @Before
    public void createProxy() {
        proxy = new ProxyFactoryImpl().generateFor(TestInterface.class);
    }

	public void testUseAfterSetWorks() {
		proxy.setter().accept(new TestImplementation());
		assertEquals("Received testArg", proxy.proxyObject().testMethod("testArg"));
	}

	public void testUseBeforeSetThrows() {
		assertThrows(IllegalStateException.class, () -> proxy.proxyObject().testMethod("testArg"));
	}

	public void testSetAfterSetThrows() {
		proxy.setter().accept(new TestImplementation());
		assertThrows(IllegalStateException.class, () -> proxy.setter().accept(new TestImplementation()));
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
}
