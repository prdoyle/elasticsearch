# Elasticsearch Custom Injector: Day-0 Design

This document describes the custom dependency injection framework in `org.elasticsearch.injection` — not the vendored Google Guice in `org.elasticsearch.injection.guice`, which is a separate codebase slated for removal.

The injector is 779 lines across 13 files. It is not a general-purpose DI framework. It is purpose-built for Elasticsearch's node startup, where all services are singletons, construction order matters, and errors must be caught before any side effects occur.

---

## Architecture: The Three-Phase Pipeline

Injection proceeds as a pipeline of three phases. Each phase has a dedicated class, a clear responsibility, and a clean handoff to the next.

```
         Configuration              Planning              Execution
        ┌────────────┐         ┌────────────┐         ┌────────────────┐
        │  Injector   │         │  Planner   │         │ PlanInterpreter │
        │             │         │            │         │                │
        │ addClass()  │         │ Validate   │         │ Walk step list │
        │ addInstance()│ ──────▶│ Topo-sort  │ ──────▶│ Invoke ctors   │
        │ inject()    │  specs  │ Detect     │  steps  │ Store results  │
        │             │         │  cycles    │         │                │
        └────────────┘         └────────────┘         └────────────────┘
              ▲                                              │
              │              Map<Class<?>, Object>           │
              └──────────────────────────────────────────────┘
```

### Phase 1: Configuration (`Injector`, 315 lines)

The user registers "seed specs" — the things the injector explicitly knows about:

- **`addClass(Class<?>)`** — register a class to be instantiated. The injector finds its constructor and wraps it in a `MethodHandleSpec`.
- **`addInstance(Class<? super T>, T)`** — register a pre-existing object. Wrapped in an `ExistingInstanceSpec`.
- **`inject(Collection<Class<?>>)`** — triggers the remaining phases. Any types passed here that weren't already registered get auto-registered.

There are only three ways the injector discovers it must instantiate a class: an explicit `addClass` call, a type passed to `inject`, or a constructor parameter of some other class being instantiated.

#### Spec closure

Between configuration and planning, the `specClosure` method discovers the full dependency graph via BFS. Starting from the seed specs, it inspects each class's constructor, enqueues all parameter types, and continues until no new types remain.

This means **you register roots, not leaves**. If `A` depends on `B` depends on `C`, registering `A` is sufficient — `B` and `C` are discovered automatically.

The algorithm uses a neat trick: it pretends there's a single gigantic method whose parameters are all the seed types, then enqueues those "parameters" as the initial work items. This unifies the seeding logic with the recursive discovery logic.

### Phase 2: Planning (`Planner`, 129 lines)

Takes the complete spec map and produces a `List<InjectionStep>` in topological order using DFS. This is where errors are caught:

- **Cycle detection**: Uses `startedPlanning` / `finishedPlanning` sets (the classic white/gray/black algorithm). If `startedPlanning.add(spec)` returns false, we've hit a gray node — there's a cycle. (A TODO notes this should be improved with SCCs for better error messages.)
- **Missing dependencies**: If `specsByClass.get(requestedClass)` returns null, the type can't be satisfied.
- **Topological ordering**: For each `MethodHandleSpec`, all parameters are planned recursively before the spec itself emits an `InstantiateStep`. This post-order traversal guarantees dependencies are constructed before dependents.

`ExistingInstanceSpec` entries need no step — the instance is already in the map.

The plan is a **first-class data structure**. It can be inspected, logged, validated, or modified before any constructor is called. This is unlike Guice, where binding resolution and construction are interleaved.

### Phase 3: Execution (`PlanInterpreter`, 109 lines)

Intentionally trivial. The design principle: no injection-related errors should be detected here. All exceptions during execution are from user-supplied constructor code.

1. Pre-populate an instance map with existing instances.
2. Walk the step list sequentially.
3. For each `InstantiateStep`, resolve parameters from the instance map and call `methodHandle.invokeWithArguments(args)`.
4. Store the result.

The entire execution logic fits in about 50 lines of actual code.

---

## Type System: Specs and Steps

Both hierarchies use sealed interfaces — adding a new variant is a compile-time change that forces all `switch`/`instanceof` sites to be updated.

### Specs (declarative — "what should happen")

```
InjectionSpec (sealed interface)
├── MethodHandleSpec (record)     — "construct by calling this MethodHandle"
│     fields: requestedType, methodHandle, List<ParameterSpec>
└── ExistingInstanceSpec (record)  — "use this pre-existing instance"
      fields: requestedType, instance
```

`ParameterSpec` (record) captures metadata for one constructor parameter: `name` (for diagnostics), `formalType` (the declared type), and `injectableType` (the type used for dependency resolution). Today these are always equal; the split exists for future cases like interface injection.

### Steps (imperative — "how to make it happen")

```
InjectionStep (sealed interface)
└── InstantiateStep (record)  — "invoke the MethodHandle in this MethodHandleSpec"
```

Currently there is only one step type. The sealed hierarchy is designed to grow as new capabilities are added (e.g., proxy creation steps, collection assembly steps).

---

## Constructor Selection

The rules are simple and intentionally unambiguous:

1. **One public non-synthetic constructor** — used automatically. No annotation needed. This is the common case, especially for records.
2. **Multiple public constructors** — exactly one must be annotated `@Inject`. Zero or more than one is an error.
3. **No suitable constructor** — error at spec creation time (during configuration, before planning even begins).

The annotation is `org.elasticsearch.injection.api.Inject` — a clean-room annotation, not `javax.inject.Inject` or `com.google.inject.Inject`. This avoids dependency on or confusion with other DI frameworks.

---

## How It Differs From Other DI Frameworks

| Aspect | Guice | Spring | Dagger | ES Injector |
|--------|-------|--------|--------|-------------|
| Error detection | Lazy (at first use) | Runtime (startup scan) | Compile-time (annotation processor) | Plan-time (after config, before construction) |
| Bytecode generation | Yes (cglib) | Yes (AOP proxies) | Yes (generated factories) | No |
| Binding configuration | DSL in Module classes | XML / annotations / Java config | `@Module`/`@Component` | `addClass`/`addInstance` API |
| Scopes | Singleton, RequestScoped, etc. | singleton, prototype, request, etc. | Via `@Scope` | None (everything is a singleton) |
| Constructor invocation | `Constructor.newInstance()` | `Constructor.newInstance()` | Generated code | `MethodHandle.invokeWithArguments()` |
| Plan as data | No | No | Partial (component graph) | Yes (`List<InjectionStep>`) |
| Provider/Lazy wrapper | `Provider<T>` | `ObjectProvider<T>` | `Lazy<T>`, `Provider<T>` | None |
| Transitive discovery | No (must bind everything) | Yes (classpath scanning) | No (must declare everything) | Yes (BFS from roots) |

### Key differentiators

**MethodHandle over reflection.** All `java.lang.reflect` work happens during spec creation. Execution uses `MethodHandle`, which the JVM can inline and optimize. Benchmarks showed the invokedynamic-based proxy approach matched `final` field performance at 2719M calls/sec.

**No global state.** Each `Injector` instance is independent. No static registry, no thread-local context, no ambient authority. This matters for testing and for the multi-domain future described in the Planner's evolution notes.

**No scopes.** Elasticsearch services are node-level singletons. There's no request scope, no session scope, no prototype scope. The injector doesn't need scope management because the problem domain doesn't have scopes.

**Records as first-class citizens.** Java records work naturally as injectable components — their canonical constructor is the single public constructor, and their components are the dependencies. No annotation needed.

---

## How It Is Used Today

The integration point is in `NodeConstruction.java` (lines 1056–1079). When a plugin's `createComponents()` returns a mix of already-constructed objects and `Class<?>` tokens:

```java
var injector = org.elasticsearch.injection.Injector.create();
injector.addInstances(componentObjects);
addRecordContents(injector, pluginServices);
var resultMap = injector.inject(classes);
```

The `addRecordContents` helper (lines 1432–1441) is worth noting. It reflectively iterates a Record's components and registers each field value with its declared type. The `PluginServiceInstances` record has ~28 fields representing core services (Client, ClusterService, ThreadPool, etc.). Decomposing it into individual registrations lets plugin classes depend on any combination of these services via their constructor parameters — without the injector needing to know about any of them specifically.

This is the bridge between the hand-wired world and the injected world: the record serves as a typed parameter bundle.

---

## Design Decisions

These are choices already made and visible in the code:

- **Constructor injection only.** No field injection, no setter injection. Dependencies are declared in the constructor signature and are therefore visible, immutable, and statically analyzable.
- **No scopes.** Everything is effectively a singleton. The problem domain requires nothing else.
- **Fail before construction.** All errors caught during planning. By the time any constructor runs, the dependency graph is fully validated.
- **Sealed hierarchies.** Controlled extensibility via the compiler, not open polymorphism.
- **One annotation.** `@Inject` on constructors, and only when disambiguation is needed.
- **MethodHandle for execution.** Reflection for configuration only.
- **No binding DSL.** No modules, no `bind(X).to(Y)`, no fluent configuration language. Just `addClass` and `addInstance`.

---

## Prepared Extension Points

The code contains several markers for planned future work:

- **`alreadyProxied` in Planner** — present but unused. Proxy support is planned for late-constructed services (e.g., RestControllers that depend on services created after them).
- **"Evolution note: the intent is to plan one domain/subsystem at a time"** (Planner) — the architecture supports splitting injection across multiple planning rounds, where one plan's outputs become another plan's inputs.
- **"Evolution note: there may be cases where we allow the user to supply a `MethodHandles.Lookup`"** (Injector) — would let non-public constructors participate in injection without requiring everything to be public.
- **`formalType` vs `injectableType` in ParameterSpec** — today always equal, but the split anticipates interface injection where a constructor declares `ServiceInterface` but the injector resolves `ServiceImpl`.

---

## What's Not Yet Implemented

From the planning documents and code TODOs, these features are needed for the full migration:

| Feature | Why it's needed |
|---------|----------------|
| `@AutoInject` build-time scanning | Discover injectable classes without explicit registration |
| Collection injection (`List<T>`) | "Contribute N things" patterns (e.g., 300+ action registrations) |
| Conditional components | Settings/license-gated service creation |
| Permissions/visibility model | "Available to plugins" vs "core-internal" — the biggest open question |
| Override mechanism | Test and serverless component replacement |
| Lifecycle hooks | Start/stop ordering for services |
| Better cycle error messages | Currently just "Cyclic dependency involving X"; should use SCCs and suggest fixes |
| Proxy support | Late-constructed services that depend on not-yet-created objects |

---

## File Inventory

All files are under `server/src/main/java/org/elasticsearch/injection/`.

| File | Lines | Role |
|------|-------|------|
| `package-info.java` | 42 | Documents the three-phase architecture |
| `Injector.java` | 315 | Configuration API, spec closure algorithm |
| `Planner.java` | 129 | Topological sort, cycle detection, plan generation |
| `PlanInterpreter.java` | 109 | Sequential plan execution via MethodHandle |
| `api/Inject.java` | 24 | The sole annotation |
| `spec/InjectionSpec.java` | 14 | Sealed root of spec hierarchy |
| `spec/MethodHandleSpec.java` | 31 | "Construct via MethodHandle" spec |
| `spec/ExistingInstanceSpec.java` | 18 | "Already have an instance" spec |
| `spec/ParameterSpec.java` | 25 | One constructor parameter's metadata |
| `spec/package-info.java` | 26 | Documents spec vs step distinction |
| `step/InjectionStep.java` | 12 | Sealed root of step hierarchy |
| `step/InstantiateStep.java` | 18 | "Call this MethodHandle" step |
| `step/package-info.java` | 16 | Documents execution model |
| **Total** | **779** | |

Test file: `server/src/test/java/org/elasticsearch/injection/InjectorTests.java` (155 lines).
