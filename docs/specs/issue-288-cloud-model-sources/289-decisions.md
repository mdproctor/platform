## D1: Discovery pattern — OllamaModelSource implementing ModelSource

**Choice:** `OllamaModelSource` implements `ModelSource` directly (no marker interface). Thin `@ApplicationScoped` bean wrapping `OllamaClient.listModels()`. On connection failure, returns `List.of()` (not cached models) — unlike cloud sources, Ollama being offline means models are uninvocable, so cached models would create false availability. Sets status to `OFFLINE` on failure. Single Ollama instance per deployment is a documented scope boundary for v1.
**Alternatives:**
- `LocalModelSource` marker interface (parallel to `CloudModelSource`) with `localStatus()` — premature generalization. Every concrete design element is Ollama-specific (VRAM, `/api/ps`, `/api/pull`). A generic `LocalModelSource` promises generality it can't deliver until a second runtime exists.
- Last-known-good caching (same as cloud sources) — creates false availability. Unlike cloud APIs where listing failure and invocation failure are decoupled, Ollama is a single process: if listing fails, invocation will fail too.
- Extended pattern with health state embedded in the source bean — couples discovery and health concerns.
- Separate from ModelSource — local runtime as its own SPI. Duplicates the discovery mechanism.
**Rationale:** `ModelRegistryRefresher` discovers it via existing `Instance<ModelSource>` iteration with no special handling. No premature `LocalModelSource` abstraction — YAGNI, consistent with D7's principle. When a second local runtime appears, design the generalization with real requirements. Returning empty on failure gives clear error messages at resolution time ("no model or backend for: llama3") rather than confusing stream failures from a dead Ollama instance.
**Trade-offs:** Health/diagnostics is a separate query via `ollamaStatus()`. Models disappear from registry immediately on Ollama failure — no grace period. Acceptable: the refresh interval (default 1h) means models reappear quickly once Ollama is back.
**Sources:** AnthropicCloudModelSource.java, CloudModelSource.java, OllamaClient.java
**Exploration:** quick
**Status:** revised (R1-04 — no-cache-on-failure for local sources; R1-09 — dropped LocalModelSource marker interface; R1-10 — single-instance scope boundary)

## D2: OllamaModelSource priority — 3

**Choice:** Priority 3 (below cloud=5, above seed=0). Priority governs two things: (1) enrichment ordering — `ModelRegistryRefresher` iterates sources by priority ascending, so seed data (0) is in the registry before Ollama (3) refreshes, enabling seed enrichment in `OllamaClient.parseModelsResponse()`; (2) `InMemoryModelRegistry.rebuildView()` conflict resolution — higher priority wins via `putIfAbsent` with descending iteration. In practice, Ollama model IDs (`llama3`, `mistral`, `codellama`) and cloud model IDs (`claude-sonnet-5`, `gpt-4o`, `gemini-2.5-pro`) are structurally disjoint namespaces — priority-based conflict resolution between local and cloud never fires.

Priority allocation table:
| Priority | Source | ID format | Purpose |
|----------|--------|-----------|---------|
| 0 | Seed catalog | plain `apiModelId` | Static fallback, air-gapped |
| 3 | Local (Ollama) | plain Ollama model name | Live local discovery |
| 5 | Cloud sources | plain `apiModelId` | Live vendor truth |
| 10 | Configured (per-tenant) | `vendor:tenancyId:apiModelId` | Tenant-specific config |

**Alternatives:**
- Priority 5 (same as cloud sources) — functionally identical given disjoint ID namespaces, but loses clear semantic ordering.
- Priority 1 (just above seed) — any value 1–4 is functionally equivalent for enrichment ordering.
**Rationale:** Priority 3 is the midpoint between seed (0) and cloud (5). The specific value is arbitrary — any value 1–4 achieves the same enrichment ordering. The choice of 3 provides visual symmetry in the allocation table and leaves room for future source types.
**Trade-offs:** Four priority tiers is more complex than three. Acceptable given the clear semantics and documented allocation table.
**Sources:** SeedCatalogModelSource (priority=0), cloud sources (priority=5), ConfiguredModelSource (priority=10), InMemoryModelRegistry.rebuildView()
**Exploration:** quick
**Status:** revised (R1-03 — added priority allocation table, clarified what priority governs, acknowledged disjoint ID namespaces)

## D3: OllamaAgentBackend — new agent-ollama/ module, shared OpenAI SDK base class

**Choice:** New `agent-ollama/` module with `OllamaAgentBackend` (`key: "ollama"`). Extends `AbstractOpenAiSdkBackend` extracted from `OpenAiAgentBackend` into the `agent-openai/` module. The shared base class contains the streaming infrastructure (`buildEventStream()`, `OpenAiEventMapper`, `ChatCompletionCreateParams` construction, `StreamResponse` iteration, `AtomicBoolean`/`ScheduledFuture` timeout coordination) and three-path resource cleanup. Each concrete subclass provides `key()`, config mapping, and client construction (base URL, API key). `OllamaAgentBackend` constructs the OpenAI client with `baseUrl("http://localhost:11434/v1/")`.

`OpenAiEventMapper.toEvents()` already handles Ollama's OpenAI-compatible endpoint gracefully: `usage`, `content`, and `toolCalls` all use `.ifPresent()`/`.orElse(null)` patterns, so missing fields (which Ollama may omit for some models) produce no events rather than exceptions. Tool calling and usage reporting are model-dependent, not backend-dependent — the same variability exists across different OpenAI models.
**Alternatives:**
- Full duplication (original D3) — 140+ lines of identical code in two files. If the OpenAI SDK changes its streaming API, both backends need identical changes. If a bug is found in the timeout coordination logic, it must be fixed in two places.
- `agent-openai-core/` shared module — cleaner module separation but adds a module purely for one base class and mapper. Over-engineering given exactly two consumers.
- Use Ollama's native API (`/api/chat`) — would need custom request/response mapping instead of reusing OpenAI SDK.
- Use LangChain4j's Ollama integration — CDI clash with AgentProvider stack (GE-20260614-337397).
**Rationale:** Extraction eliminates 140+ lines of duplication while preserving separate modules, separate CDI beans, and separate keys. `agent-ollama/` depending on `agent-openai/` is semantically accurate — Ollama's backend uses the OpenAI protocol. The dependency is on the shared SDK code, not on the concrete `OpenAiAgentBackend` bean. A separate `agent-openai-core/` module would be justified if a third OpenAI-compatible backend appeared; for two consumers, co-location in `agent-openai/` is simpler.
**Trade-offs:** `agent-ollama/` depends on `agent-openai/` at compile time. Deploying Ollama-only means `agent-openai/` is on the classpath but unused (the `OpenAiAgentBackend` bean exists but receives no traffic). Acceptable — it's just classes, not heavyweight runtime.
**Sources:** OpenAiAgentBackend.java (192 lines), OpenAiEventMapper.java (59 lines), agent-openai/ module structure, Ollama OpenAI compatibility docs, GE-20260614-337397
**Exploration:** quick
**Status:** revised (R1-02 — extract shared OpenAI SDK code instead of duplicating; R1-06 — documented graceful handling of Ollama API gaps)

## D4: Module placement — all in llm-config/ (except backend)

**Choice:** `OllamaModelSource`, pull/delete lifecycle, health status (`OllamaSourceStatus`) — all in `llm-config/`. `OllamaAgentBackend` in separate `agent-ollama/` module.
**Alternatives:**
- New `llm-config-local/` module — isolates local concerns but `OllamaClient` is already in `llm-config/`.
- Split: `llm-config/` for source, new `llm-local/` for pull/health — clean separation but more wiring.
**Rationale:** No classpath isolation concern (unlike Vertex/Bedrock with SDK dependencies). `OllamaClient` is already in `llm-config/`. All local model management logic is cohesive in one place. Backend is separate per the established agent module pattern.
**Trade-offs:** `llm-config/` grows. Acceptable — all model source management logic is cohesive.
**Sources:** llm-config/ module contents, llm-config-vertex/ and llm-config-bedrock/ separation rationale
**Exploration:** quick
**Status:** captured

## D5: Pull API — LlmConfigApi extension, any Ollama-valid reference, with cancellation

**Choice:** Extend `LlmConfigApi` with `pullModel(PullRequest)` → `PullOperation`, `pullStatus(operationId)` → `PullProgress`, `cancelPull(operationId)`, `deleteModel(modelName)`. Accepts any Ollama-valid model reference: library names (`llama3`), HuggingFace repos (`hf.co/user/repo:Q4_K_M`), or any other format Ollama supports. No HuggingFace search API. Authorization is provided by the `@McpDomain("llm-config")` annotation — all MCP tool calls go through the platform's MCP gateway which enforces tenant-scoped authorization. The existing mutation methods (`configure()`, `unconfigure()`) already operate within this authorization boundary.
**Alternatives:**
- No `cancelPull()` — pulling a large model (e.g., Llama 3.1 405B at ~230GB) can take tens of minutes to hours. Without cancellation, users must manually kill the Ollama pull process. Ollama supports cancellation by closing the HTTP connection to `/api/pull`; the platform tracks the in-flight connection and closes it on cancel.
- New `LocalModelManager` SPI in `platform-api` — adds a new SPI to the zero-dep API module for what is really an llm-config concern.
- Separate `@McpDomain` — fragments the model management surface.
- Include HuggingFace Hub search API — YAGNI. Domain fine-tunes are a shrinking niche; users who want a specific HF model already know the repo and can provide the `hf.co/` reference directly.
**Rationale:** `LlmConfigApi` is already the `@McpDomain` for model management. MCP tools get pull/delete/cancel for free via GraphQL generation. `cancelPull()` completes the pull lifecycle (start, monitor, cancel). HF support comes for free via Ollama's native `hf.co/` pull handling.
**Trade-offs:** No programmatic model search/discovery for pullable models. `cancelPull()` requires tracking in-flight HTTP connections per operation ID — small state management overhead.
**Sources:** LlmConfigApi.java, Ollama POST /api/pull docs, HuggingFace GGUF discussion
**Exploration:** deep-analysis
**Status:** revised (R1-07 — added cancelPull; R1-11 — documented authorization via @McpDomain)

## D6: Health/diagnostics — OllamaSourceStatus via LlmConfigApi

**Choice:** New `ollamaStatus()` query in `LlmConfigApi` returning `OllamaSourceStatus`. Covers: runtime reachability (`GET /`), Ollama version, loaded models with VRAM breakdown (`GET /api/ps` — name, size, size_vram, expires_at). Named Ollama-specifically rather than generically — consistent with D1's rejection of premature `LocalModelSource` generalization.
**Alternatives:**
- Generic `localSourceStatus()` returning `List<LocalSourceStatus>` — false generalization. The status schema (VRAM breakdown, `expires_at` eviction timers, `/api/ps` data) is Ollama-specific. A second local runtime would need its own status schema.
- Full GPU/CPU utilization diagnostics — Ollama's API doesn't expose GPU utilization percentage, CPU usage, or memory pressure. No endpoint exists for this (open GitHub issue #3822).
- Unified `SourceStatus` covering both cloud and local — local health data (VRAM, loaded models) doesn't fit the cloud schema.
- Binary health only — too limited for onboarding guidance.
**Rationale:** Scoped to what Ollama's API actually provides. `/api/ps` gives loaded models with VRAM split — enough to answer "is Ollama running and what's loaded?" No over-promising. Extends `OllamaClient` with `ps()` and `version()` methods. Ollama-specific naming avoids the false promise of generality — if a second runtime appears, design the appropriate status query then.
**Trade-offs:** No GPU utilization metrics. Adding a second local runtime requires a new status query method. Acceptable — YAGNI.
**Sources:** Ollama API docs (/api/ps response schema), GitHub issue #3822 (GPU hardware endpoint request)
**Exploration:** deep-analysis
**Status:** revised (R1-09 — renamed from LocalSourceStatus to OllamaSourceStatus, from localSourceStatus() to ollamaStatus())

## D8: Naming convention — Ollama-specific, no premature generalization

**Choice:** All local model source types use Ollama-specific names: `OllamaModelSource`, `OllamaSourceStatus`, `ollamaStatus()`. No `LocalModelSource` marker interface, no `LocalSourceStatus`, no `localSourceStatus()`. When a second local runtime is added, design the generalization with real requirements from both runtimes.
**Alternatives:**
- Generic `LocalModelSource` / `LocalSourceStatus` / `localSourceStatus()` — creates a false promise of generality. Every concrete design element (VRAM breakdown, `/api/ps`, `/api/pull`, `/api/delete`, Ollama version) is runtime-specific. A future contributor reading `LocalModelSource` will expect it to generalize across runtimes; discovering it doesn't forces either shoehorning a different runtime into Ollama's schema or abandoning the abstraction.
- Actually generalize — design status/pull/delete APIs that work for any local runtime. More work, but delivers real abstraction. Not justified now: no second runtime is planned, and designing without real requirements produces bad abstractions.
**Rationale:** Consistent with D7's YAGNI principle. `CloudModelSource` works as a generic interface because its status schema (`CloudSourceStatus`) is genuinely generic (sourceId, vendor, state, message, modelCount). The proposed local status data is not generic — it's Ollama's API surface. Honest naming prevents false expectations.
**Trade-offs:** Adding a second runtime requires adding new types rather than implementing an existing interface. Acceptable — the migration is mechanical and the breakage forces explicit design decisions.
**Sources:** R1-09 reviewer challenge, CloudModelSource.java, CloudSourceStatus.java
**Exploration:** surfaced by review (R1-09)
**Status:** captured

## D7: No HuggingFace search API — YAGNI

**Choice:** No `HuggingFaceClient`, no Hub API search, no `PullableModel` record. HuggingFace models are supported via Ollama's native `hf.co/` pull — the user provides the reference, the platform pulls it.
**Alternatives:**
- Minimal search pass-through — low cost but low value. Users who browse HF already have the model reference.
- Full search with quantization recommendations — high complexity, quality filtering unsolved, domain fine-tunes are a shrinking niche.
**Rationale:** Devil's advocated thoroughly. Domain fine-tunes rarely outperform frontier models with RAG. Users who need air-gapped/cost-sensitive local models already know what they want. HF Hub search adds platform-side HTTP client, result parsing, and quality heuristics for a rare use case. Ollama's native `hf.co/` pull means the platform supports HF models without needing any HF API integration.
**Trade-offs:** No programmatic model discovery for HF. MCP agents cannot search for models — they can only pull known references. If demand appears, a `HuggingFaceClient` can be added later without changing the pull API.
**Sources:** HuggingFace Hub API docs, Ollama `hf.co/` pull support, domain fine-tune performance analysis
**Exploration:** deep-analysis
**Status:** captured
