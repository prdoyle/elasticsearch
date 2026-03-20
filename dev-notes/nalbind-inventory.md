# Nalbind Branch Inventory

An inventory of all branches containing "nalbind", describing what each contains and how they relate to each other. "Nalbind" was the internal codename for the custom DI injector during prototyping; the name has since been retired.

## Branch Summary

| Branch | Tip Date | Commits | Lineage | Location | Key Features |
|--------|----------|---------|---------|----------|--------------|
| `try4-nalbind` | 2024-06-10 | 33 | 1 (big prototype) | `libs/nalbind/` | Proxy bytecode gen, ObjectGraph, ClassFinder, ActionModule injection |
| `try1-nalbind` | 2024-06-17 | 33 | 1 (rebase of try4) | `libs/nalbind/` | Same content as try4, different base commit |
| `try2-nalbind` | 2024-07-02 | 39 | 1 (extends try1) | `libs/nalbind/` | Adds stereotypes, custom `MethodHandles.Lookup` |
| `try3-nalbind` | 2024-07-16 | 42 | 1 (extends try2) | `libs/nalbind/` | Adds `List` injection, `@InjectableTo`, removes `@Injected` |
| `old-nalbind` | 2024-07-29 | 35 | 1 (extends try4) | `libs/nalbind/` | Adds "Nalbind plugin" hacking, auto-injection of plugins |
| `try5-nalbind` | 2024-07-23 | 5 | 2 (fresh restart) | `libs/nalbind/` | Clean rewrite: three-phase architecture (Planner/Interpreter), proxy pool, list proxy steps |
| `try6-nalbind` | 2024-07-25 | 13 | 2 (extends try5) | `libs/nalbind/` | Adds RestHandler injection, plugin AutoInjectable scan, Downsample injection |
| `nalbind` | 2024-08-02 | 4 | 2 (minimal) | `libs/nalbind/` | Minimal branch: core injector + Downsample injection only |
| `try7-nalbind` | 2024-08-02 | 16 | 2 (extends nalbind) | `libs/nalbind/` | Re-adds RestHandler injection, AutoInjectable scanning from try6's direction |
| `try9-nalbind` | 2024-08-12 | 3 | 3 (package move) | `server/.../injection/` | Moved injector from `libs/nalbind/` into `server`; minimal 3-commit version |
| `try8-nalbind` | 2024-08-12 | 42 | 3 (extends try9) | mixed | Many reverts unwinding complexity, then re-adds features + AutoInjectable scan |
| `try10-nalbind` | 2024-08-13 | 7 | 3 (extends ~try9 base) | `server/.../injection/` | Adds/reverts subtype handling experiments |

---

## Lineage 1: The Big Prototype (June–July 2024)

**Branches: `try4-nalbind` → `try1-nalbind` → `try2-nalbind` → `try3-nalbind`; also `old-nalbind`**

The original prototype, living in `libs/nalbind/` as a separate Gradle module with its own `module-info.java`. This was a feature-rich implementation that included:

- **Bytecode proxy generation** — `ProxyBytecodeGenerator`, `ProxyFactoryImpl`, invokedynamic-based proxies (benchmarked at zero overhead)
- **ObjectGraph** — a separate class from `Injector` managing the dependency graph
- **ClassFinder** — runtime classpath scanning for injectable classes
- **ActionModule injection** — RestHandler and TransportAction integration with Elasticsearch's action framework
- **AutoInjectionScanner** — build-time ASM scanning for `@AutoInjectable` classes (in `libs/plugin-scanner/`)
- **Multiple annotations** — `@Inject`, `@Injected`, `@InjectableSingleton`, `@AutoInjectable`, `@Actual`
- **Rich spec hierarchy** — `InjectionSpec`, `MethodHandleSpec`, `ExistingInstanceSpec`, `AliasSpec`, `AmbiguousSpec`, `DistinctInstanceSpec`, `UnambiguousSpec`
- **Gradle integration** — `AutoInjectionTask` for build-time scanning

**`try4` and `try1`** have identical commit messages (33 commits each, e.g., "First attempt at Nalbind dependency injector" through "Keep all injected instances") but different SHAs — they are rebases of the same work onto different base commits.

**`try2`** extends try1 with 6 additional commits: stereotypes, a specific `MethodHandles.Lookup` (rather than `publicLookup()`), and WIP v2 integration work.

**`try3`** extends try2 with 3 more commits: `List` injection ("Got List injection kinda working"), removal of `@Injected`, and `@InjectableTo` — an annotation for directing where injection should target.

**`old-nalbind`** (remote-only) branches from try4's tip and adds 2 commits: "Nalbind plugin WIP" and "Hacking auto-injection of plugins."

### Evolution in this lineage

```
try4 ──→ old-nalbind (+2 commits: plugin auto-injection)
  │
  └──→ try1 (rebase) ──→ try2 (+6: stereotypes, Lookup) ──→ try3 (+3: List injection, InjectableTo)
```

---

## Lineage 2: The Clean Rewrite (July–August 2024)

**Branches: `try5-nalbind` → `try6-nalbind` → `nalbind` → `try7-nalbind`**

`try5-nalbind` is a **complete restart** with only 5 commits, beginning with "Initial nalbind." This rewrite introduced the three-phase architecture that survives in the current codebase:

- **Planner** — topological sort and cycle detection (separate from Injector)
- **Interpreter/PlanInterpreter** — executes the plan as a step list
- **Step hierarchy** — `InjectionStep`, `InstantiateStep`, `ListProxyCreateStep`, `ListProxyResolveStep`, `RollUpStep`, `InstanceSupplyingStep`
- **ProxyPool** — manages proxy creation for `List<T>` injection
- **Structured exceptions** — `CyclicDependencyException`, `InjectionConfigurationException`, `InjectionExecutionException`, `UnresolvedProxyException`
- **Same spec hierarchy** as Lineage 1 — `AliasSpec`, `AmbiguousSpec`, `UnambiguousSpec`, etc.

The key architectural shift: Lineage 1 had an `ObjectGraph` class interleaving resolution with construction. Lineage 2 cleanly separates configuration → planning → execution.

**`try6-nalbind`** extends try5 with 8 more commits, adding RestHandler injection in ActionModule, plugin AutoInjectable scanning, and Downsample injection — proving the framework works end-to-end.

**`nalbind`** is a **minimal 4-commit branch** that shares try5/try6's architecture but excludes the ActionModule and RestHandler integration. Just: "Initial nalbind", security.policy, "Allow createComponents to return classes", "Downsample injection." This appears to be **the version that was stripped down for merging** — it has the full injector (with proxy pool, list proxy steps, etc.) but doesn't wire into ActionModule.

**`try7-nalbind`** extends `nalbind` (nalbind is a direct ancestor) by reverting some commits and then rebuilding RestHandler injection and AutoInjectable scanning — effectively moving back toward try6's scope from nalbind's minimal base.

### Evolution in this lineage

```
try5 (clean rewrite, 5 commits)
  ├──→ try6 (+8: RestHandler injection, Downsample)
  └──→ nalbind (minimal 4-commit version for merging)
         └──→ try7 (+12: re-adds RestHandler, AutoInjectable scan)
```

---

## Lineage 3: The Package Move (August 2024)

**Branches: `try9-nalbind` → `try8-nalbind`; also `try10-nalbind`**

`try9-nalbind` moves the injector from `libs/nalbind/` into `server/src/main/java/org/elasticsearch/injection/` — the location it occupies today. Only 3 commits: "Initial new injector", "Allow createComponents to return classes", "Downsample injection."

Despite being minimal in commit count, try9 retains a rich feature set: `AliasSpec`, `AmbiguousSpec`, `UnambiguousSpec`, `SeedSpec`, `RollUpStep`, `InstanceSupplyingStep`, `PluginServiceInstances` record, and custom exception types.

**`try8-nalbind`** extends try9 (try9 is a direct ancestor) with 39 additional commits — mostly **reverts** unwinding from a more complex state. The commit history shows a sequence like "Revert 'Remove support for lists'", "Revert 'Move libs/nalbind to libs/injection'", "Revert 'Rename nalbind module'" — suggesting this branch was experimenting with how much to strip away. It then re-adds features: AutoInjectable scanning, RestHandler injection, Downsample AutoInjectable hack.

**`try10-nalbind`** shares a similar base to try9 (same merge-base) with 7 commits. It adds and then reverts subtype handling and logger casing — another experiment in what the minimal viable set of features should be.

### Evolution in this lineage

```
try9 (3 commits, package move to server/injection/)
  ├──→ try8 (+39: many reverts, then re-adds AutoInjectable, RestHandler)
  └──→ try10 (+4: subtype handling experiments, reverted)
```

---

## Overall Narrative

The branches tell a story of progressive simplification:

1. **Lineage 1** (try4/try1 → try3, June–July) was the **full-featured prototype** — bytecode proxies, ObjectGraph, ClassFinder, multiple annotations, ActionModule integration. It proved the concept works but was too large to merge.

2. **Lineage 2** (try5 → try6, late July) was a **clean architectural rewrite** that introduced the three-phase pipeline (configure → plan → execute). `try6` was likely the **"very capable prototype"** — it had the clean architecture plus RestHandler injection and Downsample working end-to-end. The `nalbind` branch then stripped it to the minimum for merging.

3. **Lineage 3** (try9/try8/try10, August) **moved the code into `server/`** and experimented with how much of the feature set to keep. The many reverts on try8 show the process of finding the right minimal set.

4. **`di/squeeze`** (current branch) represents the final result: the injector lives in `server/.../injection/`, with only `MethodHandleSpec` and `ExistingInstanceSpec` (no `AliasSpec`, `AmbiguousSpec`, etc.), only `InstantiateStep` (no list proxy steps, no rollup step), no proxy pool, no custom exceptions, and no AutoInjectable scanning.

### Features removed during simplification

These features existed in the prototypes but were removed before merging:

| Feature | Present in | Removed by |
|---------|-----------|------------|
| `ProxyBytecodeGenerator` / invokedynamic proxies | Lineage 1 | Lineage 2 rewrite |
| `ObjectGraph` (separate from Injector) | Lineage 1 | Lineage 2 rewrite |
| `ClassFinder` (runtime classpath scanning) | Lineage 1 | Lineage 2 rewrite |
| `AliasSpec`, `AmbiguousSpec`, `UnambiguousSpec` | Lineages 1, 2, 3 | di/squeeze |
| `ListProxyCreateStep`, `ListProxyResolveStep` | Lineages 2, 3 | di/squeeze |
| `RollUpStep`, `InstanceSupplyingStep` | Lineages 2, 3 | di/squeeze |
| `ProxyPool` | Lineages 2, 3 | di/squeeze |
| `@AutoInjectable`, `@InjectableSingleton`, `@Injected` | Various | Progressive removal |
| `@InjectableTo` | try3 only | Not carried forward |
| `InjectionModifiers` | Lineages 2, 3 | di/squeeze |
| Custom exception types | Lineages 2, 3 | di/squeeze |
| `AutoInjectionScanner` | Most branches | di/squeeze (for now) |
| `SeedSpec` | Lineage 3 | di/squeeze |
| ActionModule / RestHandler integration | try1–try3, try6, try7, try8 | di/squeeze (for now) |

Many of these features are expected to be re-introduced as the DI migration progresses — they were removed to keep the initial merge small, not because they were wrong.

---

## Glossary

Terms and types that appear across the prototype branches.

### Annotations

- **`@Inject`** — Marks which constructor the injector should call when a class has multiple public constructors. Survived into the current codebase.

- **`@Actual`** — Placed on a constructor parameter to indicate that the injected value must be the real object, not a proxy. Creates a hard initialization-ordering constraint. Without it, the injector could supply a lazy proxy to break circular dependencies.

- **`@AutoInjectable`** — Marks a class for discovery by the build-time annotation scanner. Classes with this annotation would be automatically registered with the injector without explicit `addClass` calls.

- **`@InjectableSingleton`** — An early annotation (Lineage 1) whose role was later subsumed by other mechanisms.

- **`@Injected`** — An early annotation (Lineage 1) that was explicitly removed in try3. Its purpose was replaced by the constructor-based discovery model.

- **`@InjectableTo`** — A visibility/permissions annotation (try3 only). Declares which classes a component is allowed to be injected into. `@InjectableTo(B.class)` on class A means A (or any subtype) can be injected into B (or any subtype). An early attempt at the permissions model.

- **`@Service`** — Equivalent to `@InjectableTo(Object.class)` — marks a class as injectable into anything. A shorthand for "universally available."

### Stereotypes

- **`Stereotype`** — An enum (try2) inspired by Spring Boot stereotypes, describing the role an injected object plays. Two values:
  - `COMPONENT` — created by DI but not itself injectable into other components. Components cannot depend on each other.
  - `SERVICE` — can both inject and be injected into other objects.

  Injection was allowed only if the stereotype's `canInject` flag was true for either the injector or the injectee. This was an early approach to controlling the dependency graph's shape — preventing a flat "everything depends on everything" topology.

### Spec types

- **`InjectionSpec`** — Sealed interface at the root of the spec hierarchy. Describes how a type should be injected. Survived into the current codebase.

- **`MethodHandleSpec`** — "Construct this type by calling this MethodHandle." Survived into the current codebase.

- **`ExistingInstanceSpec`** — "Use this pre-existing instance." Survived into the current codebase.

- **`UnambiguousSpec`** — Sealed sub-interface of `InjectionSpec` representing specs where exactly one injection strategy is known. Permits `AliasSpec`, `ExistingInstanceSpec`, `MethodHandleSpec`. Removed during simplification.

- **`AmbiguousSpec`** — When multiple ways exist to satisfy a type (e.g., two classes both implement an interface), this tree structure records all candidates. It's only an error if something actually tries to inject the ambiguous type. Structured as a binary tree for constant-time construction. Removed during simplification.

- **`AliasSpec`** — "When someone asks for type A, give them the instance of subtype B instead." Handles supertype→subtype redirection. Removed during simplification (the current codebase handles this differently in `Injector.specClosure`).

- **`DistinctInstanceSpec`** — Sealed sub-interface (Lineage 1) marking specs that describe a "real" injectable object (as opposed to an `AliasSpec` redirect). Permits `MethodHandleSpec` and `ExistingInstanceSpec`. Also declared `reportInjectedMethods()`. Removed during simplification.

- **`SeedSpec`** — Sealed sub-interface (Lineage 3) distinguishing specs that came directly from the user (`addClass`/`addInstance`) from those inferred automatically during transitive closure. Removed during simplification.

- **`ParameterSpec`** — Metadata for one constructor parameter. Survived into the current codebase.

- **`InjectionModifiers`** — An enum of flags that modify how a parameter is injected. Two values:
  - `ACTUAL` — the parameter must receive the real object, not a proxy (corresponds to `@Actual`).
  - `LIST` — inject a `List<T>` of all instances rather than a single object.

  Removed during simplification.

### Step types

- **`InjectionStep`** — Sealed interface at the root of the step hierarchy. One operation in the execution plan. Survived into the current codebase.

- **`InstantiateStep`** — "Call this MethodHandle to construct an object." Survived into the current codebase.

- **`InstanceSupplyingStep`** — Sealed sub-interface for steps that make an object available for subsequent steps to look up. Permits `InstantiateStep` and `RollUpStep`. Removed during simplification.

- **`RollUpStep`** — "Associate all objects of subtype B with supertype A." Handles inheritance in the instance map — after this step, requesting type A finds instances of B. Not a permanent link; more like a metadata copy. Removed during simplification.

- **`ListProxyCreateStep`** — "Create a proxy `List<T>` for elements of type T." The proxy throws if used before resolution. Enables injecting a list before all contributors have been constructed. Removed during simplification.

- **`ListProxyResolveStep`** — "Resolve the proxy list for type T with the actual collected instances." After this step, the proxy becomes usable. Any subsequent `InstanceSupplyingStep` for the same type is an error (too late to add to the list). Removed during simplification.

### Other types

- **`ObjectGraph`** — (Lineage 1) The product of injection: a pool of singleton objects. Held a `Map<Class<?>, List<Object>>` of instances. Callers retrieved objects via `getInstance(Class<T>)`. Replaced by the Planner/PlanInterpreter architecture in Lineage 2.

- **`ProxyPool`** — (Lineages 2, 3) Manages proxy `List<T>` instances whose lifetime spans multiple injection plans. Uses `AtomicReference`-backed `AbstractList` proxies that delegate to the real list once resolved. Throws `UnresolvedProxyException` if accessed before resolution. Removed during simplification.

- **`ProxyBytecodeGenerator` / `ProxyBytecodeGeneratorImpl`** — (Lineage 1) Generated proxy classes via ASM bytecode generation. The invokedynamic-based approach that benchmarked at 2719M calls/sec. Replaced by the simpler `AbstractList`-based proxy approach in Lineage 2.

- **`AutoInjectionScanner`** — Build-time ASM scanner that discovers `@AutoInjectable` classes on the classpath and produces a manifest. Companion to the existing `NamedComponentScanner`. Present on most branches; removed from di/squeeze for now.

- **Custom exceptions** — `CyclicDependencyException`, `InjectionConfigurationException`, `InjectionExecutionException`, `UnresolvedProxyException`. Replaced by generic `IllegalStateException` in the current codebase.
