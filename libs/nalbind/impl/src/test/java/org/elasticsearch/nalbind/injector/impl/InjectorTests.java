package org.elasticsearch.nalbind.injector.impl;

import org.elasticsearch.example.module1.Module1ServiceImpl;
import org.elasticsearch.example.module2.Module2ServiceImpl;
import org.elasticsearch.example.module2.api.Module2Service;
import org.elasticsearch.nalbind.api.InjectableSingleton;
import org.elasticsearch.nalbind.injector.Injector;
import org.elasticsearch.test.ESTestCase;

import java.io.IOException;
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

	public void testDetectAllSPI() throws IOException {
		for (var m: Module2Service.class.getModule().getLayer().modules()) {
			System.out.println("Module: " + m);
			for (var p: m.getDescriptor().provides()) {
				if (InjectableSingleton.class.getName().equals(p.service())) {
					System.out.println("\tProvider: " + p);
				}
			}
		}
		Iterable<java.net.URL> i = getClass().getClassLoader().getResources("module-info.class")::asIterator;
		for (var r: i) {
			System.out.println("Resource: " + r);
		}
//		var services = Module2Service.class.getClassLoader().getResources("META-INF/services/");
//		var list = new ArrayList<>();
//		services.asIterator().forEachRemaining(list::add);
//		System.out.println("Services: " + list);
	}
}
