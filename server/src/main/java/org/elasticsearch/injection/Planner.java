/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the "Elastic License
 * 2.0", the "GNU Affero General Public License v3.0 only", and the "Server Side
 * Public License v 1"; you may not use this file except in compliance with, at
 * your election, the "Elastic License 2.0", the "GNU Affero General Public
 * License v3.0 only", or the "Server Side Public License, v 1".
 */

package org.elasticsearch.injection;

import org.elasticsearch.injection.api.CyclicDependencyException;
import org.elasticsearch.injection.api.InjectionConfigurationException;
import org.elasticsearch.injection.spec.AmbiguousSpec;
import org.elasticsearch.injection.spec.ExistingInstanceSpec;
import org.elasticsearch.injection.spec.InjectionSpec;
import org.elasticsearch.injection.spec.MethodHandleSpec;
import org.elasticsearch.injection.spec.ParameterSpec;
import org.elasticsearch.injection.spec.SubtypeSpec;
import org.elasticsearch.injection.step.CreateInstanceProxyStep;
import org.elasticsearch.injection.step.CreateListProxyStep;
import org.elasticsearch.injection.step.InjectionStep;
import org.elasticsearch.injection.step.InstantiateStep;
import org.elasticsearch.injection.step.ResolveInstanceProxyStep;
import org.elasticsearch.injection.step.ResolveListProxyStep;
import org.elasticsearch.injection.step.RollupStep;
import org.elasticsearch.logging.LogManager;
import org.elasticsearch.logging.Logger;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

import static java.util.Collections.unmodifiableMap;
import static java.util.Collections.unmodifiableSet;

/**
 * <em>Evolution note</em>: the intent is to plan one domain/subsystem at a time.
 * <p>
 * <em>Evolution note</em>: which parameters are proxied is currently determined by
 * {@link ParameterSpec}. In the future, this could be configurable per-Planner
 * to allow different policies.
 */
final class Planner {
    private static final Logger logger = LogManager.getLogger(Planner.class);

    final List<InjectionStep> plan;
    final Map<Class<?>, InjectionSpec> specsByClass;
    final Set<Class<?>> requiredTypes; // The injector's job is to ensure there is an instance of these; this is like the "root set"
    final Set<Class<?>> allParameterTypes; // All the injectable types in all dependencies (recursively) of all required types
    final Set<InjectionSpec> startedPlanning;
    final Set<InjectionSpec> finishedPlanning;
    final Set<Class<?>> alreadyListProxied;
    final Set<Class<?>> alreadyInstanceProxied;
    final List<String> dependencyPath; // For cycle reporting

    /**
     * @param specsByClass an {@link InjectionSpec} indicating how each class should be injected
     * @param requiredTypes the classes of which we need instances
     * @param allParameterTypes the classes that appear as the type of any parameter of any constructor we might call
     */
    Planner(Map<Class<?>, InjectionSpec> specsByClass, Set<Class<?>> requiredTypes, Set<Class<?>> allParameterTypes) {
        this.requiredTypes = requiredTypes;
        this.plan = new ArrayList<>();
        this.specsByClass = unmodifiableMap(specsByClass);
        this.allParameterTypes = unmodifiableSet(allParameterTypes);
        this.startedPlanning = new HashSet<>();
        this.finishedPlanning = new HashSet<>();
        this.alreadyListProxied = new HashSet<>();
        this.alreadyInstanceProxied = new HashSet<>();
        this.dependencyPath = new ArrayList<>();
    }

    /**
     * Intended to be called once.
     * <p>
     * Note that not all proxies are resolved once this plan has been executed.
     * <p>
     *
     * <em>Evolution note</em>: in a world with multiple domains/subsystems,
     * it will become necessary to defer proxy resolution until after other plans
     * have been executed, because they could create additional objects that ought
     * to be included in the proxies created by this plan.
     *
     * @return the {@link InjectionStep} objects listed in execution order.
     */
    List<InjectionStep> injectionPlan() {
        for (Class<?> c : requiredTypes) {
            planForClass(c, 0);
        }
        planProxyResolution();
        return plan;
    }

    /**
     * Recursive procedure that determines what effect <code>requestedClass</code>
     * should have on the plan under construction.
     *
     * @param depth is used just for indenting the logs
     */
    private void planForClass(Class<?> requestedClass, int depth) {
        InjectionSpec spec = specsByClass.get(requestedClass);
        if (spec == null) {
            throw new InjectionConfigurationException("Cannot instantiate " + requestedClass + ": no specification provided");
        }
        planForSpec(spec, depth);
    }

    private void planForSpec(InjectionSpec spec, int depth) {
        if (finishedPlanning.contains(spec)) {
            logger.trace("{}Already planned {}", indent(depth), spec);
            return;
        }

        logger.trace("{}Planning for {}", indent(depth), spec);
        if (startedPlanning.add(spec) == false) {
            List<String> cycle = new ArrayList<>(dependencyPath);
            cycle.add(spec.requestedType().getSimpleName());
            throw new CyclicDependencyException("Cyclic dependency involving " + spec.requestedType().getSimpleName(), cycle);
        }
        dependencyPath.add(spec.requestedType().getSimpleName());

        try {
            if (spec instanceof MethodHandleSpec m) {
                planForMethodHandleSpec(m, depth);
            } else if (spec instanceof ExistingInstanceSpec e) {
                logger.trace("{}- Plan {}", indent(depth), e);
                // Nothing to do. The injector will already have the required object.
            } else if (spec instanceof SubtypeSpec s) {
                planForSubtypeSpec(s, depth);
            } else if (spec instanceof AmbiguousSpec a) {
                planForAmbiguousSpec(a, depth);
            } else {
                throw new AssertionError("Unexpected injection spec: " + spec);
            }

            finishedPlanning.add(spec);
        } finally {
            dependencyPath.remove(dependencyPath.size() - 1);
        }
    }

    private void planForMethodHandleSpec(MethodHandleSpec m, int depth) {
        for (var p : m.parameters()) {
            if (p.isList()) {
                planForListParameter(p, depth + 1);
            } else if (p.canBeProxied()) {
                planForInstanceProxy(p, depth + 1);
            } else {
                logger.trace("{}- Recursing into {} for parameter {}", indent(depth), p.injectableType(), p);
                planForClass(p.injectableType(), depth + 1);
            }
        }
        addStep(new InstantiateStep(m), depth);
    }

    private void planForListParameter(ParameterSpec p, int depth) {
        Class<?> elementType = p.injectableType();
        if (p.canBeProxied()) {
            // Create a proxy list if we haven't already
            if (alreadyListProxied.add(elementType)) {
                logger.trace("{}- Creating list proxy for {}", indent(depth), elementType.getSimpleName());
                addStep(new CreateListProxyStep(elementType), depth);
            }
        } else {
            // @Actual list: must have all instances ready now
            logger.trace("{}- Planning actual list of {}", indent(depth), elementType.getSimpleName());
            planAllCandidatesOf(elementType, depth);
            // Ensure the proxy is created and resolved
            if (alreadyListProxied.add(elementType)) {
                addStep(new CreateListProxyStep(elementType), depth);
            }
            addStep(new ResolveListProxyStep(elementType), depth);
        }
    }

    private void planForInstanceProxy(ParameterSpec p, int depth) {
        Class<?> type = p.injectableType();
        if (alreadyInstanceProxied.add(type)) {
            logger.trace("{}- Creating instance proxy for {}", indent(depth), type.getSimpleName());
            addStep(new CreateInstanceProxyStep(type), depth);
        }
    }

    private void planForSubtypeSpec(SubtypeSpec s, int depth) {
        logger.trace("{}- Subtype redirect: {} -> {}", indent(depth), s.requestedType().getSimpleName(), s.subtype().getSimpleName());
        planForClass(s.subtype(), depth + 1);
        addStep(new RollupStep(s.subtype(), s.requestedType()), depth);
    }

    private void planForAmbiguousSpec(AmbiguousSpec a, int depth) {
        // Plan all candidates — their instances will be collected into a list
        logger.trace("{}- Planning ambiguous spec for {}", indent(depth), a.requestedType().getSimpleName());
        a.candidates().forEach(candidate -> planForSpec(candidate, depth + 1));
    }

    /**
     * Plans all candidates that can provide instances of the given element type.
     */
    private void planAllCandidatesOf(Class<?> elementType, int depth) {
        InjectionSpec spec = specsByClass.get(elementType);
        if (spec == null) {
            return;
        }
        if (spec instanceof AmbiguousSpec a) {
            a.candidates().forEach(candidate -> planForSpec(candidate, depth));
        } else {
            planForSpec(spec, depth);
        }
    }

    /**
     * Emit resolve steps for any proxies that haven't been resolved yet.
     * Before resolving, plan the spec for each proxied type so that rollup steps are emitted.
     */
    private void planProxyResolution() {
        for (Class<?> proxiedType : alreadyListProxied) {
            boolean alreadyResolved = plan.stream().anyMatch(
                step -> step instanceof ResolveListProxyStep r && r.elementType() == proxiedType
            );
            if (alreadyResolved == false) {
                InjectionSpec spec = specsByClass.get(proxiedType);
                if (spec != null) {
                    planForSpec(spec, 0);
                }
                plan.add(new ResolveListProxyStep(proxiedType));
            }
        }
        for (Class<?> proxiedType : alreadyInstanceProxied) {
            InjectionSpec spec = specsByClass.get(proxiedType);
            if (spec != null) {
                planForSpec(spec, 0);
            }
            plan.add(new ResolveInstanceProxyStep(proxiedType));
        }
    }

    private void addStep(InjectionStep newStep, int depth) {
        logger.trace("{}- Add step {}", indent(depth), newStep);
        plan.add(newStep);
    }

    private static Supplier<String> indent(int depth) {
        return () -> "\t".repeat(depth);
    }
}
