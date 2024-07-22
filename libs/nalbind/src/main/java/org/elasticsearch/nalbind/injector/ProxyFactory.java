/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0 and the Server Side Public License, v 1; you may not use this file except
 * in compliance with, at your election, the Elastic License 2.0 or the Server
 * Side Public License, v 1.
 */

package org.elasticsearch.nalbind.injector;

import org.elasticsearch.nalbind.api.UnresolvedProxyException;

import java.util.AbstractList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

class ProxyFactory {
    <T> ProxyInfo<List<T>> generateListProxyFor(Class<T> elementType) {
        // TODO: A more performant proxy
        AtomicReference<List<T>> delegate = new AtomicReference<>(null);
        List<T> proxy = new AbstractList<>() {
            @Override
            public T get(int index) {
                return delegate().get(index);
            }

            @Override
            public int size() {
                return delegate().size();
            }

            private List<T> delegate() {
                List<T> result = delegate.get();
                if (result == null) {
                    throw new UnresolvedProxyException(
                        "Missing @Actual annotation; cannot call method on injected List during object construction. " +
                            "Element type is " + elementType);
                } else {
                    return result;
                }
            }
        };
        return new ProxyInfo<>(listClass(), proxy, delegate::set);
    }

    record ProxyInfo<T>(Class<T> interfaceType, T proxyObject, Consumer<T> setter) {}

    @SuppressWarnings({"rawtypes", "unchecked"})
    private <T> Class<List<T>> listClass() {
        return (Class<List<T>>)(Class)List.class;
    }
}
