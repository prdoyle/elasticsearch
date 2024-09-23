/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the "Elastic License
 * 2.0", the "GNU Affero General Public License v3.0 only", and the "Server Side
 * Public License v 1"; you may not use this file except in compliance with, at
 * your election, the "Elastic License 2.0", the "GNU Affero General Public
 * License v3.0 only", or the "Server Side Public License, v 1".
 */

package org.elasticsearch.injection;

import org.elasticsearch.nalbind.injector.ProxyFactory;

import java.util.AbstractCollection;
import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

public class ProxyPool {
    final Map<Class<?>, ProxyFactory.ProxyInfo<?>> unresolvedInstanceProxies = new HashMap<>();
    final Map<Class<?>, ProxyCollection> unresolvedCollectionProxies = new HashMap<>();

    public Object getInstanceProxyIfUnresolved(Class<?> type) {
        var p = unresolvedInstanceProxies.get(type);
        if (p == null) {
            return null;
        } else {
            return p.proxyObject();
        }
    }

    public Collection<?> getCollectionProxyIfUnresolved(Class<?> entryType) {
        return unresolvedCollectionProxies.get(entryType);
    }

    public Object createProxyInstance(Class<?> t) {
        ProxyFactory.ProxyInfo<?> info = ProxyFactory.INSTANCE.generateFor(t);
        var previous = unresolvedInstanceProxies.put(t, info);
        if (previous != null) {
            throw new IllegalStateException("Proxy instance already exists for " + t.getSimpleName());
        }
        return info.proxyObject();
    }

    public <T> void resolveProxyInstance(Class<T> t, Object object) {
        @SuppressWarnings("unchecked")
        ProxyFactory.ProxyInfo<T> info = (ProxyFactory.ProxyInfo<T>) unresolvedInstanceProxies.remove(t);
        if (info == null) {
            throw new IllegalStateException("Cannot resolve nonexistent proxy instance proxy for " + t.getSimpleName());
        }
        info.setter().accept(info.interfaceType().cast(object));
    }

    public Collection<?> createProxyCollection(Class<?> entryType) {
        ProxyCollection result = new ProxyCollection();
        var previous = unresolvedCollectionProxies.put(entryType, result);
        if (previous != null) {
            throw new IllegalStateException("Proxy collection already exists for " + entryType.getSimpleName());
        }
        return result;
    }

    public void resolveProxyCollection(Class<?> entryType, List<?> instances) {
        ProxyCollection proxy = unresolvedCollectionProxies.remove(entryType);
        if (proxy == null) {
            throw new IllegalStateException("Cannot resolve nonexistent collection proxy for " + entryType.getSimpleName());
        }
        var previous = proxy.delegate.getAndSet(List.copyOf(instances));
        if (previous != null) {
            throw new IllegalStateException("Proxy already resolved for " + entryType.getSimpleName());
        }
    }

    static class ProxyCollection extends AbstractCollection<Object> {
        final AtomicReference<List<Object>> delegate = new AtomicReference<>(null);

        private List<Object> delegate() {
            List<Object> result = delegate.get();
            if (result == null) {
                throw new IllegalStateException("Cannot use injected collection during @Injected constructor");
            }
            return result;
        }

        @Override
        public Iterator<Object> iterator() {
            return delegate().iterator();
        }

        @Override
        public int size() {
            return delegate().size();
        }
    }
}
