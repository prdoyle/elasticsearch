/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the "Elastic License
 * 2.0", the "GNU Affero General Public License v3.0 only", and the "Server Side
 * Public License v 1"; you may not use this file except in compliance with, at
 * your election, the "Elastic License 2.0", the "GNU Affero General Public
 * License v3.0 only", or the "Server Side Public License, v 1".
 */

package org.elasticsearch.injection;

import org.elasticsearch.injection.spec.AmbiguousSpec;
import org.elasticsearch.injection.spec.ExistingInstanceSpec;
import org.elasticsearch.injection.spec.InjectionSpec;
import org.elasticsearch.injection.spec.MethodHandleSpec;
import org.elasticsearch.injection.spec.ParameterSpec;
import org.elasticsearch.injection.spec.SubtypeSpec;
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
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.elasticsearch.action.fieldcaps.TransportFieldCapabilitiesAction.LOGGER;
import static org.elasticsearch.injection.spec.ParameterModifier.COLLECTION;

/**
 * <em>Evolution note</em>: the intent is to plan one domain/subsystem at a time.
 */
final class Planner {
    private static final Logger logger = LogManager.getLogger(Planner.class);

    final List<InjectionStep> plan;
    final Map<Class<?>, InjectionSpec> specsByClass;
    final Set<Class<?>> seedTypes; // The injector's job is to ensure there is an instance of each of these
    final Set<InjectionSpec> startedPlanning;
    final Set<InjectionSpec> finishedPlanning;
    final Set<Class<?>> alreadyProxiedInstance;
    final Set<Class<?>> alreadyProxiedCollection;

    /**
     * @param specsByClass an {@link InjectionSpec} indicating how each class should be injected
     * @param seedTypes the classes of which we need instances
     */
    Planner(Map<Class<?>, InjectionSpec> specsByClass, Set<Class<?>> seedTypes) {
        this.seedTypes = seedTypes;
        this.plan = new ArrayList<>();
        this.specsByClass = Map.copyOf(specsByClass);
        this.startedPlanning = new HashSet<>();
        this.finishedPlanning = new HashSet<>();
        this.alreadyProxiedInstance = new HashSet<>();
        this.alreadyProxiedCollection = new HashSet<>();
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
    List<InjectionStep> computeInjectionPlan() {
        assert plan.isEmpty();
        for (Class<?> c : seedTypes) {
            planForClass(c, 0);
        }
        planProxyResolution(unresolvedInstanceProxies(), unresolvedCollectionProxies());
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
            throw new IllegalStateException("Cannot instantiate " + requestedClass + ": no specification provided");
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
            // TODO: Better cycle detection and reporting. Use SCCs
            throw new IllegalStateException("Cyclic dependency involving " + spec);
        }

        switch (spec) {
            case ExistingInstanceSpec e -> {
                // Nothing to do. The injector will already have the required object.
                logger.trace("{}- Plan {}", indent(depth), e);
            }
            case MethodHandleSpec m -> {
                for (var p : m.parameters()) {
                    planParameter(p, depth);
                }
                addStep(new InstantiateStep(m), depth);
            }
            case SubtypeSpec s -> {
                LOGGER.trace("{}- Recursing into subtype for {}", indent(depth), s);
                planForClass(s.subtype(), depth + 1);
                addStep(new RollupStep(s.subtype(), s.requestedType()), depth);
            }
            case AmbiguousSpec a -> {
                if (seedTypes.contains(a.requestedType())) {
                    throw new IllegalStateException("Ambiguous injection spec for seed type: " + a);
                } else {
                    // Nobody could validly ask for an instance of an ambiguous class, so
                    // this must be a class we encountered as a Collection.
                    // Ensure we generate the necessary rollups to ensure the list has all the right objects.
                    LOGGER.trace("{}- Processing candidates for {}", indent(depth), a.requestedType());
                    a.candidates().forEach(candidate -> planForSpec(candidate, depth + 1));
                }
            }
        }

        finishedPlanning.add(spec);
    }

    private void planParameter(ParameterSpec p, int depth) {
        if (p.modifiers().contains(COLLECTION)) {
            if (p.canBeProxied()) {
                if (alreadyProxiedCollection.add(p.injectableType())) {
                    addStep(new CreateCollectionProxyStep(p.injectableType()), depth);
                } else {
                    LOGGER.trace("{}- Instance proxy exists for {}", indent(depth), p);
                }
            } else {
                rollUpAndResolveCollectionProxy(p.injectableType(), depth);
            }
        } else {
            if (p.canBeProxied()) {
                if (alreadyProxiedInstance.add(p.injectableType())) {
                    addStep(new CreateInstanceProxyStep(p.injectableType()), depth);
                } else {
                    LOGGER.trace("{}- Use existing instance proxy for {}", indent(depth), p);
                }
            } else {
                planForClass(p.injectableType(), depth + 1);
            }
        }
    }

    private void addStep(InjectionStep newStep, int depth) {
        logger.trace("{}- Add step {}", indent(depth), newStep);
        plan.add(newStep);
    }

    /**
     * An analysis that scans the current plan and finds proxies that are created but never resolved.
     *
     * @return the types for instance proxies that aren't resolved in the current plan
     */
    private Collection<Class<?>> unresolvedInstanceProxies() {
        Set<Class<?>> result = new LinkedHashSet<>();
        for (InjectionStep step : plan) {
            switch (step) {
                case CreateInstanceProxyStep(var t) -> result.add(t);
                case ResolveInstanceProxyStep(var t) -> result.remove(t);
                default -> {} // ignore others
            }
        }
        return result;
    }

    /**
     * An analysis that scans the current plan and finds proxies that are created but never resolved.
     *
     * @return the element types for collection proxies that aren't resolved in the current plan
     */
    private Collection<Class<?>> unresolvedCollectionProxies() {
        Set<Class<?>> result = new LinkedHashSet<>();
        for (InjectionStep step : plan) {
            switch (step) {
                case CreateCollectionProxyStep(var t) -> result.add(t);
                case ResolveCollectionProxyStep(var t) -> result.remove(t);
                default -> {} // ignore others
            }
        }
        return result;
    }

    private void planProxyResolution(Collection<Class<?>> instanceProxiesToResolve, Collection<Class<?>> collectionProxiesToResolve) {
        LOGGER.trace("Resolving remaining proxies: {} instance, {} collection", instanceProxiesToResolve.size(), collectionProxiesToResolve.size());
        instanceProxiesToResolve.forEach(t -> rollUpAndResolveInstanceProxy(t, 0));
        collectionProxiesToResolve.forEach(elementType -> rollUpAndResolveCollectionProxy(elementType, 0));
    }

    private void rollUpAndResolveInstanceProxy(Class<?> type, int depth) {
        LOGGER.trace("{}- Roll up and resolve {}", indent(depth), type);
        planForClass(type, depth + 1);
        addStep(new ResolveInstanceProxyStep(type), depth);
    }

    private void rollUpAndResolveCollectionProxy(Class<?> elementType, int depth) {
        LOGGER.trace("{}- Roll up and resolve collection of {}", indent(depth), elementType);
        planForClass(elementType, depth + 1);
        addStep(new ResolveCollectionProxyStep(elementType), depth);
    }

    private static String indent(int depth) {
        // TODO: Don't call this when logging is off!
        return "\t".repeat(depth);
    }
}
