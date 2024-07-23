package org.elasticsearch.nalbind.injector;

import org.elasticsearch.logging.LogManager;
import org.elasticsearch.logging.Logger;
import org.elasticsearch.nalbind.api.CyclicDependencyException;
import org.elasticsearch.nalbind.api.Inject;
import org.elasticsearch.nalbind.api.InjectionConfigurationException;
import org.elasticsearch.nalbind.injector.spec.AliasSpec;
import org.elasticsearch.nalbind.injector.spec.AmbiguousSpec;
import org.elasticsearch.nalbind.injector.spec.ExistingInstanceSpec;
import org.elasticsearch.nalbind.injector.spec.InjectionModifiers;
import org.elasticsearch.nalbind.injector.spec.InjectionSpec;
import org.elasticsearch.nalbind.injector.spec.MethodHandleSpec;
import org.elasticsearch.nalbind.injector.spec.ParameterSpec;
import org.elasticsearch.nalbind.injector.step.InjectionStep;
import org.elasticsearch.nalbind.injector.step.InstantiateStep;
import org.elasticsearch.nalbind.injector.step.ListProxyCreateStep;
import org.elasticsearch.nalbind.injector.step.ListProxyResolveStep;
import org.elasticsearch.nalbind.injector.step.RollUpStep;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Constructor;
import java.lang.reflect.Modifier;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.stream.Stream;

import static java.util.Collections.unmodifiableMap;
import static java.util.Collections.unmodifiableSet;
import static java.util.function.Predicate.not;
import static java.util.stream.Collectors.joining;
import static java.util.stream.Collectors.toCollection;
import static org.elasticsearch.nalbind.injector.spec.InjectionModifiers.LIST;

public class Injector {
    private final Set<Class<?>> classesToInstantiate;
    private final Map<Class<?>, Object> existingInstances;
    private final MethodHandles.Lookup lookup;

    Injector(Collection<Class<?>> classesToInstantiate, Map<Class<?>, Object> existingInstances, MethodHandles.Lookup lookup) {
        this.classesToInstantiate = new LinkedHashSet<>(classesToInstantiate);
        this.existingInstances = existingInstances;
        this.lookup = lookup;
    }

    public static Injector create(MethodHandles.Lookup lookup) {
        return new Injector(new LinkedHashSet<>(), new LinkedHashMap<>(), lookup);
    }

    /**
     * Instructs the injector to instantiate <code>classToProcess</code>
     * in accordance with whatever annotations may be present on that class.
     * <p>
     * There are only three ways the injector can find out that it must instantiate some class:
     * <ol>
     *     <li>
     *         This method (which is also used to implement {@link org.elasticsearch.nalbind.api.AutoInjectable @Autoinjectable})
     *     </li>
     *     <li>
     *         The parameter passed to {@link #inject}
     *     </li>
     *     <li>
     *         A constructor parameter of some other class being instantiated,
     *         having exactly the right class (not a supertype)
     *     </li>
     * </ol>
     *
     * @return <code>this</code>
     */
    public Injector addClass(Class<?> classToProcess) {
        this.classesToInstantiate.add(classToProcess);
        return this;
    }

    /**
     * Equivalent to multiple chained calls to {@link #addClass}.
     */
    public Injector addClasses(Class<?>... classesToProcess) {
        return addClasses(Arrays.asList(classesToProcess));
    }

    /**
     * Equivalent to multiple chained calls to {@link #addClass}.
     */
    public Injector addClasses(Collection<Class<?>> classesToProcess) {
        this.classesToInstantiate.addAll(classesToProcess);
        return this;
    }

    /**
     * Equivalent to {@link #addInstance addInstance(object.getClass(), object)}.
     */
    public <T> Injector addInstance(T object) {
        // TODO: Raise an error if the object's class is annotated with a Stereotype other than SERVICE.
        @SuppressWarnings("unchecked")
        Class<? super T> aClass = (Class<? super T>) object.getClass();
        return addInstance(aClass, object);
    }

    /**
     * Equivalent to multiple calls to {@link #addInstance(Object)}.
     */
    public Injector addInstances(Object... objects) {
        for (var x : objects) {
            addInstance(x);
        }
        return this;
    }

    /**
     * Indicates that <code>object</code> is to be injected for parameters of type <code>type</code>.
     * The given object is treated as though it had been instantiated by the injector.
     */
    public <T> Injector addInstance(Class<? super T> type, T object) {
        Object existing = this.existingInstances.put(type, object);
        if (existing != null) {
            throw new IllegalStateException("There's already an object for " + type);
        }
        return this;
    }

    /**
     * @param resultType The type of object to return; if {@link Void Void.class}, will return <code>null</code>.
     */
    public <T> T inject(Class<T> resultType) {
        if (resultType != Void.class) {
            // Ensure the result class is specified
            if (false == (existingInstances.containsKey(resultType) || classesToInstantiate.contains(resultType))) {
                classesToInstantiate.add(resultType);
            }
        }

        LOGGER.debug("Starting injection");
        Map<Class<?>, InjectionSpec> specMap = specMap(existingInstances, classesToInstantiate, lookup);
        PlanInterpreter i = new PlanInterpreter(existingInstances);
        i.doInjection(injectionPlan(classesToInstantiate, specMap));
        LOGGER.debug("Done injection");

        if (resultType == Void.class) {
            return null;
        } else {
            return i.theOnlyInstance(resultType);
        }
    }

    /**
     * @return an {@link InjectionSpec} for every class the injector is capable of injecting.
     *
     * @param lookup is used to find {@link MethodHandle}s for constructors.
     */
    private static Map<Class<?>, InjectionSpec> specMap(
        Map<Class<?>, Object> existingInstances,
        Set<Class<?>> classesToInstantiate,
        MethodHandles.Lookup lookup
    ) {
        if (LOGGER.isDebugEnabled()) {
            LOGGER.debug("Classes to instantiate: {}", classesToInstantiate.stream().map(Class::getSimpleName).toList());
        }

        Set<Class<?>> checklist = new HashSet<>();
        Queue<ParameterSpec> queue = classesToInstantiate.stream()
            .map(Injector::syntheticParameterSpec)
            .collect(toCollection(ArrayDeque::new));
        Map<Class<?>, InjectionSpec> specsByClass = new LinkedHashMap<>();
        existingInstances.forEach((type, obj) -> {
            registerSpec(new ExistingInstanceSpec(type, obj), specsByClass);
            aliasSuperinterfaces(type, type, specsByClass);
        });

        ParameterSpec p;
        while ((p = queue.poll()) != null) {
            Class<?> c = p.injectableType();
            InjectionSpec existingResult = specsByClass.get(c);
            if (existingResult != null) {
                LOGGER.trace("Spec for {} already exists", c.getSimpleName());
                continue;
            }

            // At this point, we know we'll need to create a MethodHandleSpec

            if (checklist.add(c)) {
                Constructor<?> constructor = getSuitableConstructorIfAny(c);
                if (constructor == null) {
                    throw new InjectionConfigurationException("No suitable constructor for " + c);
                }

                LOGGER.trace("Inspecting parameters for constructor: {}", constructor);
                for (var parameter: constructor.getParameters()) {
                    ParameterSpec ps = ParameterSpec.from(parameter);
                    LOGGER.trace("Enqueue {}", parameter);
                    queue.add(ps);
                }

                MethodHandle ctorHandle;
                try {
                    ctorHandle = lookup.unreflectConstructor(constructor);
                } catch (IllegalAccessException e) {
                    throw new InjectionConfigurationException(e);
                }

                List<ParameterSpec> parameters = Stream.of(constructor.getParameters())
                    .map(ParameterSpec::from)
                    .toList();

                registerSpec(
                    new MethodHandleSpec(constructor.getDeclaringClass(), ctorHandle, parameters),
                    specsByClass
                );
                aliasSuperinterfaces(c, c, specsByClass);
                for (Class<?> superclass = c.getSuperclass(); superclass != Object.class; superclass = superclass.getSuperclass()) {
                    if (Modifier.isAbstract(superclass.getModifiers())) {
                        registerSpec(new AliasSpec(superclass, c), specsByClass);
                    } else {
                        LOGGER.trace("Not aliasing {} to concrete superclass {}", c.getSimpleName(), superclass.getSimpleName());
                    }
                    aliasSuperinterfaces(superclass, c, specsByClass);
                }
            } else {
                throw new CyclicDependencyException("Cycle detected");
            }
        }
        if (LOGGER.isTraceEnabled()) {
            LOGGER.trace("Specs: {}", specsByClass.values().stream().map(Object::toString).collect(joining("\n\t", "\n\t", "")));
        }
        return specsByClass;
    }

    /**
     * For the classes we've been explicitly asked to inject,
     * pretend there's some massive method taking all of them as parameters
     */
    private static ParameterSpec syntheticParameterSpec(Class<?> c) {
        return new ParameterSpec("synthetic_" + c.getSimpleName(), c, c, InjectionModifiers.NONE);
    }

    private static Constructor<?> getSuitableConstructorIfAny(Class<?> type) {
        var constructors = Stream.of(type.getDeclaredConstructors()).filter(not(Constructor::isSynthetic)).toList();
        if (constructors.size() == 1) {
            return constructors.get(0);
        }
        var injectConstructors = constructors.stream().filter(c -> c.isAnnotationPresent(Inject.class)).toList();
        if (injectConstructors.size() == 1) {
            return injectConstructors.get(0);
        }
        LOGGER.trace("No suitable constructor for {}", type);
        return null;
    }

    /**
     * When creating <code>specsByClass</code>, we compute a kind of "inheritance closure"
     * in the sense that, for each class <code>C</code>, we not only add an entry for <code>C</code>,
     * but we also add {@link AliasSpec} entries for all abstract supertypes.
     * <p>
     * This method is part of the recursion that achieves this.
     */
    private static void aliasSuperinterfaces(Class<?> classToScan, Class<?> classToAlias, Map<Class<?>, InjectionSpec> specsByClass) {
        for (var i : classToScan.getInterfaces()) {
            registerSpec(new AliasSpec(i, classToAlias), specsByClass);
            aliasSuperinterfaces(i, classToAlias, specsByClass);
        }
    }

    private static void registerSpec(InjectionSpec spec, Map<Class<?>, InjectionSpec> specsByClass) {
        Class<?> requestedType = spec.requestedType();
        var existing = specsByClass.put(requestedType, spec);
        if (existing == null || existing.equals(spec)) {
            LOGGER.trace("Register spec: {}", spec);
        } else {
            AmbiguousSpec ambiguousSpec = new AmbiguousSpec(requestedType, spec, existing);
            LOGGER.trace("Ambiguity discovered: {}", ambiguousSpec);
            specsByClass.put(requestedType, ambiguousSpec);
        }
    }

    /**
     * @return the {@link InjectionStep} objects listed in execution order.
     */
    private List<InjectionStep> injectionPlan(Set<Class<?>> requiredClasses, Map<Class<?>, InjectionSpec> specsByClass) {
        // TODO: Cycle detection and reporting. Use SCCs
        LOGGER.trace("Constructing instantiation plan");
        Set<Class<?>> allParameterTypes = new HashSet<>();
        specsByClass.values().forEach(spec -> {
            if (spec instanceof MethodHandleSpec m) {
                m.parameters().stream()
                    .map(ParameterSpec::injectableType)
                    .forEachOrdered(allParameterTypes::add);
            }
        });

        var plan = new Planner(specsByClass, requiredClasses, allParameterTypes).injectionPlan();
        if (LOGGER.isDebugEnabled()) {
            LOGGER.debug("Injection plan: {}", plan.stream().map(Object::toString).collect(joining("\n\t", "\n\t", "")));
        }
        return plan;
    }

    /**
     * <em>Evolution note</em>: the intent is to plan one domain/subsystem at a time.
     */
    private static final class Planner {
        final List<InjectionStep> plan;
        final Queue<Class<?>> queue;
        final Map<Class<?>, InjectionSpec> specsByClass;
        final Set<Class<?>> requiredTypes; // The injector's job is to ensure there is an instance of these; this is like the "root set"
        final Set<Class<?>> allParameterTypes; // All the injectable types in all dependencies (recursively) of all required types
        final Set<InjectionSpec> startedPlanning;
        final Set<InjectionSpec> finishedPlanning;
        final Set<Class<?>> alreadyProxied;

        Planner(Map<Class<?>, InjectionSpec> specsByClass, Set<Class<?>> requiredTypes, Set<Class<?>> allParameterTypes) {
            this.requiredTypes = requiredTypes;
            this.plan = new ArrayList<>();
            this.specsByClass = unmodifiableMap(specsByClass);
            this.queue = new ArrayDeque<>(specsByClass.keySet());
            this.allParameterTypes = unmodifiableSet(allParameterTypes);
            this.startedPlanning = new HashSet<>();
            this.finishedPlanning = new HashSet<>();
            this.alreadyProxied = new HashSet<>();
        }

        /**
         * Note that not all proxies are resolved once this plan has been executed.
         * <p>
         *
         * <em>Evolution note</em>: in a world with multiple domains/subsystems,
         * it will become necessary to defer proxy resolution until after other plans
         * have been executed, because they could create additional objects that ought
         * to be included in the proxies created by this plan.
         *
         * @return a plan that's complete except that some proxies might be left unresolved.
         */
        List<InjectionStep> injectionPlan() {
            Class<?> c;
            while ((c = queue.poll()) != null) {
                updatePlan(c, 0);
            }
            return plan;
        }

        /**
         * Recursive procedure that determines what effect <code>requestedClass</code>
         * should have on the plan under construction.
         *
         * @param depth is used just for indenting the logs
         */
        private void updatePlan(Class<?> requestedClass, int depth) {
            String indent;
            if (LOGGER.isTraceEnabled()) {
                indent = "\t".repeat(depth);
            } else {
                indent = null;
            }
            InjectionSpec spec = specsByClass.get(requestedClass);
            if (spec == null) {
                throw new InjectionConfigurationException("Cannot instantiate " + requestedClass);
            }
            if (finishedPlanning.contains(spec)) {
                LOGGER.trace("{}Already planned", indent);
                return;
            }

            if (startedPlanning.add(spec) == false) {
                throw new InjectionConfigurationException("Cyclic dependency involving " + spec);
            }

            LOGGER.trace("{}Planning for {}", indent, spec);
            if (spec instanceof MethodHandleSpec m) {
                for (var p : m.parameters()) {
                    if (p.canBeProxied()) {
                        if (alreadyProxied.add(p.injectableType())) {
                            addStep(indent, new ListProxyCreateStep(p.injectableType()));
                        } else {
                            LOGGER.trace("{}- Use existing proxy for {}", indent, p);
                        }
                    } else {
                        LOGGER.trace("{}- Recursing into {} for actual parameter {}", indent, p.injectableType(), p);
                        updatePlan(p.injectableType(), depth + 1);
                        if (p.modifiers().contains(LIST)) {
                            addStep(indent, new ListProxyResolveStep(p.injectableType()));
                        }
                    }
                }
                addStep(indent, new InstantiateStep(m));
            } else if (spec instanceof AliasSpec a) {
                LOGGER.trace("{}Recursing into subtype for {}", indent, a);
                updatePlan(a.subtype(), depth + 1);
                if (allParameterTypes.contains(a.requestedType()) == false) {
                    // Could be an opportunity for optimization here.
                    // The _only_ reason we need these unused aliases is in case
                    // somebody asks for one directly from the injector; they are
                    // not needed otherwise.
                    // If we change the injector setup so the user must specify
                    // which types they'll pull directly, we could skip these.
                    LOGGER.trace("{}- Planning unused {}", indent, a);
                }
                addStep(indent, new RollUpStep(a.requestedType(), a.subtype()));
            } else if (spec instanceof ExistingInstanceSpec e) {
                LOGGER.trace("{}- Plan {}", indent, e);
                // Nothing to do. The injector will already have the required object.
            } else if (spec instanceof AmbiguousSpec a) {
                if (requiredTypes.contains(a.requestedType())) {
                    throw new InjectionConfigurationException("Ambiguous injection spec for required type: " + a);
                } else {
                    LOGGER.trace("{}- Skipping {}", indent, a);
                    // Nothing to do. Nobody could validly ask for an instance of an ambiguous class anyway;
                    // this must be a class we encountered as a List, and that case is already handled before
                    // we recurse to this point.
                }
            }

            finishedPlanning.add(spec);
        }

        private void addStep(String indent, InjectionStep newStep) {
            LOGGER.trace("{}- Add step {}", indent, newStep);
            plan.add(newStep);
        }

    }

    private static final Logger LOGGER = LogManager.getLogger(Injector.class);
}
