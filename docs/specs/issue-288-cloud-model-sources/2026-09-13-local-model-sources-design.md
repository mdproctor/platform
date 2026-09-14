# Local Model Sources — Design Spec

**Issue:** casehubio/platform#289
**Date:** 2026-09-13
**Status:** Draft
**Branch:** issue-288-cloud-model-sources (covers: 288, 289, 290, 292)

## Summary

Ollama-specific local model management: auto-discover installed models, invoke them through the platform's agent routing, track pull/delete lifecycle, and report runtime health. HuggingFace models supported via Ollama's native `hf.co/` pull — no HF API integration needed.

Four components:
1. **OllamaModelSource** — `ModelSource` (priority 3), wraps `OllamaClient`, no-cache-on-failure
2. **OllamaAgentBackend** — `AgentBackend` (key "ollama"), OpenAI-compatible API via shared SDK base class
3. **Pull/delete lifecycle** — `LlmConfigApi` extensions (pull, status, cancel, delete)
4. **OllamaSourceStatus** — health/diagnostics via `ollamaStatus()` query

All Ollama-specific naming — no premature `LocalModelSource` generalization. When a second local runtime appears, design the generalization with real requirements from both runtimes.

## Architecture

```
┌─────────────────────────────────────────────────────────────────┐
│  ModelRegistryRefresher (@Startup + @Scheduled 1h)              │
│  iterates Instance<ModelSource> sorted by priority ascending    │
├─────────────────────────────────────────────────────────────────┤
│  Priority 0: SeedCatalogModelSource (static YAML)               │
│  Priority 3: OllamaModelSource (GET /api/tags)          ← NEW  │
│  Priority 5: Cloud sources (Anthropic, OpenAI, Vertex, Bedrock) │
│  Priority 10: ConfiguredModelSource (per-tenant wizard)         │
├─────────────────────────────────────────────────────────────────┤
│  OllamaModelSource                                              │
│  └─→ OllamaClient.listModels() → seed-enriched ModelDescriptors│
│  └─→ Returns List.of() on failure (no false availability)       │
├─────────────────────────────────────────────────────────────────┤
│  RoutingAgentProvider                                           │
│  └─→ resolveById("llama3") → backendKey="ollama"               │
│  └─→ OllamaAgentBackend → OpenAI SDK → localhost:11434/v1/     │
├─────────────────────────────────────────────────────────────────┤
│  LlmConfigApi (@McpDomain "llm-config")                         │
│  └─→ ollamaStatus() → OllamaSourceStatus                       │
│  └─→ pullModel() / pullStatus() / cancelPull() / deleteModel() │
│  └─→ Delegates to OllamaClient (POST /api/pull, DELETE, etc.)  │
└─────────────────────────────────────────────────────────────────┘
```

### Priority Model

| Priority | Source | ID format | Purpose |
|----------|--------|-----------|---------|
| 0 | Seed catalog | plain `apiModelId` | Static fallback, air-gapped |
| 3 | Local (Ollama) | plain Ollama model name | Live local discovery |
| 5 | Cloud sources | plain `apiModelId` | Live vendor truth |
| 10 | Configured (per-tenant) | `vendor:tenancyId:apiModelId` | Tenant-specific config |

Local and cloud ID namespaces are structurally disjoint in practice (Ollama: `llama3`, `mistral`, `codellama`; cloud: `claude-sonnet-5`, `gpt-4o`, `gemini-2.5-pro`). Priority-based conflict resolution between local and cloud rarely fires. The priority ordering matters primarily for seed enrichment: `ModelRegistryRefresher` iterates ascending, so seed data (0) is in the registry before Ollama (3) refreshes, enabling `OllamaClient.parseModelsResponse()` to enrich sparse Ollama metadata with curated seed data.

## OllamaModelSource

`@ApplicationScoped` bean implementing `ModelSource` directly (no marker interface).

```java
@ApplicationScoped
public class OllamaModelSource implements ModelSource {

    private final OllamaClient client;
    private volatile OllamaSourceStatus lastStatus;

    @Override public String sourceId() { return "local:ollama"; }
    @Override public int priority() { return 3; }

    @Override
    public List<ModelDescriptor> refresh() {
        // OllamaClient reads host from casehub.ollama.host config
        // (default http://localhost:11434) — no credentials map needed
        var result = client.listModels(Map.of());
        if (result.valid()) {
            var loaded = client.ps();
            var version = client.version();
            lastStatus = OllamaSourceStatus.online(version, loaded);
            return result.models();
        }
        lastStatus = OllamaSourceStatus.offline(result.errorMessage());
        return List.of();
    }

    OllamaSourceStatus status() { return lastStatus; }
}
```

### No-cache-on-failure (differs from cloud sources)

Cloud sources cache `lastKnownModels` and return them on API failure. This works because cloud listing failure and invocation failure are decoupled — the Anthropic listing API can be down while the Anthropic invocation API is up.

Ollama is a single process. If `GET /api/tags` fails, `POST /v1/chat/completions` will fail too. Cached models would create false availability: the router resolves a model, dispatches to `OllamaAgentBackend`, and the user gets a confusing streaming failure from a dead Ollama instance. Returning `List.of()` on failure gives a clean error at resolution time ("no model or backend for: llama3").

Models disappear from the registry immediately on Ollama failure. The refresh interval (default 1h) means they reappear on the next cycle once Ollama is back.

### No LocalModelSource marker interface

`LlmConfigService` injects `OllamaModelSource` directly for `ollamaStatus()`. Every design element (VRAM breakdown, `/api/ps`, `/api/pull`, `/api/delete`, Ollama version) is runtime-specific. A generic `LocalModelSource` interface would promise generality it can't deliver — a future contributor reading it would expect it to generalize across runtimes, then discover it doesn't.

When a second local runtime is added, design the generalization with real requirements from both runtimes. The migration is mechanical: extract a common interface from two concrete implementations.

## OllamaAgentBackend

New `agent-ollama/` module. `OllamaAgentBackend` (key: `"ollama"`) extends `AbstractOpenAiSdkBackend` extracted from `OpenAiAgentBackend`.

### Shared base class extraction

```java
// In agent-openai/ — extracted from OpenAiAgentBackend
public abstract class AbstractOpenAiSdkBackend implements AgentBackend {

    protected abstract com.openai.client.OpenAIClient openAiClient();
    protected abstract int maxConcurrentSessions();
    protected abstract Duration defaultTimeout();
    protected abstract String defaultModel();

    // Shared infrastructure:
    // - buildEventStream() — ChatCompletionCreateParams construction,
    //   StreamResponse iteration, AtomicBoolean/ScheduledFuture timeout
    // - Three-path resource cleanup (completion, failure, cancellation)
    // - Semaphore-gated invoke()
    // - OpenAiEventMapper.toEvents() for ChatCompletionChunk → AgentEvent
}

// In agent-openai/ — now extends the base
@ApplicationScoped
public class OpenAiAgentBackend extends AbstractOpenAiSdkBackend {
    @Override public String key() { return "openai"; }
    // Constructs OpenAI client with api.openai.com + api key
}

// In agent-ollama/ — new
@ApplicationScoped
public class OllamaAgentBackend extends AbstractOpenAiSdkBackend {
    @Override public String key() { return "ollama"; }
    // Constructs OpenAI client with localhost:11434/v1 + dummy key
}
```

**Why extract, not duplicate:** 140+ lines of identical streaming infrastructure (stream iteration, timeout coordination, resource cleanup, event mapping). If a bug is found in timeout logic, fix it once. If the OpenAI SDK changes its streaming API, update one base class.

**Why not `agent-openai-core/` module:** Over-engineering for exactly two consumers. If a third OpenAI-compatible backend appears, extract to a shared module then.

**OpenAiEventMapper compatibility:** `toEvents()` already handles Ollama's OpenAI-compatible endpoint gracefully. `usage`, `content`, and `toolCalls` all use `.ifPresent()`/`.orElse(null)` patterns. Missing fields (which Ollama may omit for some models) produce no events rather than exceptions. Tool calling and usage reporting are model-dependent, not backend-dependent.

### Configuration

```
casehub.platform.agent.ollama.host=http://localhost:11434       # Ollama host
casehub.platform.agent.ollama.default-model=llama3              # fallback model
casehub.platform.agent.ollama.default-timeout=PT120S            # 2 min default
casehub.platform.agent.ollama.max-concurrent-sessions=4
```

Timeout default is 120s (vs 30s for OpenAI) — local models are slower. GE-20260614-1ece0f confirms default timeouts are too short for local LLMs.

### OpenAI client construction

```java
com.openai.client.okhttp.OpenAIOkHttpClient.builder()
    .baseUrl(properties.host() + "/v1/")
    .apiKey("ollama")  // required by SDK, ignored by Ollama
    .build();
```

## Pull/Delete Lifecycle

Extensions to `LlmConfigApi`:

```java
@PlatformMutation("Pull a model into Ollama — accepts library names or hf.co/ references")
PullOperation pullModel(PullRequest request);

@PlatformQuery("Check pull operation progress")
PullProgress pullStatus(String operationId);

@PlatformMutation("Cancel an in-progress pull operation")
void cancelPull(String operationId);

@PlatformMutation("Delete a model from Ollama")
void deleteModel(String modelName);
```

### Data types

```java
public record PullRequest(String modelRef) {}
// modelRef: any Ollama-valid reference
//   "llama3"                         — Ollama library
//   "hf.co/TheBloke/Llama-2-7B-GGUF:Q4_K_M"  — HuggingFace GGUF

public record PullOperation(
    String operationId,
    String modelRef,
    PullStatus status
) {
    public enum PullStatus { PULLING, COMPLETED, FAILED, CANCELLED }
}

public record PullProgress(
    String operationId,
    String modelRef,
    PullOperation.PullStatus status,
    long totalBytes,
    long completedBytes,
    String digest,         // current layer being pulled
    String errorMessage    // null unless FAILED
) {}
```

### Implementation

`OllamaClient.pull(modelRef)` calls `POST /api/pull` with `{"name": modelRef, "stream": true}`. Ollama streams JSON progress objects:

```json
{"status":"pulling manifest"}
{"status":"pulling abc123","digest":"sha256:abc123","total":4000000000,"completed":1500000000}
{"status":"success"}
```

A background virtual thread reads the progress stream and updates a `ConcurrentHashMap<operationId, PullProgress>`. `pullStatus()` reads from this map. `cancelPull()` closes the HTTP connection to `/api/pull` — Ollama cancels the pull. The `HttpClient` connection reference is tracked per operation ID.

After successful completion, triggers `ModelRegistryRefresher` to refresh so the new model appears in the registry immediately rather than waiting for the next 1h cycle.

### Authorization

All mutation methods are within the `@McpDomain("llm-config")` annotation boundary. MCP tool calls go through the platform's MCP gateway which enforces tenant-scoped authorization. The existing mutation methods (`configure()`, `unconfigure()`) already operate within this boundary.

### No HuggingFace search

The pull API accepts `hf.co/` references natively — Ollama handles the HF download. Users browse HuggingFace Hub externally, copy the reference, pass it to the platform. Domain fine-tunes rarely outperform frontier models with RAG, and users who need air-gapped/cost-sensitive local models already know what they want. If demand for programmatic model search appears, a `HuggingFaceClient` can be added later without changing the pull API.

## OllamaSourceStatus

```java
public record OllamaSourceStatus(
    State state,
    String version,
    String message,
    List<LoadedModel> loadedModels
) {
    public enum State { ONLINE, OFFLINE }

    public record LoadedModel(
        String name,
        long sizeBytes,
        long sizeVramBytes,
        String quantization,
        Instant expiresAt
    ) {}

    public static OllamaSourceStatus online(String version,
                                             List<LoadedModel> loadedModels) {
        return new OllamaSourceStatus(State.ONLINE, version,
            loadedModels.size() + " models loaded", loadedModels);
    }

    public static OllamaSourceStatus offline(String errorMessage) {
        return new OllamaSourceStatus(State.OFFLINE, null, errorMessage, List.of());
    }
}
```

### Data sources

| Field | Ollama endpoint | Response field |
|-------|----------------|----------------|
| Reachability | `GET /` | HTTP 200 = online |
| Version | `GET /api/version` | `version` |
| Loaded models | `GET /api/ps` | `models[]` |
| Model VRAM | `GET /api/ps` | `models[].size_vram` |
| Model size | `GET /api/ps` | `models[].size` |
| Quantization | `GET /api/ps` | `models[].details.quantization_level` |
| Eviction time | `GET /api/ps` | `models[].expires_at` |

### What it doesn't include

GPU utilization %, CPU usage, memory pressure — Ollama's API doesn't expose these. There is an open GitHub issue (#3822) requesting a GPU hardware info endpoint. When Ollama adds it, enrich `OllamaSourceStatus` accordingly. The platform reports what Ollama reports — no more, no less.

### API exposure

```java
@PlatformQuery("Ollama runtime status — reachability, version, loaded models with VRAM")
OllamaSourceStatus ollamaStatus();
```

Named Ollama-specifically, not generically. Consistent with the rejection of premature generalization — `CloudSourceStatus` works as a generic interface because its schema is genuinely generic (sourceId, vendor, state, message, modelCount). `OllamaSourceStatus` includes Ollama-specific data (VRAM breakdown, eviction timers) that wouldn't apply to a different local runtime.

## Module Structure

| Module | Contents | Dependencies |
|--------|----------|-------------|
| `llm-config/` | `OllamaModelSource`, pull/delete lifecycle, `OllamaSourceStatus`, extended `OllamaClient` | platform-api, jackson, java.net.http |
| `agent-ollama/` (new) | `OllamaAgentBackend`, `OllamaAgentProperties` | agent-api, agent-openai (shared base class), OpenAI Java SDK |
| `agent-openai/` (modified) | Extract `AbstractOpenAiSdkBackend` from `OpenAiAgentBackend` | (unchanged deps) |

### OllamaClient extensions

The existing `OllamaClient` in `llm-config/` gains new methods:

| Method | Ollama endpoint | Purpose |
|--------|----------------|---------|
| `listModels()` | `GET /api/tags` | Already exists — installed model discovery |
| `ps()` | `GET /api/ps` | Loaded models with VRAM detail |
| `version()` | `GET /api/version` | Runtime version |
| `pull(modelRef)` | `POST /api/pull` | Streaming pull with progress |
| `cancelPull(connection)` | Close HTTP connection | Cancels in-flight pull |
| `delete(modelName)` | `DELETE /api/delete` | Remove model |
| `isReachable()` | `HEAD /` | Binary health check |

All methods use the shared `HttpClient` field (reusable, created at construction time — R1-08 from #288).

Timeout for `listModels()`, `ps()`, `version()`, `isReachable()`: configurable via `casehub.ollama.timeout` (default 10s). Timeout for `pull()`: no timeout (streaming, potentially hours). GE-20260614-1ece0f confirms default timeouts are too short for local model operations.

## Test Strategy

1. **OllamaModelSource** — mock `OllamaClient`. Verify: Ollama online → returns models; Ollama offline → returns `List.of()` (not cached); status reflects current state (ONLINE/OFFLINE); seed enrichment via `parseModelsResponse()`.
2. **OllamaAgentBackend** — mock OpenAI SDK streaming. Verify: streaming `ChatCompletionChunk → AgentEvent` works, timeout coordination, semaphore lifecycle, missing usage/tool fields handled gracefully (no exceptions).
3. **AbstractOpenAiSdkBackend extraction** — existing `OpenAiAgentBackendTest` must pass unchanged after extraction. No behavior change for the OpenAI backend.
4. **Pull lifecycle** — mock Ollama `POST /api/pull` streaming responses. Verify: progress tracking updates `ConcurrentHashMap`, completion triggers registry refresh, cancel closes HTTP connection, operation ID lookup returns correct state, FAILED status on error response.
5. **Delete** — mock Ollama `DELETE /api/delete`. Verify: successful delete triggers registry refresh, error handling for unknown models.
6. **OllamaSourceStatus** — mock `/api/ps` and `/api/version` responses. Verify: loaded model VRAM breakdown parsed correctly, offline detection, version extraction, empty loaded models when none running.
7. **Integration** — full flow: Ollama online → `OllamaModelSource` discovers models → `ModelRegistry.resolveById("llama3")` → `RoutingAgentProvider` dispatches to `OllamaAgentBackend` → streaming response.

## Files Changed

### New module: `agent-ollama/`

| File | Purpose |
|------|---------|
| `agent-ollama/pom.xml` | Depends on agent-api, agent-openai |
| `OllamaAgentBackend.java` | `AbstractOpenAiSdkBackend` subclass, key "ollama" |
| `OllamaAgentProperties.java` | `@ConfigMapping(prefix = "casehub.platform.agent.ollama")` |
| `OllamaAgentBackendTest.java` | Unit tests |

### Modified in `agent-openai/`

| File | Change |
|------|--------|
| `AbstractOpenAiSdkBackend.java` | New — extracted shared streaming infrastructure |
| `OpenAiAgentBackend.java` | Now extends `AbstractOpenAiSdkBackend` |

### New files in `llm-config/`

| File | Purpose |
|------|---------|
| `OllamaModelSource.java` | `ModelSource` impl, priority 3 |
| `OllamaSourceStatus.java` | Health record + `LoadedModel` record |
| `OllamaModelSourceTest.java` | Unit tests |

### Modified files in `llm-config/`

| File | Change |
|------|--------|
| `OllamaClient.java` | Add `ps()`, `version()`, `pull()`, `cancelPull()`, `delete()`, `isReachable()` |
| `LlmConfigApi.java` | Add `ollamaStatus()`, `pullModel()`, `pullStatus()`, `cancelPull()`, `deleteModel()` |
| `LlmConfigService.java` | Implement new API methods, inject `OllamaModelSource` for status |

### Modified root

| File | Change |
|------|--------|
| `pom.xml` (root) | Add `agent-ollama` to modules list |

### Unchanged

- `platform-api/` — no SPI changes
- `InMemoryModelRegistry` — unchanged
- `agent-router/` — `RoutingAgentProvider` unchanged (dispatches by `backendKey`, "ollama" key resolves naturally)

## Known Limitations

1. **Single Ollama instance** — scoped to one Ollama at `casehub.ollama.host`. Multi-instance local model management is a follow-up. Documented as a v1 scope boundary.
2. **No GPU metrics** — Ollama doesn't expose GPU utilization. `OllamaSourceStatus` reports VRAM per model (from `/api/ps`), not GPU %.
3. **Pull progress is in-memory** — `ConcurrentHashMap<operationId, PullProgress>` is lost on platform restart. Ollama continues the pull; the next discovery refresh picks up the completed model.
4. **Discovery-invocation coupling** — `OllamaAgentBackend` and `OllamaModelSource` both need Ollama running. Unlike cloud sources where discovery and invocation are independent services.
5. **No model search** — the platform doesn't search Ollama's library or HuggingFace Hub. Users provide model references directly to the pull API.
6. **No multi-turn sessions** — `OllamaAgentBackend` implements `invoke()` (single-shot) only. `openSession()` throws `UnsupportedOperationException`, matching `OpenAiAgentBackend`'s current state.

## Downstream (not this issue)

| Item | Description |
|------|-------------|
| Multi-instance Ollama | Managing multiple Ollama instances (e.g., different GPUs, remote machines) |
| GPU hardware endpoint | When Ollama adds `/api/info` (issue #3822), enrich `OllamaSourceStatus` |
| HuggingFace search | Add `HuggingFaceClient` if MCP agents need programmatic model discovery |
| In-process Java runtime | jlama or ONNX backend — separate `ModelSource` + `AgentBackend`, not generalized from Ollama |
| Multi-turn sessions | `openSession()` implementation for local models |
| `AbstractOpenAiSdkBackend` → `agent-openai-core/` | Extract to shared module if a third OpenAI-compatible backend appears |

## References

- #288 spec: `specs/issue-288-cloud-model-sources/2026-09-12-cloud-model-sources-design.md` — cloud source pattern
- #291 spec: `specs/issue-291-llm-config-wizard-api/2026-09-12-llm-config-wizard-api-design.md` — VendorClient, LlmConfigApi
- `OllamaClient.java` — existing Ollama HTTP client with `listModels()` and seed enrichment
- `OpenAiAgentBackend.java` — streaming infrastructure to extract
- `OpenAiEventMapper.java` — `ChatCompletionChunk → AgentEvent` mapping
- `AnthropicCloudModelSource.java` — cloud source pattern (last-known-good caching, not used here)
- `InMemoryModelRegistry.java` — priority resolution, `rebuildView()` semantics
- GE-20260614-337397 — langchain4j-ollama CDI clash with AgentProvider (avoid langchain4j)
- GE-20260614-1ece0f — Ollama REST client timeout too short for local LLMs
- GE-20260626-773613 — Ollama embed endpoint no truncation
- GE-20260612-79d73b — Ollama format=json enforcement
- Ollama API docs: `/api/tags`, `/api/ps`, `/api/pull`, `/api/delete`, `/api/version`
- Ollama OpenAI compatibility: `localhost:11434/v1/chat/completions`
- Epic #285 — parent: LLM model registry
