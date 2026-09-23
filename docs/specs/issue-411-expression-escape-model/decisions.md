# Decisions — Four-Tier Expression Escape Model

## D1: Expression default wiring — registry constructor initialization

**Choice:** `DefaultExpressionEngineRegistry` constructor registers defaults: CONDITION→"mvel", TRANSFORM→"jq", FILTER→"jq". Both Quarkus and Spring get defaults automatically via the core POJO constructor. Overridable via subsequent `registerDefault()` calls.
**Alternatives:**
- @Startup bean in expression/ — framework-specific, requires separate Spring equivalent
- platform/ module — couples platform to expression internals it doesn't own
- Config-driven (application.properties) — #391 D4 explicitly chose convention over configuration
**Rationale:** Framework-neutral. Both DI containers construct the registry via the core POJO; defaults come for free. No @Startup/@PostConstruct needed. More testable — unit tests get defaults without CDI.
**Trade-offs:** Defaults are baked into the registry class rather than wired externally. Acceptable — these conventions rarely change, and `registerDefault()` allows override.
**Sources:** ExpressionEngineRegistry.registerDefault() SPI, #391 D4 (convention over config), DefaultExpressionEngineRegistry
**Exploration:** quick
**Revised from:** R1-07 — reviewer correctly identified that constructor initialization is framework-neutral and eliminates the need for framework-specific startup beans.
**Status:** revised

## D2: InvokeDirective data model — yaml-core record

**Choice:** `InvokeDirective` record in `io.casehub.yaml.core.orchestration` with `parse(Object)` factory following the ForEachDirective sealed-type pattern. Parses `Bean::method` syntax into bean class name + method name. Arguments as a list of expression strings evaluated at invocation time. Zero-dep — just parsed data.
**Variable resolution ordering:** Variables are always resolved before `InvokeDirective.parse()`. The VariableResolver pre-processing step resolves `${order}` to its value (e.g., a class name string) before the directive parser sees it. `InvokeDirective.parse()` always receives fully-resolved strings. This matches how VariableResolver works everywhere else — it's a pre-processing step, not interleaved with parsing.
**Alternatives:**
- platform-api — closer to CDI but breaks the pattern (ComputeBlock is already in yaml-core). Directive data models belong where YAML parsing happens.
**Rationale:** Follows ForEachDirective's `parse(Object)` pattern — handles both shorthand (`"Bean::method"` string) and full form (`{bean: ..., method: ..., args: [...]}`). yaml-core owns the data model (what was declared), consuming layer owns the runtime (CDI resolution + invocation).
**Trade-offs:** Bean class name is a string at this layer — no compile-time verification. Acceptable — same as ComputeBlock's engine name. The `::` separator is unambiguous after variable resolution since resolved values are class names (no `::` in Java class names).
**Depends on:** #391 D1 (parse(Object) factory pattern — ForEachDirective, not ComputeBlock which uses parse(Map, String))
**Sources:** ForEachDirective sealed type, ComputeBlock record, #391 D14 (four-tier model)
**Exploration:** quick
**Revised from:** R1-02 — corrected pattern attribution to ForEachDirective. R1-08 — added explicit variable resolution ordering.
**Status:** revised

## D3: Bean invoke runtime — SPI in platform-api with primitive parameters

**Choice:** New `BeanInvoker` SPI in platform-api: `Object invoke(String beanClassName, String methodName, Object... args)`. Takes primitive types only — no yaml-core imports. The caller (in expression/ or scenario runner) maps `InvokeDirective` fields to primitives before calling the SPI. `@DefaultBean` no-op in platform/ throws UnsupportedOperationException. Implementation in expression/ uses CDI `BeanManager` to resolve the bean by class name, find the method by name, and invoke it on the managed instance (with interceptors, transactions, etc.). Method resolution uses parameter-count matching; varargs methods match any arg count ≥ (paramCount - 1), with fixed-arity preferred over varargs on ambiguity (matching Java's own resolution).
**Security model:** BeanInvoker enforces an `InvocationPolicy` SPI (platform-api): `boolean isAllowed(String beanClassName, String methodName)`. `@DefaultBean` implementation: `AllowListInvocationPolicy` configured via `casehub.expression.invoke.allowed-packages` (comma-separated package prefixes). When no packages configured, defaults to the project's own root package (fail-closed — not unrestricted). This is the first explicit invocation restriction in the expression layer; expression engines (MVEL, JQ) currently have no sandboxing but should eventually use the same pattern.
**Alternatives:**
- SPI taking InvokeDirective — creates platform-api → yaml-core dependency, violating the zero-dep boundary rule
- New invoke/ module — separate module for a single class, overly granular
- Concrete class in expression/ with no SPI — consuming modules must depend on expression/ directly
**Rationale:** Follows the established platform pattern: SPI in platform-api (with primitive params only), @DefaultBean no-op in platform/, real impl displaces in expression/. platform-api's zero-dep constraint means all SPI method signatures use only JDK types.
**Trade-offs:** Caller must destructure InvokeDirective before calling invoke(). One extra line at the call site — acceptable for maintaining the boundary.
**Depends on:** D2 (InvokeDirective data model — caller destructures before calling SPI)
**Sources:** ExpressionEngine/ExpressionEngineRegistry SPI pattern (String-based params), platform-api zero-dep constraint (CLAUDE.md), CDI BeanManager API, #391 D14 (Tier 3 bean invoke)
**Exploration:** quick
**Revised from:** R1-01 — changed SPI to primitive params, no yaml-core types in platform-api. R1-04 — added explicit security assumption and optional package allow-list. R1-09 — added varargs handling.
**Status:** revised

## D4: @ScenarioAction — annotation in platform-api, CDI discovery at startup

**Choice:** `@ScenarioAction(name)` annotation in platform-api (pure marker, no yaml-core imports). `ActionRegistry` and `ActionHandle` in yaml-core's orchestration package alongside ScenarioScope — ActionHandle.invoke(ScenarioScope, Map) requires ScenarioScope, so they must be co-located. At startup, a CDI bean scans all beans for methods annotated `@ScenarioAction`, builds the ActionRegistry (name → method handle). YAML `action: name` resolves from the registry.
**ScenarioScope coupling is intentional:** Tier 4 is for stateful, scope-aware logic that needs spawn, childScope, channels — the full ScenarioScope surface. If an action doesn't need scope access, it belongs in Tier 3 (invoke:) instead. The tier separation IS the abstraction boundary — a slim ActionContext would duplicate ScenarioScope's API surface and add an adapter for no benefit.
**Alternatives:**
- Interface-based (ScenarioAction.execute()) — one class per action, heavy for simple cases
- APT code generation — compile-time verification but adds a generator for runtime discovery
- Slim ActionContext interface in platform-api — duplicates ScenarioScope's surface for an indirection layer; Tier 3 already serves the "no scope needed" case
**Rationale:** Follows @McpDomain discovery pattern. Lightweight — any CDI bean can expose actions by annotating methods. Supports multiple actions per class.
**Trade-offs:** @ScenarioAction implementations have a compile-time dependency on yaml-core (ScenarioScope). Intentional — Tier 4 is the scope-aware tier. Runtime discovery means typos surface at execution time.
**Depends on:** D3 (BeanInvoker pattern establishes the SPI-in-platform-api convention)
**Sources:** @McpDomain CDI discovery (GraphQLModelScanner), @CallbackEligible pattern, #391 D14 (Tier 4)
**Exploration:** quick
**Revised from:** R1-05 — explicitly stated ScenarioScope coupling is by design, not an oversight.
**Status:** revised

## D5: ComputeBlock compilation bridge — dropped

**Choice:** Dropped. Callers use `registry.compile(block.engine(), block.expression(), contextType, resultType)` directly. One line at the call site.
**Rationale:** Adding a `compile(ComputeBlock)` method to ExpressionEngineRegistry in platform-api would create a platform-api → yaml-core dependency, violating the zero-dep boundary. The convenience is a one-liner that doesn't justify a boundary violation.
**Revised from:** R1-01 — reviewer correctly identified the boundary violation. Original D5 incorrectly claimed yaml-core was a transitive dep of platform-api.
**Status:** revised (dropped)

## D6: BeanInvoker + ActionRegistry implementations — expression/ module

**Choice:** `CdiBeanInvoker @ApplicationScoped` and `CdiActionRegistry @ApplicationScoped @Startup` both live in expression/. CdiBeanInvoker uses CDI BeanManager to resolve beans by class name and invoke methods. CdiActionRegistry scans CDI beans at startup for @ScenarioAction-annotated methods, builds name→method handle map.
**Alternatives:**
- New action/ module — module for two beans that share CDI BeanManager dependency profile with expression/. Overly granular.
- orchestration-core/ — closed by #425 (keep orchestration in yaml-core). No separate orchestration module exists.
**Rationale:** Expression/ already has CDI BeanManager access patterns (DefaultExpressionEngineRegistry discovers ExpressionEngine beans). The four tiers of the expression escape model are a cohesive concern — "bridging YAML directives to Java runtime." Same dependency profile, same module.
**Trade-offs:** Expression/ grows to encompass invocation as well as expression evaluation. The module's scope is the four-tier escape model, not just expression evaluation.
**Depends on:** D3 (BeanInvoker SPI), D4 (@ScenarioAction + ActionRegistry SPI)
**Sources:** DefaultExpressionEngineRegistry CDI pattern, expression/ module, #425 closure (no orchestration-core extraction)
**Exploration:** quick
**Status:** captured

## D7: Spring equivalents — hand-written, built in parallel

**Choice:** Build CDI implementations in expression/ AND hand-written Spring equivalents in expression-spring/ simultaneously. `SpringBeanInvoker` uses `ApplicationContext.getBean()` for bean resolution. `SpringActionRegistry` scans all beans' methods for @ScenarioAction via reflection (Spring's `getBeansWithAnnotation()` is class-level; method-level scanning requires explicit iteration). Core POJOs in expression-core/ following the dual-framework extraction pattern.
**Spring-specific notes:** The spring-generator auto-generates expression engine wiring but cannot generate BeanInvoker or ActionRegistry — these have framework-specific logic (BeanManager vs ApplicationContext, CDI Instance vs ObjectProvider). Hand-written Spring auto-configuration classes required.
**Alternatives:**
- Quarkus first, Spring deferred — risks API drift and delayed parity
**Rationale:** Dual-framework from day one ensures the SPI design works for both DI containers. Hand-writing is explicit — no pretence that the generator handles invocation infrastructure.
**Trade-offs:** More implementation work. Acceptable — the SPIs are thin and the Spring equivalents are straightforward ApplicationContext wiring.
**Depends on:** D3 (BeanInvoker SPI), D4 (ActionRegistry SPI), D6 (CDI impls in expression/)
**Sources:** Dual-framework core extraction pattern (CLAUDE.md §Core Module Architecture), expression-spring/ module
**Exploration:** quick
**Revised from:** R1-06 — acknowledged Spring impls are hand-written, not auto-generated. Clarified method-level annotation scanning.
**Status:** revised
