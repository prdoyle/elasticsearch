/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0 and the Server Side Public License, v 1; you may not use this file except
 * in compliance with, at your election, the Elastic License 2.0 or the Server
 * Side Public License, v 1.
 */

package org.elasticsearch.nalbind.injector;

import java.util.function.Consumer;

import static org.elasticsearch.nalbind.injector.spi.ProxyBytecodeGenerator.Holder.PROXY_BYTECODE_GENERATOR;

public interface ProxyFactory {
    <T> ProxyInfo<T> generateFor(Class<T> interfaceType);

    record ProxyInfo<T> (
        Class<T> interfaceType,
        T proxyObject,
        Consumer<T> setter
    ){}

    ProxyFactory INSTANCE = new ProxyFactoryImpl(PROXY_BYTECODE_GENERATOR);
}
