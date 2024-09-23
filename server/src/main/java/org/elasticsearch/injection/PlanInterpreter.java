/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the "Elastic License
 * 2.0", the "GNU Affero General Public License v3.0 only", and the "Server Side
 * Public License v 1"; you may not use this file except in compliance with, at
 * your election, the "Elastic License 2.0", the "GNU Affero General Public
 * License v3.0 only", or the "Server Side Public License, v 1".
 */

package org.elasticsearch.injection;

import org.elasticsearch.core.SuppressForbidden;
import org.elasticsearch.injection.spec.MethodHandleSpec;
import org.elasticsearch.injection.spec.ParameterSpec;
import org.elasticsearch.injection.step.CreateCollectionProxyStep;
import org.elasticsearch.injection.step.CreateInstanceProxyStep;
import org.elasticsearch.injection.step.InjectionStep;
import org.elasticsearch.injection.step.InstantiateStep;
import org.elasticsearch.injection.step.ResolveCollectionProxyStep;
import org.elasticsearch.injection.step.ResolveInstanceProxyStep;
import org.elasticsearch.injection.step.RollupStep;
import org.elasticsearch.logging.LogManager;
import org.elasticsearch.logging.Logger;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static java.util.Collections.unmodifiableList;

/**
 * Performs the actual injection operations by running the {@link InjectionStep}s.
 * <p>
 * The intent is that this logic is as simple as possible so that we don't run complex injection
 * logic alongside the user-supplied constructor logic. All the injector complexity is already
 * supposed to have happened in the planning phase. In particular, no injection-related errors
 * are supposed to be detected during execution; they should be detected during planning and validation.
 * All exceptions thrown during execution are supposed to be caused by user-supplied code.
 * <p>
 * User interactions with injection is supposed to be declarative; but this is the class
 * where the rubber meets the road. This class is emphatically imperative, with a mutable
 * state that changes over time, as directed by the {@link InjectionStep steps} in the plan.
 * This class takes one for the team, being imperative and stateful so the rest of the
 * application initialization logic all around the codebase doesn't need to be.
 *
 * <p>
 * <strong>Execution model</strong>:
 * The state of the injector during injection comprises a map from classes to objects.
 * Before any steps execute, the map is pre-populated by object instances added via
 * {@link Injector#addInstance(Object)  Injector.addInstance},
 * and then the steps begin to execute, reading and writing from this map.
 * Some steps create objects and add them to this map; others manipulate the map itself.
 *
 * <p>
 * The injector also uses a given {@link ProxyPool}, which it can reference to get or
 * add {@link org.elasticsearch.injection.ProxyPool.ProxyCollection ProxyCollection}s.
 *
 */
final class PlanInterpreter {
    private static final Logger logger = LogManager.getLogger(PlanInterpreter.class);

    /**
     * The objects currently associated with each injectable class.
     */
    private final Map<Class<?>, List<Object>> instances = new LinkedHashMap<>();

    private final ProxyPool proxyPool;

    PlanInterpreter(Map<Class<?>, Object> existingInstances, ProxyPool proxyPool) {
        this.proxyPool = proxyPool;
        existingInstances.forEach(this::addInstance);
    }

    /**
     * Main entry point. Contains the implementation logic for each {@link InjectionStep}.
     */
    void executePlan(List<InjectionStep> plan) {
        int numConstructorCalls = 0;
        for (InjectionStep step : plan) {
            switch (step) {
                case InstantiateStep i -> {
                    MethodHandleSpec spec = i.spec();
                    logger.trace("Instantiating {}", spec.requestedType().getSimpleName());
                    addInstance(spec.requestedType(), instantiate(spec));
                    ++numConstructorCalls;
                }
                case RollupStep r -> {
                    logger.trace("Rolling up {} into {}", r.subtype().getSimpleName(), r.supertype().getSimpleName());
                    addInstances(r.supertype(), instancesOf(r.subtype()));
                }
                case CreateInstanceProxyStep(var t) -> {
                    logger.trace("Creating proxy instance for {}", t.getSimpleName());
                    proxyPool.createProxyInstance(t);
                }
                case ResolveInstanceProxyStep(var t) -> {
                    logger.trace("Resolving proxy instance for {}", t.getSimpleName());
                    proxyPool.resolveProxyInstance(t, theActualInstanceOf(t));
                }
                case CreateCollectionProxyStep(var t) -> {
                    logger.trace("Creating proxy collection for {}", t.getSimpleName());
                    proxyPool.createProxyCollection(t);
                }
                case ResolveCollectionProxyStep(var t) -> {
                    logger.trace("Resolving proxy collection for {}", t.getSimpleName());
                    proxyPool.resolveProxyCollection(t, instancesOf(t));
                }
            }
        }
        logger.debug("Instantiated {} objects", numConstructorCalls);
    }

    /**
     * Like {@link #theInstanceOf} except any proxy is ignored.
     */
    private <T> T theActualInstanceOf(Class<T> type) {
        List<Object> candidates = this.instances.getOrDefault(type, List.of());
        if (candidates.isEmpty()) {
            throw new IllegalStateException("No object of type " + type.getSimpleName());
        } else if (candidates.size() > 1) {
            throw new IllegalStateException("Multiple objects type " + type.getSimpleName());
        }
        return type.cast(candidates.getFirst());
    }

    /**
     * @return the list element corresponding to {@link #instancesOf instancesOf(type).getFirst()},
     * assuming that instances.get(type) has exactly one element.
     * @throws IllegalStateException if instances.get(type) does not have exactly one element
     */
    public <T> T theInstanceOf(Class<T> type) {
        List<Object> candidates = instancesOf(type);
        if (candidates.isEmpty()) {
            throw new IllegalStateException("No object of type " + type.getSimpleName());
        } else if (candidates.size() > 1) {
            throw new IllegalStateException("Multiple objects type " + type.getSimpleName());
        }
        return type.cast(candidates.getFirst());
    }

    /**
     * If there's an unresolved proxy for the given type, the resulting list
     * will include that.
     */
    <T> List<Object> instancesOf(Class<T> type) {
        List<Object> instances = this.instances.getOrDefault(type, List.of());
        List<Object> result = new ArrayList<>(1 + instances.size());
        result.addAll(instances);
        var instanceProxy = proxyPool.getInstanceProxyIfUnresolved(type);
        if (instanceProxy != null) {
            result.add(instanceProxy);
        }
        return unmodifiableList(result);
    }

    private void addInstance(Class<?> requestedType, Object instance) {
        assert requestedType.isInstance(instance);
        instances.computeIfAbsent(requestedType, t -> new ArrayList<>()).add(instance);
    }

    private void addInstances(Class<?> requestedType, Collection<?> newInstances) {
        assert newInstances.stream().allMatch(requestedType::isInstance);
        instances.computeIfAbsent(requestedType, t -> new ArrayList<>()).addAll(newInstances);
    }

    /**
     * @throws IllegalStateException if the <code>MethodHandle</code> throws.
     */
    @SuppressForbidden(
        reason = "Can't call invokeExact because we don't know the method argument types statically, "
            + "since each constructor has a different signature"
    )
    private Object instantiate(MethodHandleSpec spec) {
        Object[] args = spec.parameters().stream().map(this::parameterValue).toArray();
        try {
            return spec.methodHandle().invokeWithArguments(args);
        } catch (Throwable e) {
            throw new IllegalStateException("Unexpected exception while instantiating {}" + spec, e);
        }
    }

    private Object parameterValue(ParameterSpec parameterSpec) {
        return theInstanceOf(parameterSpec.formalType());
    }

}
