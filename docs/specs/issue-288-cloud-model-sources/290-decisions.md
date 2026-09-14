## D1: Dispatch model — BackendInstanceRegistry

**Choice:** New `BackendInstanceRegistry` bean separates instance management from routing. `ConcurrentHashMap<BackendRef, AgentBackend>` where `BackendRef` is a `(key, instanceId)` record. Populated at startup from two sources: (1) CDI-discovered `AgentBackend` beans (existing pattern), (2) `BackendInstanceFactory` beans (credential-store-driven). `RoutingAgentProvider` injects the registry instead of `Instance<AgentBackend>` directly. Resolution: `registry.resolve(backendKey, instanceId)` returns the matching backend; null instanceId on `ModelDescriptor` falls back to "default" instance.
**Alternatives:**
- Inline compound map in RoutingAgentProvider — simpler (no new bean) but accumulates routing + registration + lifecycle responsibilities in the router. Harder to test registration independently.
- CDI qualifier-based dispatch (`@BackendInstance("vertex")`) — pure CDI but qualifiers are build-time; can't support credential-store-driven runtime instances.
**Rationale:** Clean separation of concerns. Router stays focused on resolution logic. Registry handles both CDI-discovered and runtime-created instances uniformly. Natural extension point for future runtime registration (e.g., MCP-driven backend onboarding).
**Trade-offs:** One more bean in the graph. Acceptable — the responsibility split is clear and the registry is testable independently.
**Sources:** RoutingAgentProvider.java, Instance<AgentBackend> pattern, GE-20260626-c21b02 (@DefaultBean suppression by Instance<T> peer)
**Exploration:** quick
**Status:** captured

## D2: Module placement — SPI in agent-api, implementation in agent-router

**Choice:** `BackendInstanceRegistry` interface and `BackendInstanceFactory` interface in `agent-api/` (pure Java, no CDI). `InMemoryBackendInstanceRegistry` implementation (`@ApplicationScoped`, `ConcurrentHashMap`) and `BackendInstanceCoordinator` (`@Startup` — iterates CDI backends + factory beans, populates registry) in `agent-router/`. Backend modules implement `BackendInstanceFactory` against the agent-api SPI — they already depend on agent-api, so no new dependencies.
**Alternatives:**
- All in agent-router — forces backend factory modules to depend on agent-router, reversing the current dependency direction (backends are discovered by CDI, not dependent on the router).
- New agent-registry module — over-engineering for one interface and one implementation. No isolation benefit.
**Rationale:** Follows the existing agent-api/agent-router split (SPI vs implementation). agent-api remains pure Java (no Quarkus). Factory beans in backend modules implement BackendInstanceFactory without coupling to the router module.
**Trade-offs:** Two more interfaces in agent-api. Acceptable — they're peer SPIs to AgentBackend.
**Sources:** agent-api/ (AgentBackend.java — pure Mutiny), agent-router/ (RoutingAgentProvider.java)
**Exploration:** quick
**Status:** captured

## D3: BackendInstanceFactory — credential-ref-specific, per-module

**Choice:** Each `BackendInstanceFactory` implementation maps to a credential pattern. The factory declares its `backendKey()` and a `handles(credentialRef, credentials)` predicate. The coordinator iterates all credential refs in `LlmCredentialStore`, finds matching factories, calls `create()` to produce `BackendInstance(instanceId, AgentBackend)`. Each factory lives in the module that owns the invocation SDK — `agent-openai/` provides `OpenAiDirectBackendFactory`, a future `agent-vertex/` would provide `VertexClaudeBackendFactory`, etc.

SPI in `agent-api/`:
```java
public interface BackendInstanceFactory {
    String backendKey();
    boolean handles(String credentialRef, Map<String, String> credentials);
    BackendInstance create(String credentialRef, Map<String, String> credentials);
}
public record BackendInstance(String instanceId, AgentBackend backend) {}
```

For multi-credential same-platform: two credential refs (`cloud-anthropic`, `extra-anthropic`) both match `ClaudeDirectBackendFactory.handles()`, producing two instances with different instanceIds derived from the credential ref.
**Alternatives:**
- Bulk credential map per factory — one factory per backend type receives all credentials and creates all instances. Fewer classes but factory logic is complex and can't be split across modules.
- Per-credential canCreate/create — similar pattern but factory can't see the full credential picture. Not a meaningful difference from the chosen approach.
**Rationale:** Self-contained factories — each module provides exactly the factories it can create. Adding a new backend type means adding a module with a factory; no coordinator changes needed. The `handles()` predicate allows both exact-match (credential ref name) and structural matching (credential fields present).
**Trade-offs:** More factory classes than a bulk approach. Each factory is trivial — 10-20 lines. Acceptable.
**Depends on:** D1 (registry), D2 (module placement)
**Sources:** CloudSourceCredentialBootstrap.java (credential detection pattern), LlmCredentialStore.java
**Exploration:** quick
**Status:** captured

## D4: ModelDescriptor — add nullable backendInstanceId field

**Choice:** Add `@Nullable String backendInstanceId` to `ModelDescriptor` record. Null means "default" instance — backward compatible with all existing code. `ModelSource` implementations set it when their models route to a specific backend instance (e.g., `VertexCloudModelSource` sets `backendInstanceId = "vertex"`). The router resolves: `registry.resolve(backendKey, instanceId != null ? instanceId : "default")`.
**Alternatives:**
- `BackendRef` composite replacing `backendKey` — typed, clean, but breaking change to every `ModelDescriptor` constructor call across the codebase. Migration cost disproportionate to benefit.
- Properties map — untyped, easy to miss, not discoverable by IDEs or schema generators.
**Rationale:** Additive change to a record in `platform-api/`. No existing constructor calls break — new field has a default-compatible null value. The router treats null as "default" which preserves all current behavior.
**Trade-offs:** Nullable field on a record (nullable fields on records are a mild code smell). Acceptable — the null semantics are clear and documented. A builder would be heavier than justified for one nullable field.
**Depends on:** D1 (registry uses backendInstanceId for resolution)
**Sources:** ModelDescriptor.java (platform-api), RoutingAgentProvider.resolve()
**Exploration:** quick
**Status:** captured

## D5: AgentBackend.instanceId() — default method returning "default"

**Choice:** Add `default String instanceId() { return "default"; }` to `AgentBackend`. Existing backends inherit `"default"` without code changes. Backends that represent a specific instance (e.g., a factory-created Vertex Claude backend) override to return their instance identifier (e.g., `"vertex"`). The registry key is always `(key, instanceId)` — uniform compound key, no null-handling special cases.
**Alternatives:**
- Null default — requires special-case handling in registry resolution (null vs non-null keys). More complex for no benefit.
- Abstract method (no default) — forces all existing backends to add `instanceId()` override. Breaking change for zero value.
**Rationale:** Default method is backward compatible — all existing backends work unchanged. `"default"` is an explicit, log-friendly sentinel. Uniform `(key, instanceId)` compound keys simplify registry and router logic.
**Trade-offs:** None — purely additive.
**Depends on:** D1 (registry uses instanceId for compound keys)
**Sources:** AgentBackend.java (agent-api)
**Exploration:** quick
**Status:** captured

## D6: Startup coordination — eager at Priority 75

**Choice:** `BackendInstanceCoordinator` observes `StartupEvent` at `@Priority(75)` — after `CloudSourceCredentialBootstrap` (50) seeds credentials, before `ModelRegistryRefresher` (100) populates the model registry. The coordinator: (1) iterates `Instance<AgentBackend>` to register CDI-discovered backends with the registry, (2) iterates `Instance<BackendInstanceFactory>` + `LlmCredentialStore` entries to create and register factory instances. All backend instances exist before any model resolution.
**Alternatives:**
- Lazy on first resolve — creates instances on demand. No startup ordering concern but adds latency spike on first model resolution and pushes factory error handling onto the request path.
**Rationale:** Eager startup matches the existing credential bootstrap → model refresh pattern from #288. All backends are available before any model is resolved. Factory errors surface at startup (fail-fast) rather than on the first user request. The `@Priority` ordering is explicit and documented.
**Trade-offs:** Adds ~1 more startup observer in the priority chain. Acceptable — the ordering is linear and documented.
**Depends on:** D1 (registry), D3 (factory SPI)
**Sources:** CloudSourceCredentialBootstrap.java (@Priority(50)), ModelRegistryRefresher.java
**Exploration:** quick
**Status:** captured

## D7: Scope boundary — infrastructure + OpenAI factory

**Choice:** This issue delivers: (1) SPI changes — `AgentBackend.instanceId()`, `BackendInstanceFactory`, `BackendInstanceRegistry`, `BackendInstance`, `BackendRef`; (2) implementation — `InMemoryBackendInstanceRegistry`, `BackendInstanceCoordinator`; (3) router refactor — `RoutingAgentProvider` uses registry instead of `Instance<AgentBackend>`; (4) `ModelDescriptor.backendInstanceId`; (5) one concrete factory — `OpenAiDirectBackendFactory` (creates `OpenAiAgentBackend` instances from credential-store API keys). Vertex/Bedrock factories are separate issues requiring new modules with vendor SDK dependencies. Claude CLI doesn't fit the credential-store model (reads system config, not credentials). Ollama is local with no credentials.
**Alternatives:**
- Infrastructure only — no end-to-end proof that the factory mechanism works. Harder to validate the design without a real consumer.
- All backend factories — forces awkward implementations for Claude CLI (no credentials) and Ollama (local, no auth). Scope creep.
**Rationale:** OpenAI is the simplest factory — API-key-driven, `AbstractOpenAiSdkBackend` already parameterizes base URL and API key. One factory proves the mechanism end-to-end: credential detection → factory → registry → model resolution → invocation. Vertex/Bedrock need vendor SDK modules; Claude CLI needs a different activation model (config-driven, not credential-driven). Both are better as follow-up issues.
**Trade-offs:** Claude CLI and Ollama backends are not factory-produced. They continue as CDI-discovered beans registered by the coordinator. Factory integration for these backends is downstream if needed.
**Sources:** OpenAiAgentBackend.java (AbstractOpenAiSdkBackend), ClaudeAgentProvider (CLI subprocess), OllamaAgentBackend (local, no auth)
**Exploration:** quick
**Status:** captured
