/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0 and the Server Side Public License, v 1; you may not use this file except
 * in compliance with, at your election, the Elastic License 2.0 or the Server
 * Side Public License, v 1.
 */

package org.elasticsearch.nalbind.injector;

import org.elasticsearch.logging.LogManager;
import org.elasticsearch.logging.Logger;
import org.elasticsearch.nalbind.api.InjectionConfigurationException;
import org.elasticsearch.nalbind.api.UnresolvedProxyException;
import org.elasticsearch.nalbind.injector.spec.MethodHandleSpec;
import org.elasticsearch.nalbind.injector.spec.ParameterSpec;
import org.elasticsearch.nalbind.injector.step.InjectionStep;
import org.elasticsearch.nalbind.injector.step.InstantiateStep;
import org.elasticsearch.nalbind.injector.step.ListProxyCreateStep;
import org.elasticsearch.nalbind.injector.step.ListProxyResolveStep;
import org.elasticsearch.nalbind.injector.step.RollUpStep;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static java.util.Collections.emptyList;
import static java.util.Objects.requireNonNull;
import static org.elasticsearch.nalbind.injector.spec.InjectionModifiers.LIST;

/**
 * Performs the actual injection operations by running the {@link InjectionStep}s.
 * <p>
 * <strong>Execution model</strong>:
 * The state of the injector during injection comprises a map from classes to lists of objects.
 * Before any steps execute, the map is pre-populated by object instances added via
 * {@link Injector#addInstance(Class, Object) Injector.addInstance},
 * and then the steps begin to execute, reading and writing from this map.
 * Some steps create objects and add them to this map; others manipulate the map itself.
 * In addition to the map of instances, there is also a map of {@link List} proxy objects.
 * Some steps create list proxies; others resolve the proxies by populating them with all the objects associated with a given class.
 */
class PlanInterpreter {
    private final Map<Class<?>, List<Object>> instances = new LinkedHashMap<>();
    private final Map<Class<?>, List<?>> proxyInstances = new LinkedHashMap<>();
    private final Map<Class<?>, ProxyFactory.ProxyInfo<? extends List<?>>> unresolvedListProxies = new LinkedHashMap<>();
    private final ProxyFactory proxyFactory = new ProxyFactory();

    PlanInterpreter(Map<Class<?>, Object> existingInstances) {
        existingInstances.forEach(this::addInstance);
    }

    /**
     * Main entry point
     */
    void doInjection(List<InjectionStep> plan) {
        validatePlan(plan);
        executePlan(plan);
        resolveAllRemainingProxies();
        // Evolution note: when there are multiple rounds of injection,
        // we'll want to leave the proxies unresolved until the very end
    }

    private void validatePlan(List<InjectionStep> plan) {
        // TODO: we'll want to check certain rules.
        // One that comes to mind is that we should not create a new instance of any type T
        // if a list proxy for any supertype of T has already been resolved.
        // However, at the time this comment was written, that was impossible, so there's nothing to check.
    }

    void resolveAllRemainingProxies() {
        LOGGER.debug("Resolving {} remaining list proxies", unresolvedListProxies.size());
        unresolvedListProxies.forEach(this::resolveProxy);
        unresolvedListProxies.clear();
    }

    private <T extends List<?>> void resolveProxy(Class<?> c, ProxyFactory.ProxyInfo<T> p) {
        LOGGER.trace("- Resolve list proxy for {}", c.getSimpleName());
        p.setter().accept(p.interfaceType().cast(instances.getOrDefault(c, emptyList())));
    }

    /**
     * @return the list element corresponding to instances.get(type).get(0),
     * assuming that instances.get(type) has exactly one element.
     * @throws IllegalStateException if instances.get(type) does not have exactly one element
     */
    <T> T theOnlyInstance(Class<T> type) {
        List<Object> candidates = instances.getOrDefault(type, emptyList());
        if (candidates.size() == 1) {
            return type.cast(candidates.get(0));
        }

        throw new InjectionConfigurationException(
            "No unique object of type "
                + type.getSimpleName()
                + ": "
                + candidates.stream().map(x -> x.getClass().getSimpleName()).toList()
        );
    }

    private void addInstance(Class<?> requestedType, Object instance) {
        instances.computeIfAbsent(requestedType, __ -> new ArrayList<>()).add(requestedType.cast(instance));
    }

    private void addInstances(Class<?> requestedType, Collection<?> c) {
        instances.computeIfAbsent(requestedType, __ -> new ArrayList<>()).addAll(c);
    }

    /**
     * The implementation logic for the {@link InjectionStep}s.
     */
    private void executePlan(List<InjectionStep> plan) {
        AtomicInteger numConstructorCalls = new AtomicInteger(0);
        plan.forEach(step -> {
            if (step instanceof InstantiateStep i) {
                MethodHandleSpec spec = i.spec();
                LOGGER.trace("Instantiating {}", spec.requestedType().getSimpleName());
                addInstance(spec.requestedType(), instantiate(spec));
                numConstructorCalls.incrementAndGet();
            } else if (step instanceof RollUpStep r) {
                var requestedType = r.requestedType();
                var subtype = r.subtype();
                LOGGER.trace("Rolling up {} into {}", subtype.getSimpleName(), requestedType.getSimpleName());
                addInstances(requestedType, instances.getOrDefault(subtype, emptyList()));
            } else if (step instanceof ListProxyCreateStep s) {
                if (putNewListProxy(s.elementType()) != null) {
                    throw new IllegalStateException("Two proxies for " + s.elementType());
                }
            } else if (step instanceof ListProxyResolveStep s) {
                var proxy = unresolvedListProxies.remove(s.elementType());
                resolveProxy(s.elementType(), requireNonNull(proxy));
            } else {
                // TODO: switch patterns would make this unnecessary
                throw new AssertionError("Unexpected step type: " + step.getClass().getSimpleName());
            }
        });
        LOGGER.debug("Instantiated {} objects", numConstructorCalls.get());
    }

    private Object instantiate(MethodHandleSpec spec) {
        Object[] args = spec.parameters().stream().map(this::parameterValue).toArray();
        try {
            return spec.methodHandle().invokeWithArguments(args);
        } catch (UnresolvedProxyException e) {
            // This exception is descriptive enough already. Catching it and wrapping it here
            // only makes the stack trace a little more complex for no benefit.
            throw e;
        } catch (Throwable e) {
            throw new IllegalStateException("Unexpected exception while instantiating {}" + spec, e);
        }
    }

    private Object parameterValue(ParameterSpec parameterSpec) {
        if (parameterSpec.modifiers().contains(LIST)) {
            return proxyInstances.get(parameterSpec.injectableType());
        } else {
            return theOnlyInstance(parameterSpec.formalType());
        }
    }

    private <T> List<T> putNewListProxy(Class<T> elementType) {
        LOGGER.trace("Creating list proxy for {}", elementType.getSimpleName());
        ProxyFactory.ProxyInfo<List<T>> proxyInfo = proxyFactory.generateListProxyFor(elementType);
        unresolvedListProxies.put(elementType, proxyInfo);
        return proxyInfo.interfaceType().cast(proxyInstances.put(elementType, proxyInfo.proxyObject()));
    }

    private static final Logger LOGGER = LogManager.getLogger(PlanInterpreter.class);
}
