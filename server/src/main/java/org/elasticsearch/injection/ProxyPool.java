/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the "Elastic License
 * 2.0", the "GNU Affero General Public License v3.0 only", and the "Server Side
 * Public License v 1"; you may not use this file except in compliance with, at
 * your election, the "Elastic License 2.0", the "GNU Affero General Public
 * License v3.0 only", or the "Server Side Public License, v 1".
 */

package org.elasticsearch.injection;

import org.elasticsearch.injection.api.UnresolvedProxyException;
import org.elasticsearch.logging.LogManager;
import org.elasticsearch.logging.Logger;

import java.util.AbstractList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Manages proxy {@link List} objects that stand in for not-yet-populated lists of injectable types.
 * <p>
 * A proxy list is an {@link AbstractList} backed by an {@link AtomicReference}.
 * Before resolution, any access to the list throws {@link UnresolvedProxyException}.
 * After resolution, the list delegates to the actual contents.
 */
final class ProxyPool {
    private static final Logger logger = LogManager.getLogger(ProxyPool.class);
    private final Map<Class<?>, ListProxy<?>> proxies = new LinkedHashMap<>();

    /**
     * Creates a new proxy list for the given element type.
     *
     * @return the proxy list
     */
    <T> List<T> putNewListProxy(Class<T> elementType) {
        @SuppressWarnings("unchecked")
        ListProxy<T> existing = (ListProxy<T>) proxies.get(elementType);
        if (existing != null) {
            throw new InjectionExecutionException("List proxy for " + elementType.getSimpleName() + " already exists");
        }
        ListProxy<T> proxy = new ListProxy<>(elementType);
        proxies.put(elementType, proxy);
        logger.trace("Created list proxy for {}", elementType.getSimpleName());
        return proxy;
    }

    /**
     * Resolves the proxy list for the given element type, populating it with the actual instances.
     */
    void resolveListProxy(Class<?> elementType, List<Object> instances) {
        @SuppressWarnings("unchecked")
        ListProxy<Object> proxy = (ListProxy<Object>) proxies.get(elementType);
        if (proxy == null) {
            throw new InjectionExecutionException("No list proxy for " + elementType.getSimpleName());
        }
        proxy.resolve(List.copyOf(instances));
        logger.trace("Resolved list proxy for {} with {} instances", elementType.getSimpleName(), instances.size());
    }

    /**
     * @return the proxy list for the given element type
     */
    List<?> theProxyFor(Class<?> elementType) {
        ListProxy<?> proxy = proxies.get(elementType);
        if (proxy == null) {
            throw new InjectionExecutionException("No list proxy for " + elementType.getSimpleName());
        }
        return proxy;
    }

    /**
     * @return the set of element types that have proxies that have not yet been resolved
     */
    Set<Class<?>> unresolvedTypes() {
        Set<Class<?>> result = new java.util.LinkedHashSet<>();
        proxies.forEach((type, proxy) -> {
            if (proxy.delegate.get() == null) {
                result.add(type);
            }
        });
        return result;
    }

    private static final class ListProxy<T> extends AbstractList<T> {
        private final Class<T> elementType;
        final AtomicReference<List<T>> delegate = new AtomicReference<>();

        ListProxy(Class<T> elementType) {
            this.elementType = elementType;
        }

        void resolve(List<T> actual) {
            if (delegate.compareAndSet(null, actual) == false) {
                throw new InjectionExecutionException("List proxy for " + elementType.getSimpleName() + " already resolved");
            }
        }

        @Override
        public T get(int index) {
            return resolved().get(index);
        }

        @Override
        public int size() {
            return resolved().size();
        }

        private List<T> resolved() {
            List<T> result = delegate.get();
            if (result == null) {
                throw new UnresolvedProxyException(
                    "List<" + elementType.getSimpleName() + "> proxy has not been resolved yet. "
                        + "This typically means a constructor is trying to use a List parameter during construction. "
                        + "Use @Actual to request a non-proxy list if you need immediate access."
                );
            }
            return result;
        }
    }
}
