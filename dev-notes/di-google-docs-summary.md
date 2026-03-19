# Dependency Injection: Google Docs Summary

Summary of all DI-related Google Docs for the Elasticsearch team, synthesized into a narrative of how thinking evolved from 2020 through 2025.

## Source Documents

| Doc | Owner | Last Modified | Link |
|-----|-------|---------------|------|
| The Guice is Loose | Ryan Ernst | 2020-05-28 | [link](https://docs.google.com/document/d/1p6ne0eEpLB37NNYrDigzs5qmNYHPsvz6Wh5lfQrAFEY/edit) |
| Ryan's Plugin API dump | Ryan Ernst | 2022-07-30 | [link](https://docs.google.com/document/d/1UogitSQ3LUUrp0ZTgNfPbK5DZcN9PfmixcSYF5_5ANc/edit) |
| Stable Analysis Plugin API | Chris Hegarty | 2022-07-29 | [link](https://docs.google.com/document/d/1REr45kglcfMP9mkasuThmOhRJ78IxJ0FhIVGeMrhHYM/edit) |
| Inter-plugin API | Lorenzo Dematte | 2024-09-16 | [link](https://docs.google.com/document/d/15MGxBeymXYuERo8TB7gFVZHtIau-1Zwn0oljsFLl4_Q/edit) |
| DI Principles | Ryan Ernst | 2025-05-16 | [link](https://docs.google.com/document/d/1G02H-g59VxKQmpOHuEGjvV-coz__yRXf-2g5BdzN1xM/edit) |
| Dependency Injection: first steps toward a plan | Patrick Doyle | 2025-03-10 | [link](https://docs.google.com/document/d/1C7tWwQOwk34YqSnIoCPVe3tOrkOmo2VrihXsinafXGg/edit) |
| Dependency Injection - Spacetime | Ryan Ernst | 2025-03-27 | [link](https://docs.google.com/document/d/1IR0zS3Z0JJnoTB2J1YoT9jQotBkEzpxnl3FzrgTPgEc/edit) |

---

## Phase 1: The Guice Problem (2020)

"The Guice is Loose" (Ryan Ernst, May 2020) documented the pain points with Guice, which had been in ES since the early days:

- **Circular dependencies** hidden until runtime
- **`SetOnce`** used pervasively in plugins to hold references between different lifecycle method calls, creating fragile ordering dependencies
- **Static workarounds** (e.g. `XPackPlugin.sslService`, `licenseState`) to share services across plugins
- **`createComponents`** growing unmanageable — too many parameters, wrong ordering (e.g. `Supplier<RepositoriesService>` because initialization order forced it)
- Tests initialized plugins differently from production

The doc weighed "explicit" vs "magic" worlds. The conclusion at the time was skeptical of DI — the team wanted to move *away* from Guice, not toward better DI. The main idea was an `@Exported` annotation to control visibility, but no concrete DI framework was proposed.

## Phase 2: Stable Plugin API & Annotation Discovery (2022)

Three docs from this era — "Ryan's Plugin API dump", "Stable Analysis Plugin API" (Chris Hegarty/Przemek Gomulka), and related work — focused on the *stable plugin API*, not DI per se, but laid critical groundwork.

Three PoCs were explored for plugin component registration:

- **PoC1** (fine-grain SPI): Too much boilerplate — separate META-INF/services files per interface
- **PoC2** (coarse-grain SPI + settings injection via dynamic proxies): Workable but still verbose
- **PoC3** (annotation-based `@Factory`/`@NamedComponent`): **Winner** — consistent developer experience, ASM scanning at startup

Key decisions made:

- Annotations over SPI for both component declaration and settings injection
- `@ExtensibleComponent` marks types available for extension (like `uses` in module-info)
- `@NamedComponent` marks implementations (like `provides` in module-info)
- Build-time pre-computation of annotated classes to avoid startup scan cost
- Stable plugins loaded in synthetic Uber modules with restricted classloader visibility
- New `stable-plugin-descriptor.properties` file format

This was fundamentally about plugin isolation and API stability, not internal DI. But the annotation scanning infrastructure and the thinking about "what can see what" became the foundation for what came later.

## Phase 3: Inter-plugin Dependencies (2024)

"Inter-plugin API" (Lorenzo Dematte, Sep 2024) tackled how one plugin can consume services from another without direct dependencies. Three options were evaluated:

1. **Raw TransportActions** — simple but pushed complexity to consumers
2. **SPI-based** — explicit, leveraged Java modules, but implementations had fixed constructors
3. **Extended ExtensiblePlugin** — more control over creation, but initialization ordering issues (`createApi` called way before `createComponents`)

All three options kept bumping into the same fundamental problem: the lack of a proper dependency injection system meant initialization ordering was manual, fragile, and couldn't express cross-plugin dependencies cleanly. This work helped crystallize the realization that ES needed real DI.

## Phase 4: Accepting DI & the custom injector (Late 2024 → 2025)

The team accepted that DI — done right, tailored to ES — is the way forward. Work began on a custom injector; the original 2024 proof-of-concept was referred to internally as "Nalbind." That name is historical only — ongoing and future work uses neutral terms (e.g. custom Elasticsearch DI, the injector) and does not use "Nalbind" as a product name.

"DI Principles" (Ryan Ernst, updated through May 2025) codified the design philosophy:

1. **Singletons at node level** — services are singletons, or singleton factories for lazy construction
2. **Fail before construction** — all injection errors detected before any object is created
3. **No deep construction chains** — design for parallelizable startup (even if not parallelized yet)
4. **No passing objects just to register** — e.g. MetricRegistry shouldn't need to be threaded through constructors
5. **Inverted dependencies supported declaratively** — when the dependency direction is opposite to the reference direction
6. **No boilerplate** — no Provider wrapper classes
7. **IDE-friendly** — annotations prevent "dead code" false positives; `@Inject` constructors are clearly marked
8. **Error messages teach DI** — "someone who doesn't know DI could learn it simply by doing it wrong and following the advice"
9. **Explicit visibility** — core can depend on anything; plugins can only access components explicitly marked as available
10. **No duplicate types** — duplicate component types fail startup
11. **Test overrides allowed** — tests (and serverless) can replace component implementations
12. **Extension points** — components declare interfaces that collect implementations from plugins

## Phase 5: The ON-Week Sprint & Current Design (March 2025)

"Dependency Injection: first steps toward a plan" (Patrick Doyle) and "Dependency Injection - Spacetime" (Ryan Ernst, ON-week Mar 24-28, 2025) represent the latest concrete work.

### Current architecture dimensions

| Dimension | Status | Notes |
|-----------|--------|-------|
| **@AutoInject scan** | Nearly ready to merge | Build-time annotation scanning to discover injectable components |
| **Proxies** | Simplifying | Moving away from ASM; will proxy only a small fixed set of interfaces (e.g. List). Needed for late-constructed objects like RestController |
| **Permissions** | Critical path, in progress | The key unsolved problem — the injector currently lets anything inject anything (same as Guice). Need a "simple, clean permission system that is sufficiently expressive" |
| **Injecting from statics** | Planned | Scrape injectable values from static fields/annotations (e.g. Settings constants) |

### ON-week goals and work

The ON-week goal was to demonstrate DI value through a concrete example: fully remove the Plugin class for the Downsample plugin, showing:

- How `createComponents` implementations can be replaced with injection
- How annotation-based discovery eliminates boilerplate registration
- How much cleaner a real-world plugin looks

Work was split:

- **Alexey**: executor builder injection
- **Patrick**: REST handler injection + proxy objects for late-constructed services
- **Ryan + John**: persistent task executor injection
- **Ryan**: bundle manifest for injection metadata

### Broader ideas being explored

1. **Replace registries with annotation discovery** — NamedWriteable, NamedXContent, and other registries could be discovered via annotations on the classes themselves, eliminating central registry methods
2. **Discover constants** — Settings could be collected via annotations rather than centrally referenced
3. **Replace both Java SPI and Lucene SPI** — both have painful boilerplate and poor error feedback; DI can subsume them
4. **Overridable injector for tests and serverless** — a unified mechanism for replacing implementations, instead of the current kludgy `NodeServiceProvider` (tests) and SPI-replacement-module approach (serverless)

---

## Summary of Latest Design

The current direction is a custom, annotation-driven DI framework for Elasticsearch that:

- Uses **build-time ASM scanning** to discover `@AutoInject`/`@Factory`/`@NamedComponent` annotated classes and pre-compute a manifest
- **Validates all wiring before construction** — no runtime surprises
- Replaces **`createComponents`** as the primary plugin lifecycle method — components are just annotated classes with `@Inject` constructors
- Eventually **eliminates the Plugin base class entirely** — plugins become collections of annotated components discovered from the bundle manifest
- Introduces a **permissions/visibility layer** (still being designed) to control what plugins can inject vs. what's core-private
- Provides a **clean override mechanism** for tests and serverless, replacing `NodeServiceProvider` hacks and module-swapping
- Subsumes **SPI, registries, and manual wiring** into a single declarative system

The biggest remaining open question is the **permissions model** — how to express "this component is available to plugins" vs "this is internal to core" in a way that's simple, expressive, and catches violations at build time rather than runtime.
