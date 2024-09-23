/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0 and the Server Side Public License, v 1; you may not use this file except
 * in compliance with, at your election, the Elastic License 2.0 or the Server
 * Side Public License, v 1.
 */

package org.elasticsearch.nalbind.injector.spi;

import org.elasticsearch.core.internal.provider.ProviderLocator;

import java.lang.invoke.MutableCallSite;
import java.util.Set;

/**
 * A lower-level interface than <code>ProxyFactory</code>. This one encapsulates only the logic
 * that requires the asm library and must therefore live in the <code>impl</code> module.
 */
public interface ProxyBytecodeGenerator {
    <T> ProxyBytecodeInfo generateBytecodeFor(Class<T> interfaceType);

    record ProxyBytecodeInfo(
        String classInternalName,
        byte[] bytecodes,
        MutableCallSite callSite
    ) { }

    class Holder {

        private Holder() {}

        private static final String PROVIDER_NAME = "nalbind";
        private static final String PROVIDER_MODULE_NAME = "org.elasticsearch.nalbind.impl";
        private static final Set<String> MISSING_MODULES = Set.of("org.ow2.asm");

        public static final ProxyBytecodeGenerator PROXY_BYTECODE_GENERATOR = (new ProviderLocator<>(
            PROVIDER_NAME,
            ProxyBytecodeGenerator.class,
            PROVIDER_MODULE_NAME,
            MISSING_MODULES
        )).get();
    }

}
