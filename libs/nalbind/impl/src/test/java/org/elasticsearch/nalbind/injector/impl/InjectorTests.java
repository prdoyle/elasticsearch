package org.elasticsearch.nalbind.injector.impl;

import org.elasticsearch.example.module1.Module1ServiceImpl;
import org.elasticsearch.example.module2.Module2ServiceImpl;
import org.elasticsearch.example.module2.api.Module2Service;
import org.elasticsearch.nalbind.injector.Injector;
import org.elasticsearch.test.ESTestCase;

import java.util.List;

public class InjectorTests extends ESTestCase {

	public void testBasicFunctionality() {
		Injector injector = Injector.withClasses(List.of(
			// Lame. Hopefully we can auto-scan these instead of providing them explicitly.
			Module1ServiceImpl.class,
			Module2ServiceImpl.class));
		Module2Service module2Service = injector.getInstance(Module2Service.class);
		assertEquals(
			"Module1Service: Hello from Module1ServiceImpl to my 1 listeners",
			module2Service.statusReport());
	}
}
