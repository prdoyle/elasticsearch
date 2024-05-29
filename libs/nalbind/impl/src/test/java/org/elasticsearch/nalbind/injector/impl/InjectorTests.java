package org.elasticsearch.nalbind.injector.impl;

import org.elasticsearch.example.module1.Module1ServiceImpl;
import org.elasticsearch.example.module2.Module2ServiceImpl;
import org.elasticsearch.example.module2.api.Module2Service;
import org.elasticsearch.nalbind.injector.Injector;
import org.elasticsearch.nalbind.injector.ObjectGraph;
import org.elasticsearch.test.ESTestCase;

import java.util.List;

import static java.util.Collections.singleton;

public class InjectorTests extends ESTestCase {

	public void testBasicFunctionality() {
        ObjectGraph objectGraph = Injector.create().addClasses(List.of(
			Module1ServiceImpl.class,
			Module2ServiceImpl.class
        )).inject();
		Module2Service module2Service = objectGraph.getInstance(Module2Service.class);
		assertEquals(
			"Module1Service: Hello from Module1ServiceImpl to my 1 listeners",
			module2Service.statusReport());
	}

    public void testInstanceInjection() {
        Injector injector = Injector.create()
            .addInstance(new Module1ServiceImpl())
            .addClasses(singleton(Module2ServiceImpl.class));
        ObjectGraph objectGraph = injector.inject();
        Module2Service module2Service = objectGraph.getInstance(Module2Service.class);
        assertEquals(
            "Module1Service: Hello from Module1ServiceImpl to my 1 listeners",
            module2Service.statusReport());
    }
}
