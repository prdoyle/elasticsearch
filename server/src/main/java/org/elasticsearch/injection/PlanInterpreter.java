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
import org.elasticsearch.injection.step.CreateListProxyStep;
import org.elasticsearch.injection.step.InjectionStep;
import org.elasticsearch.injection.step.InstantiateStep;
import org.elasticsearch.injection.step.ResolveListProxyStep;
import org.elasticsearch.injection.step.RollupStep;
import org.elasticsearch.logging.LogManager;
import org.elasticsearch.logging.Logger;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Performs the actual injection operations by running the {@link InjectionStep}s.
 * <p>
 * The intent is that this logic is as simple as possible so that we don't run complex injection
 * logic alongside the user-supplied constructor logic. All the injector complexity is already
 * supposed to have happened in the planning phase. In particular, no injection-related errors
 * are supposed to be detected during execution; they should be detected during planning and validation.
 * All exceptions thrown during execution are supposed to be caused by user-supplied code.
 *
 * <p>
 * <strong>Execution model</strong>:
 * The state of the injector during injection comprises a map from classes to lists of objects.
 * Before any steps execute, the map is pre-populated by object instances added via
 * {@link Injector#addInstance(Object)  Injector.addInstance},
 * and then the steps begin to execute, reading and writing from this map.
 * Some steps create objects and add them to this map; others manipulate the map itself.
 */
final class PlanInterpreter {
    private static final Logger logger = LogManager.getLogger(PlanInterpreter.class);
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
            if (step instanceof InstantiateStep i) {
                MethodHandleSpec spec = i.spec();
                logger.trace("Instantiating {}", spec.requestedType().getSimpleName());
                addInstance(spec.requestedType(), instantiate(spec));
                ++numConstructorCalls;
            } else if (step instanceof RollupStep r) {
                logger.trace("Rolling up {} -> {}", r.subtype().getSimpleName(), r.supertype().getSimpleName());
                List<Object> subtypeInstances = getInstances(r.subtype());
                for (Object instance : subtypeInstances) {
                    addInstance(r.supertype(), instance);
                }
            } else if (step instanceof CreateListProxyStep c) {
                logger.trace("Creating list proxy for {}", c.elementType().getSimpleName());
                proxyPool.putNewListProxy(c.elementType());
            } else if (step instanceof ResolveListProxyStep r) {
                logger.trace("Resolving list proxy for {}", r.elementType().getSimpleName());
                List<Object> currentInstances = getInstances(r.elementType());
                proxyPool.resolveListProxy(r.elementType(), currentInstances);
            } else {
                assert false : "Unexpected step type: " + step.getClass().getSimpleName();
                throw new InjectionExecutionException("Unexpected step type: " + step.getClass().getSimpleName());
            }
        }
        logger.debug("Instantiated {} objects", numConstructorCalls);
    }

    /**
     * @return the single instance of the given type
     * @throws InjectionExecutionException if there is not exactly one instance
     */
    public <T> T theInstanceOf(Class<T> type) {
        List<Object> list = instances.get(type);
        if (list == null || list.isEmpty()) {
            throw new InjectionExecutionException("No object of type " + type.getSimpleName());
        }
        if (list.size() > 1) {
            throw new InjectionExecutionException("Multiple objects for " + type.getSimpleName() + ": expected exactly one");
        }
        return type.cast(list.get(0));
    }

    /**
     * @return all instances of the given type, or an empty list if none
     */
    List<Object> getInstances(Class<?> type) {
        return instances.getOrDefault(type, Collections.emptyList());
    }

    private void addInstance(Class<?> requestedType, Object instance) {
        instances.computeIfAbsent(requestedType, k -> new ArrayList<>()).add(instance);
    }

    /**
     * @throws InjectionExecutionException if the <code>MethodHandle</code> throws a checked exception.
     */
    @SuppressForbidden(
        reason = "Can't call invokeExact because we don't know the method argument types statically, "
            + "since each constructor has a different signature"
    )
    private Object instantiate(MethodHandleSpec spec) {
        Object[] args = spec.parameters().stream().map(this::parameterValue).toArray();
        try {
            return spec.methodHandle().invokeWithArguments(args);
        } catch (RuntimeException | Error e) {
            throw e;
        } catch (Throwable e) {
            throw new InjectionExecutionException("Unexpected exception while instantiating " + spec, e);
        }
    }

    private Object parameterValue(ParameterSpec parameterSpec) {
        if (parameterSpec.isList()) {
            return proxyPool.theProxyFor(parameterSpec.injectableType());
        }
        return theInstanceOf(parameterSpec.formalType());
    }

}
