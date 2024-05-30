package org.elasticsearch.nalbind.injector;

import org.elasticsearch.logging.LogManager;
import org.elasticsearch.logging.Logger;
import org.elasticsearch.nalbind.api.Inject;
import org.elasticsearch.nalbind.api.InjectableSingleton;
import org.elasticsearch.nalbind.api.Injected;
import org.elasticsearch.nalbind.api.Now;
import org.elasticsearch.nalbind.injector.spec.AliasSpec;
import org.elasticsearch.nalbind.injector.spec.AmbiguousSpec;
import org.elasticsearch.nalbind.injector.spec.ConstructorSpec;
import org.elasticsearch.nalbind.injector.spec.DistinctInstanceSpec;
import org.elasticsearch.nalbind.injector.spec.ExistingInstanceSpec;
import org.elasticsearch.nalbind.injector.spec.InjectionSpec;
import org.elasticsearch.nalbind.injector.spec.UnambiguousSpec;

import java.lang.annotation.Annotation;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import static java.util.Arrays.asList;
import static java.util.Collections.newSetFromMap;
import static java.util.Objects.requireNonNull;
import static java.util.stream.Collectors.joining;
import static org.elasticsearch.nalbind.injector.spi.ClassFinder.Holder.CLASS_FINDER;
import static org.elasticsearch.nalbind.injector.spi.ProxyBytecodeGenerator.Holder.PROXY_BYTECODE_GENERATOR;

public class Injector {
    private final Collection<Class<?>> classesToProcess;
    private final Map<Class<?>, Object> injectedInstances;

    Injector(Collection<Class<?>> classesToProcess, Map<Class<?>, Object> injectedInstances) {
        this.classesToProcess = classesToProcess;
        this.injectedInstances = injectedInstances;
    }

    public static Injector create() {
        return new Injector(new LinkedHashSet<>(), new LinkedHashMap<>());
    }

	public Injector addInjectableSingletonsProvidedBy(ModuleLayer layer) {
		return addClasses(injectableSingletonsProvidedBy(layer));
	}

	public Injector addClasses(Collection<Class<?>> classesToProcess) {
        this.classesToProcess.addAll(classesToProcess);
		return this;
	}

    public Injector addAnnotatedClasspathClasses(Class<? extends Annotation> annotation) {
        return addClasses(CLASS_FINDER.classesOnClasspathWithAnnotation(annotation));
    }

    public <T> Injector addInstance(T object) {
        @SuppressWarnings("unchecked")
        Class<? super T> aClass = (Class<? super T>) object.getClass();
        return addInstance(aClass, object);
    }

    public Injector addInstances(Object... objects) {
        for (var x: objects) {
            addInstance(x);
        }
        return this;
    }

    public <T> Injector addInstance(Class<? super T> type, T object) {
        Object existing = this.injectedInstances.put(type, object);
        if (existing != null) {
            throw new IllegalStateException("There's already an object for " + type);
        }
        return this;
    }

    public ObjectGraph inject() {
        InjectionInProgress i = new InjectionInProgress(injectedInstances);
        i.doInjection(specMap(injectedInstances, classesToProcess));
        return new ObjectGraph(i.instances);
    }

    private static Map<Class<?>, InjectionSpec> specMap(
        Map<Class<?>, Object> injectedInstances,
        Collection<Class<?>> classesToProcess
    ) {
		LOGGER.debug("Root set: {}", classesToProcess);

		Set<Class<?>> checklist = new HashSet<>(classesToProcess);
		Map<Class<?>, InjectionSpec> specsByClass = new LinkedHashMap<>();
        injectedInstances.forEach((type, obj) -> {
            registerSpec(new ExistingInstanceSpec(type, obj, getReportInjectedMethods(obj.getClass())), specsByClass);
            aliasSuperinterfaces(type, type, specsByClass);
        });
		for (var c: classesToProcess) {
			computeSpec(c, checklist, specsByClass);
		}
		if (LOGGER.isTraceEnabled()) {
			LOGGER.trace("Specs: {}",
				specsByClass.values().stream()
					.map(Object::toString)
					.collect(joining("\n\t", "\n\t", "")));
		}
		return specsByClass;
	}

	private static Set<Class<?>> injectableSingletonsProvidedBy(ModuleLayer layer) {
		Set<Class<?>> classesToProcess = new HashSet<>();
		for (var m: layer.modules()) {
			for (var p: m.getDescriptor().provides()) {
				if (InjectableSingleton.class.getName().equals(p.service())) {
					p.providers().forEach(name -> {
						try {
							classesToProcess.add(m.getClassLoader().loadClass(name));
						} catch (ClassNotFoundException e) {
							throw new IllegalStateException("Unexpected error scanning module layer", e);
						}
					});
				}
			}
		}
		return classesToProcess;
	}

	/**
	 * @param checklist will have <code>c</code> removed from it
	 * @param specsByClass will be left in topological order
	 */
	private static void computeSpec(Class<?> c, Set<Class<?>> checklist, Map<Class<?>, InjectionSpec> specsByClass) {
		InjectionSpec existingResult = specsByClass.get(c);
		if (existingResult != null) {
			LOGGER.trace("Spec for {} already exists", c);
			return;
		}

		if (checklist.remove(c)) {
			Constructor<?> constructor = getSuitableConstructorIfAny(c);
			if (constructor == null) {
				LOGGER.debug("No suitable constructor: {}", c);
				return;
			}

			LOGGER.trace("Recurse into parameters for constructor: {}", constructor);
			for (var pt: constructor.getParameterTypes()) {
				computeSpec(pt, checklist, specsByClass);
			}

			List<Method> reportInjectedMethods = getReportInjectedMethods(c);
			for (Method m: reportInjectedMethods) {
				LOGGER.trace("Recurse into parameters for method: {}", m);
				for (var pt: m.getParameterTypes()) {
					computeSpec(pt, checklist, specsByClass);
				}
			}

			registerSpec(new ConstructorSpec(constructor, reportInjectedMethods), specsByClass);
			aliasSuperinterfaces(c, c, specsByClass);
			for (Class<?> superclass = c.getSuperclass(); superclass != Object.class; superclass = superclass.getSuperclass()) {
				registerSpec(new AliasSpec(superclass, c), specsByClass);
				aliasSuperinterfaces(superclass, c, specsByClass);
			}
		}
	}

	private static Constructor<?> getSuitableConstructorIfAny(Class<?> type) {
		Constructor<?>[] constructors = type.getDeclaredConstructors();
		if (constructors.length == 1) {
			return constructors[0];
		} else {
			var injectConstructors = Stream.of(constructors)
				.filter(c -> c.isAnnotationPresent(Inject.class))
				.toList();
			if (injectConstructors.size() == 1) {
				return injectConstructors.get(0);
			} else {
				return null;
			}
		}
	}

	private static void aliasSuperinterfaces(Class<?> classToScan, Class<?> classToAlias, Map<Class<?>, InjectionSpec> specsByClass) {
		for (var i: classToScan.getInterfaces()) {
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

	private static List<Method> getReportInjectedMethods(Class<?> givenClass) {
		List<Method> result = new ArrayList<>();
		for (var c = givenClass; c != Object.class; c = c.getSuperclass()) {
			for (var m: c.getDeclaredMethods()) {
				if (m.isAnnotationPresent(Injected.class)) {
					checkValidInjectedMethod(m);
					result.add(m);
				}
			}
		}
		return result;
	}

	private static void checkValidInjectedMethod(Method method) {
		var pts = method.getParameterTypes();
		if (pts.length != 1) {
			throw new IllegalStateException(
                "Expected @" + Injected.class.getSimpleName() + " method to have one parameter: " + method);
		}
		var pt = pts[0];
		if (Collection.class != pt) {
			// TODO: It should also a collection of the right type of elements
			throw new IllegalStateException(
                "Expected @" + Injected.class.getSimpleName() + " method parameter to be a Collection: " + method);
		}
	}

	/**
	 * @return the {@link UnambiguousSpec} objects listed in execution order.
	 */
	private static Collection<UnambiguousSpec> instantiationPlan(Map<Class<?>, InjectionSpec> specsByClass) {
		// TODO: Cycle detection and reporting. Use SCCs
		LOGGER.trace("Constructing instantiation plan");
		Set<Class<?>> allParameterTypes = new HashSet<>();
		specsByClass.values().forEach(spec -> {
			if (spec instanceof ConstructorSpec c) {
				allParameterTypes.addAll(asList(c.constructor().getParameterTypes()));
			}
		});
		List<UnambiguousSpec> plan = new ArrayList<>();
		Set<InjectionSpec> alreadyPlanned = newSetFromMap(new IdentityHashMap<>());
		specsByClass.keySet().forEach((c) ->
			updateInstantiationPlan(plan, c, specsByClass, allParameterTypes, alreadyPlanned)
		);
        if (LOGGER.isTraceEnabled()) {
            LOGGER.trace("Instantiation plan: {}", plan.stream()
                .map(InjectionSpec::toString)
				.collect(joining("\n\t", "\n\t", "")));
        }
		return plan;
	}

	private static void updateInstantiationPlan(
		List<UnambiguousSpec> plan,
		Class<?> requestedClass,
		Map<Class<?>, InjectionSpec> specsByClass,
		Set<Class<?>> allParameterTypes,
		Set<InjectionSpec> alreadyPlanned
	) {
		InjectionSpec spec = specsByClass.get(requestedClass);
		if (alreadyPlanned.add(spec)) {
            if (spec == null) {
                throw new IllegalStateException("Cannot instantiate " + requestedClass);
            } else if (spec instanceof ConstructorSpec c) {
                if (c.constructor().getParameterCount() != 0) {
                    for (var p : c.constructor().getParameters()) {
                        if (p.isAnnotationPresent(Now.class)) {
                            LOGGER.trace("Recursing into @Now parameter {} of {}", p.getName(), c);
                            updateInstantiationPlan(plan, p.getType(), specsByClass, allParameterTypes, alreadyPlanned);
                        }
                    }
                }
                LOGGER.trace("Plan {}", c);
                plan.add(c);
            } else if (spec instanceof AliasSpec a) {
                LOGGER.trace("Recursing into subtype for {}", a);
                updateInstantiationPlan(plan, a.subtype(), specsByClass, allParameterTypes, alreadyPlanned);
                if (allParameterTypes.contains(a.requestedType())) {
                    LOGGER.trace("Plan {}", a);
                } else {
                    // Could be an opportunity for optimization here.
                    // The _only_ reason we need these unused aliases is in case
                    // somebody asks for one directly from the injector; they are
                    // not needed otherwise.
                    // If we change the injector setup so the user must specify
                    // which types they'll pull directly, we could skip these.
                    LOGGER.trace("Plan unused {}", a);
                }
                plan.add(a);
            } else if (spec instanceof ExistingInstanceSpec e) {
                LOGGER.trace("Plan {}", e);
                plan.add(e);
            } else if (spec instanceof AmbiguousSpec a) {
                LOGGER.trace("Skipping {}", a);
            }
		}
	}

	private static Class<?> rawClass(Type sourceType) {
		if (sourceType instanceof ParameterizedType pt) {
			return (Class<?>)pt.getRawType();
		} else {
			return (Class<?>)sourceType;
		}
	}

    /**
     * A holder for the mutable injection state while injection is running.
     * This means we don't need to pass all kinds of mutable arguments all around.
     */
    private class InjectionInProgress {
        final Map<Class<?>, Object> instances = new LinkedHashMap<>();
        final List<ProxyFactory.ProxyInfo<?>> proxies = new ArrayList<>();

        InjectionInProgress(Map<Class<?>, Object> injectedInstances) {
            instances.putAll(injectedInstances);
        }

        /**
         * Main entry point
         */
        void doInjection(Map<Class<?>, InjectionSpec> specsByClass) {
            Collection<UnambiguousSpec> plan = instantiationPlan(specsByClass);
            createProxies(plan);
            executeInstantiationPlan(plan);
            resolveProxies();
            reportInjectedObjects(specsByClass);
        }

        void createProxies(Collection<UnambiguousSpec> plan) {
            var proxyFactory = new ProxyFactoryImpl(PROXY_BYTECODE_GENERATOR);
            for (var spec: plan) {
                // Proxies are for interfaces, and interfaces can't be instantiated;
                // therefore, proxies are only needed for AliasSpec.
                if (spec instanceof AliasSpec a) {
                    var requestedType = a.requestedType();
                    LOGGER.debug("Creating proxy for {}", requestedType.getSimpleName());
                    var proxyInfo = proxyFactory.generateFor(requestedType);
                    proxies.add(proxyInfo);
                    instances.put(requestedType, proxyInfo.proxyObject());
                }
            }
        }

        void resolveProxies() {
            for (var proxyInfo: proxies) {
                resolveProxy(proxyInfo);
            }
        }

        <T> void resolveProxy(ProxyFactory.ProxyInfo<T> proxyInfo) {
            Class<T> type = proxyInfo.interfaceType();
            proxyInfo.setter().accept(type.cast(instances.get(type)));
        }

        /**
         * As each object is created, it replaces its proxy in {@link #instances}.
         * TODO: This hides errors. We should have a mode where we inject proxies
         * to the greatest extent possible to catch cases where people call methods
         * without using the @Now annotation.
         */
        private void executeInstantiationPlan(Collection<UnambiguousSpec> plan) {
            plan.forEach(spec -> {
                if (requireNonNull(spec) instanceof ConstructorSpec c) {
                    LOGGER.debug("Instantiating {}", c.requestedType().getSimpleName());
                    instances.put(c.requestedType(), instantiate(c.constructor()));
                } else if (spec instanceof AliasSpec a) {
                    var requestedType = a.requestedType();
                    var subtype = a.subtype();
                    LOGGER.debug("Aliasing {} = {}", requestedType.getSimpleName(), subtype.getSimpleName());
                    instances.put(requestedType, requireNonNull(instances.get(subtype), "No object for " + subtype));
                } else if (spec instanceof ExistingInstanceSpec e) {
                    LOGGER.debug("Using user-provided instance for " + e.requestedType().getSimpleName());
                    assert instances.containsKey(e.requestedType());
                } else {
                    // TODO: switch patterns!
                    throw new AssertionError("Unexpected spec type: " + spec.getClass().getSimpleName());
                }
            });
        }

        private Object instantiate(Constructor<?> constructor) {
            Object[] args = Stream.of(constructor.getParameterTypes())
                .map(t -> requireNonNull(instances.get(t), "No object for " + t))
                .toArray();
            try {
                return constructor.newInstance(args);
            } catch (InvocationTargetException | InstantiationException | IllegalAccessException e) {
                throw new IllegalStateException("Unable to call constructor: " + constructor, e);
            }
        }

        private void reportInjectedObjects(Map<Class<?>, InjectionSpec> specsByClass) {
            Set<Object> distinctInstances = newSetFromMap(new IdentityHashMap<>());
            distinctInstances.addAll(instances.values());

            // There must be a more efficient way to do this. This way is quadratic.
            for (Object obj: distinctInstances) {
                var spec = specsByClass.get(obj.getClass());
                if (spec instanceof DistinctInstanceSpec d) {
                    for (Method m: d.reportInjectedMethods()) {
                        Type requiredType = ((ParameterizedType)m.getGenericParameterTypes()[0])
                            .getActualTypeArguments()[0];
                        Class<?> requiredClass = rawClass(requiredType);
                        var relevantObjects = distinctInstances.stream()
                            .filter(requiredClass::isInstance)
                            .toList();
                        try {
                            m.invoke(obj, relevantObjects);
                        } catch (IllegalAccessException | InvocationTargetException e) {
                            throw new IllegalStateException(
                                "Can't invoke " + Injected.class.getSimpleName() + " method", e);
                        }
                    }
                }
            }
        }
    }

    private static final Logger LOGGER = LogManager.getLogger(Injector.class);
}
