/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0 and the Server Side Public License, v 1; you may not use this file except
 * in compliance with, at your election, the Elastic License 2.0 or the Server
 * Side Public License, v 1.
 */

package org.elasticsearch.nalbind.injector;

import java.lang.invoke.MutableCallSite;

/**
 * A lower-level interface than {@link ProxyFactory}. This one encapsulates only the logic
 * that requires the asm library and must therefore live in the <code>impl</code> module.
 */
public interface ProxyBytecodeGenerator {
    <T> ProxyBytecodeInfo generateBytecodeFor(Class<T> interfaceType);

    record ProxyBytecodeInfo(
        String classInternalName,
        byte[] bytecodes,
        MutableCallSite callSite
    ) { }

}
