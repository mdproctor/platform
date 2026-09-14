## D1: Separate MCP domains for registry reads vs configuration

**Choice:** New `models` domain for read-only registry queries + refresh. `llm-config` keeps vendor configuration and local model management.
**Alternatives:**
- Single `models` domain merging everything — forces llm-config dependencies onto platform-level reads
- Extend `llm-config` with registry reads — mixes platform-level SPI with vendor-level operations
**Rationale:** ModelRegistry is a platform-level concern (zero-dep API). Any LLM agent should be able to browse available models without needing vendor HTTP clients, credential stores, or Ollama on the classpath. Different audiences: LLM agents browse models; admins configure providers.
**Trade-offs:** Two domains to discover instead of one. An LLM looking for "how do I set up OpenAI" needs to find `llm-config`, not `models`.
**Sources:** LlmConfigApi.java, ModelRegistry.java, issue #292
**Exploration:** quick
**Status:** captured

## D2: Interface in platform-api, implementation in platform

**Choice:** `ModelRegistryApi` interface in `platform-api` (zero-dep), `ModelRegistryService` implementation in `platform/` delegating to injected `ModelRegistry` + `ModelRegistryRefresher`.
**Alternatives:**
- New `models/` module — clean separation but adds build complexity for 3 thin delegate operations
- Both in `platform/` — simpler but interface unavailable to non-Quarkus consumers
**Rationale:** Follows existing SPI pattern (every SPI in platform-api gets a @DefaultBean or @ApplicationScoped implementation in platform). The interface sits next to `ModelRegistry` and `ModelDescriptor` which it directly uses as return types.
**Trade-offs:** None significant — this is the established convention.
**Sources:** platform-api/.../model/ModelRegistry.java, LlmConfigApi.java (interface pattern)
**Exploration:** quick
**Status:** captured

## D3: ModelEnricher with registry state

**Choice:** Include a `ModelRegistryEnricher` implementing `ModelEnricher` to surface live state (model count, vendor breakdown, source count, last refresh time) in the `casehub_model` catalog.
**Alternatives:**
- No enrichment — LLM must call `listModels()` to discover what's available
**Rationale:** The catalog is the entry point for discovery. Showing "12 models from 3 vendors, last refreshed 5m ago" lets an LLM decide whether to drill in or call `refreshRegistry` first.
**Trade-offs:** Adds one more CDI bean. Negligible.
**Sources:** TestModelEnricher.java, ModelEnricher.java, EngineModelEnricher.java
**Exploration:** quick
**Status:** captured

## D4: Individual params for listModels

**Choice:** `listModels(String vendor, String family, String tier, String locality, String maxCostTier)` — all optional. Implementation constructs a `ModelQuery` from them.
**Alternatives:**
- `listModels(ModelQuery query)` — reuses existing type but heavier for simple browsing. GraphQL maps it as an input object requiring nested JSON.
**Rationale:** Individual params map cleanly to GraphQL query args and REST `@QueryParam`. Most LLM calls will be `listModels()` with zero or one filter. String types for enums allow the generated REST layer to work without custom converters; impl converts internally.
**Trade-offs:** Capabilities and authMethod omitted from initial params — can be added later. String-typed enum params need validation in the impl.
**Sources:** ModelQuery.java (7 fields), LlmConfigApi pattern
**Exploration:** quick
**Status:** captured

## D5: RefreshResult with delta for refreshRegistry

**Choice:** `refreshRegistry()` returns a `RefreshResult` record with source count, total model count, and added/removed/updated counts.
**Alternatives:**
- Void return — fire-and-forget, no feedback
**Rationale:** An LLM calling refresh wants to know it worked and what changed. "Refreshed 4 sources: 2 added, 1 removed" is actionable. Void gives no feedback.
**Trade-offs:** Requires aggregating `CatalogDelta` from `ModelRegistryRefresher` — currently `refreshAll()` fires CDI events but doesn't return a summary. Small refactor needed.
**Sources:** ModelRegistryRefresher.java, MutableModelRegistry.CatalogDelta
**Exploration:** quick
**Status:** captured
