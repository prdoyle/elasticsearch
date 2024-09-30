/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the "Elastic License
 * 2.0", the "GNU Affero General Public License v3.0 only", and the "Server Side
 * Public License v 1"; you may not use this file except in compliance with, at
 * your election, the "Elastic License 2.0", the "GNU Affero General Public
 * License v3.0 only", or the "Server Side Public License, v 1".
 */

package org.elasticsearch.entitlement.agent;

import org.elasticsearch.entitlement.checks.EntitlementChecks;
import org.elasticsearch.entitlement.instrumentation.Instrumenter;
import org.elasticsearch.entitlement.instrumentation.MethodKey;
import org.elasticsearch.entitlement.runtime.checks.EntitlementChecksImpl;
import org.elasticsearch.entitlement.config.SystemMethods;
import org.elasticsearch.entitlement.trampoline.EntitlementTrampoline;

import java.io.File;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.jar.JarFile;

import static java.util.stream.Collectors.toSet;

public class EntitlementAgent {

    public static void premain(String agentArgs, Instrumentation inst) throws Exception {
        // Add the runtime library (the one with the entitlement checks) to the bootstrap classpath
//        File jar = Paths.get(EntitlementChecks.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toFile();
        System.out.println("!!! premain has started with classpath: " + System.getProperty("java.class.path"));
        File jar = new File("/Users/prdoyle/IdeaProjects/try2-elasticsearch/distribution/tools/entitlement-trampoline/build/distributions/entitlement-trampoline-9.0.0-SNAPSHOT.jar");
        System.out.println("THE JAR IS " + jar);
        inst.appendToBootstrapClassLoaderSearch(new JarFile(jar));
        Class.forName("org.elasticsearch.entitlement.trampoline.EntitlementTrampoline", true, System.class.getClassLoader());
        Class<?> bootVersion = Class.forName("org.elasticsearch.entitlement.checks.EntitlementChecks", true, System.class.getClassLoader());
        Class<?> appVersion = Class.forName("org.elasticsearch.entitlement.checks.EntitlementChecks", true, EntitlementAgent.class.getClassLoader());
        Class<?> codeVersion = EntitlementChecks.class;
        System.out.println("Classes: " + System.identityHashCode(bootVersion) + " " + System.identityHashCode(appVersion) + " " + System.identityHashCode(codeVersion));

        // Now we can mention EntitlementChecks. If we do so before adjusting the bootstrap class loader, this won't work!
        EntitlementTrampoline.setInstance(new EntitlementChecksImpl());
//        System.out.println("Starting premain");
//        EntitlementChecks.getInstance().setAgentBooted();

        // Hardcoded config for now
        Method targetMethod = System.class.getDeclaredMethod("exit", int.class);
        Method instrumentationMethod = SystemMethods.class.getDeclaredMethod("exit", Class.class, System.class, int.class);
        var methodMap = Map.of(MethodKey.forTargetMethod(targetMethod), instrumentationMethod);
        var classesToInstrument = List.of(System.class);

        inst.addTransformer(
            new Transformer(new Instrumenter("", methodMap), classesToInstrument.stream().map(EntitlementAgent::internalName).collect(toSet())),
            true
        );
        // System.out.println("Starting retransformClasses");
        inst.retransformClasses(classesToInstrument.toArray(new Class<?>[0]));
        // System.out.println("Finished initialization");
    }

    private static String internalName(Class<?> c) {
        return c.getName().replace('.', '/');
    }

    // private static final Logger LOGGER = LogManager.getLogger(EntitlementAgent.class);
}
