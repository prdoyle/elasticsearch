# DI Rework: Workstreams

## 1. Inventory of Existing Patterns

Before committing to a design, we need confidence that every existing construction/registration pattern has a clear mapping into the new model. The codebase has several distinct patterns that each need a story:

### a. Guice `@Inject` constructors (~193 classes)

These are the simplest case — classes already declare their dependencies via constructor injection. The new injector needs to handle them, but the mapping is straightforward.

### b. `createComponents` implementations (63 plugins)

The main plugin lifecycle entry point. These vary enormously in complexity:
- **Simple cases:** Create a service, return it. Maps cleanly to `@AutoInject` constructor.
- **Complex cases:** Security.java has 22+ `SetOnce` fields and creates dozens of interdependent services with specific ordering. The new model needs to handle this without `SetOnce`.
- **Conditional creation:** Some plugins conditionally create components based on settings or license state (e.g., XPackPlugin creates `ClusterStateLicenseService` only if not already set).
- **PluginComponentBinding:** Some plugins return `PluginComponentBinding<Interface, Impl>` to expose an interface while hiding the implementation. The new model needs an equivalent.

### c. Plugin interface registration methods

28 plugin interfaces contribute things through methods like:
- `ActionPlugin.getActions()` — ~300 action registrations in ActionModule
- `SearchPlugin.getAggregations()`, `.getQueries()`, etc.
- `AnalysisPlugin.getAnalyzers()`, `.getTokenizers()`, etc.
- `IngestPlugin.getProcessors()`
- `MapperPlugin.getMappers()`

These are all "contribute N things to a registry" patterns. The new model needs a story for whether these become annotated classes discovered at build time, or stay as explicit registration, or something else.

### d. NamedWriteable / NamedXContent registries (58+ plugin contributors)

Serialization-layer registration. Each entry is a `(Category, Name, Reader)` triple. These are pervasive and a different shape from service injection — they're data registrations, not service construction.

### e. SPI / ServiceLoader (40+ endpoints)

Both Elasticsearch-internal SPIs (NamedXContentProvider, PainlessExtension, FeatureSpecification, CliToolProvider) and Lucene SPIs (Codec, DocValuesFormat, KnnVectorsFormat). These have their own discovery mechanism today.

### f. Manual construction in NodeConstruction.java (1856 lines)

The giant orchestration method that creates services in a specific order, wires them together, and feeds them into Guice. Some of these services have complex construction logic (conditionals, settings-driven branching) that may not map cleanly to declarative DI.

### g. ExtensiblePlugin / inter-plugin dependencies

The current mechanism where one plugin can load extensions from another. This needs a first-class replacement in the new model.

### h. Test overrides (NodeServiceProvider + ad-hoc patterns)

Tests swap service implementations via `NodeServiceProvider` factory methods and by creating plugins with different behavior. The new model needs a clean override mechanism.

---

## 2. Core Injector Design

The injector itself — what's built, what's missing, and the design decisions that need to be locked down.

### Built
- ProxyFactory with invokedynamic (zero-overhead proxies for late-constructed services)
- Build-time annotation scanning infrastructure (ClassScanner, NamedComponentScanner)

### Needs design resolution
- **Permissions/visibility model** — The biggest open question. How does a component declare "I'm available to plugins" vs "I'm core-internal"? Build-time or runtime enforcement? Annotation-based? Module-based?
- **Collection injection** — How do "contribute N things" patterns work? If 30 plugins each contribute actions, how does ActionModule (or its replacement) receive them all?
- **Conditional components** — Components that only exist based on settings, license, or feature flags.
- **Ordering / lifecycle** — Some components need to start/stop in a specific order. How is this expressed?
- **Override mechanism** — How do tests and serverless replace components? Child injectors? Override annotations? Something else?
- **Error messages** — The principle says "someone who doesn't know DI could learn it simply by doing it wrong." What does the error model look like concretely?

---

## 3. Build-Time Infrastructure

The tooling that makes DI work at compile/build time rather than runtime:

- **`@AutoInject` scanning** — Discover injectable classes and generate manifests (partially built, on feature branches)
- **Bundle manifests** — Per-plugin metadata files describing what a plugin provides and requires
- **Wiring validation** — Pre-construction verification that all dependencies can be satisfied, no cycles exist, and visibility rules are respected
- **IDE integration** — Ensuring annotations prevent false "unused" warnings and provide navigation

---

## 4. Migration Strategy

The phased approach to moving from the old world to the new.

**Phase question:** Do we migrate vertically (one plugin end-to-end) or horizontally (one pattern across all plugins)?

The ON-week approach was vertical — fully convert the Downsample plugin as a proof of concept. But at scale, horizontal phases may work better:

- **Phase A:** RestHandler injection (already prototyped) — proves the proxy mechanism works for late-constructed services
- **Phase B:** TransportAction injection — eliminates ActionModule's MapBinder, the biggest Guice multi-binding user
- **Phase C:** Simple `createComponents` conversions — plugins that just construct and return services
- **Phase D:** Complex `createComponents` conversions — Security, ML, and other plugins with ordering/conditional logic
- **Phase E:** Registry replacement — NamedWriteable, NamedXContent, analysis, etc.
- **Phase F:** Guice removal — delete the 111 files in `injection/guice/`

Each phase needs a coexistence story — the old and new systems running side by side during transition.

---

## 5. Registry & Discovery Consolidation

Replacing the current grab-bag of registration mechanisms with a unified annotation-driven system:

- **NamedWriteable entries** — Can `@NamedWriteable(category=Foo.class, name="bar")` on the class itself replace central registry methods?
- **NamedXContent entries** — Similar annotation approach?
- **Action registration** — Can `@Action` or similar on TransportAction subclasses replace the 300-line registration block in ActionModule?
- **Analysis components** — Can `@Analyzer`, `@TokenFilter` etc. replace AnalysisPlugin methods?
- **SPI replacement** — Can the build-time scanner subsume both Java SPI and Lucene SPI?

The key design question: these aren't all "service singletons." NamedWriteable entries are factory registrations (category + name → reader function). Analysis components are factories. The DI model needs to handle this shape, not just singleton services.

---

## 6. Plugin Lifecycle Simplification

The end goal is eliminating the Plugin base class. This requires:

- Replacing `createComponents` with annotated constructors
- Replacing plugin interface methods with annotation discovery
- Replacing `ExtensiblePlugin` with DI-native extension points
- Defining what a "bundle" looks like — just a JAR with annotated classes and a manifest?
- Handling plugin settings (currently `getSettings()`, `getSettingsFilter()`)
- Handling bootstrap checks, index module hooks, and other lifecycle callbacks

---

## 7. Testing Infrastructure

- Replace `NodeServiceProvider` with DI override mechanism
- Ensure test plugins can be expressed as annotated bundles
- Support component replacement in integration tests (e.g., swap real transport for mock)
- Serverless overrides use the same mechanism as test overrides

---

## Design Stress Tests

The patterns that will stress the design the most:

1. **Security plugin** — 22 SetOnce fields, complex ordering, inter-service dependencies, ExtensiblePlugin usage
2. **ActionModule** — 300+ action registrations via MapBinder
3. **SearchModule** — Aggregation, query, and suggester registries with plugin contributions
4. **NamedWriteable** — Not a service, but a factory registration pattern across 58 plugins
5. **Conditional components** — Settings/license-gated service creation

If the design handles these five, everything else likely falls out naturally.

---

## Suggested Starting Point

Start with **Workstream 1 (Inventory)** as a concrete deliverable — a document that catalogs every pattern in the codebase with its proposed new-world equivalent. This forces the design questions in Workstream 2 to be answered against real code, not in the abstract.
