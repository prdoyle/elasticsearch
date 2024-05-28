package org.elasticsearch.nalbind.injector.impl;

import org.elasticsearch.common.logging.LogConfigurator;
import org.elasticsearch.nalbind.injector.ProxyFactory.ProxyInfo;
import org.elasticsearch.test.ESTestCase;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

public class ProxyTests extends ESTestCase {
	static ProxyInfo<TestInterface> proxy;

	@BeforeClass
	public static void setupLogging() {
        LogConfigurator.configureESLogging();
	}

    @Before
    public void createProxy() {
        proxy = new ProxyFactoryImpl().generateFor(TestInterface.class);
    }

	@Test
	public void useAfterSet_works() {
		proxy.setter().accept(new TestImplementation());
		assertEquals("Received testArg", proxy.proxyObject().testMethod("testArg"));
	}

	@Test
	public void useBeforeSet_throws() {
		assertThrows(IllegalStateException.class, () -> proxy.proxyObject().testMethod("testArg"));
	}

	@Test
	public void setAfterSet_throws() {
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
