# Multi-Instance Backend Support — Design Spec

**Issue:** casehubio/platform#290
**Date:** 2026-09-14
**Status:** Draft
**Branch:** issue-288-cloud-model-sources (covers: 288, 289, 290, 292)

## Summary

Allow multiple instances of the same `AgentBackend` type with different configurations. Today `AgentBackend.key()` returns a single string ("claude", "openai"), `RoutingAgentProvider` stores `Map<String, AgentBackend>` — one instance per key. This blocks cross-platform invocation (Claude via direct API + Claude via Vertex + Claude via Bedrock) and multi-credential same-platform (two OpenAI API keys for different cost centers).

Five components:
1. **SPI extension** — `AgentBackend.instanceId()` default method, `BackendInstanceFactory` and `BackendInstanceRegistry` interfaces in `agent-api/`
2. **Registry implementation** — `InMemoryBackendInstanceRegistry` in `agent-router/`
3. **Startup coordinator** — `BackendInstanceCoordinator` wires CDI backends + factory-created instances
4. **Router refactor** — `RoutingAgentProvider` dispatches via registry instead of direct `Instance<AgentBackend>`
5. **ModelDescriptor extension** — nullable `backendInstanceId` field for instance-specific routing

One concrete factory: `OpenAiDirectBackendFactory` (creates `OpenAiAgentBackend` instances from credential-store API keys). Vertex/Bedrock factories are separate issues requiring vendor SDK modules.

## Architecture

```
┌─────────────────────────────────────────────────────────────────────┐
│  Startup Sequence                                                    │
│                                                                      │
│  @Priority(50)   CloudSourceCredentialBootstrap                      │
│                  └→ seeds LlmCredentialStore with env-detected creds │
│                                                                      │
│  @Priority(75)   BackendInstanceCoordinator                    ← NEW │
│                  ├→ Instance<AgentBackend> → register CDI backends   │
│                  └→ Instance<BackendInstanceFactory>                 │
│                     + LlmCredentialStore.listRefs()                  │
│                     → create & register factory instances            │
│                                                                      │
│  @Priority(100)  ModelRegistryRefresher                              │
│                  └→ Instance<ModelSource> → populate model registry  │
├─────────────────────────────────────────────────────────────────────┤
│                                                                      │
│  BackendInstanceRegistry                                       ← NEW │
│  ┌──────────────────────────────────────────────────────────┐       │
│  │  (claude, default) → ClaudeAgentProvider    [CDI bean]   │       │
│  │  (openai, default) → OpenAiAgentBackend     [CDI bean]   │       │
│  │  (openai, extra)   → OpenAiAgentBackend     [factory]    │       │
│  │  (ollama, default) → OllamaAgentBackend     [CDI bean]   │       │
│  │  (claude, vertex)  → VertexClaudeBackend    [factory]  * │       │
│  │  (claude, bedrock) → BedrockClaudeBackend   [factory]  * │       │
│  └──────────────────────────────────────────────────────────┘       │
│  * = future issues, not this scope                                   │
│                                                                      │
├─────────────────────────────────────────────────────────────────────┤
│  RoutingAgentProvider                                     [modified] │
│  resolve(model):                                                     │
│    1. modelRegistry.resolveById(model) → backendKey + instanceId    │
│    2. registry.resolve(backendKey, instanceId ?? "default")         │
│    3. fallback: registry.resolve(model, "default")                  │
│    4. throw                                                          │
├─────────────────────────────────────────────────────────────────────┤
│  ModelDescriptor                                          [modified] │
│  + backendInstanceId: String (nullable — null = "default")          │
└─────────────────────────────────────────────────────────────────────┘
```

## SPI Changes (agent-api)

All new types in `io.casehub.platform.agent` package. Pure Java — no CDI, no Quarkus.

### AgentBackend — new default method

```java
public interface AgentBackend {
    String key();
    default String instanceId() { return "default"; }
    Multi<AgentEvent> invoke(AgentSessionConfig config);
    AgentSession openSession(AgentSessionInit init);
}
```

Existing backends (`ClaudeAgentProvider`, `OpenAiAgentBackend`, `OllamaAgentBackend`) inherit `"default"` without code changes. Factory-created backends override to return their instance identifier (e.g., `"vertex"`, `"extra"`).

### BackendRef — compound key

```java
public record BackendRef(String key, String instanceId) {
    public BackendRef {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(instanceId, "instanceId");
    }
}
```

Used by the registry for compound key lookup. The `(key, instanceId)` pair uniquely identifies a backend instance.

### BackendInstance — factory output

```java
public record BackendInstance(String instanceId, AgentBackend backend) {
    public BackendInstance {
        Objects.requireNonNull(instanceId, "instanceId");
        Objects.requireNonNull(backend, "backend");
    }
}
```

Returned by `BackendInstanceFactory.create()`. The `instanceId` becomes the backend's identity in the registry.

### BackendInstanceFactory — per-module factory SPI

```java
public interface BackendInstanceFactory {
    String backendKey();
    boolean handles(String credentialRef, Map<String, String> credentials);
    BackendInstance create(String credentialRef, Map<String, String> credentials);
}
```

Each factory maps to a credential pattern. The coordinator iterates all credential refs in `LlmCredentialStore`, finds matching factories, calls `create()` to produce `BackendInstance`. Each factory lives in the module that owns the invocation SDK.

- `backendKey()` — which backend type this factory creates (e.g., `"openai"`)
- `handles(credentialRef, credentials)` — predicate: can this factory create an instance from these credentials? Matching can be by credential ref name convention, credential field structure, or both.
- `create(credentialRef, credentials)` — creates a configured `BackendInstance`. The factory determines the `instanceId` (typically derived from the credential ref — e.g., `"cloud-openai"` → `"default"`, `"extra-openai"` → `"extra"`).

### BackendInstanceRegistry — instance management SPI

```java
public interface BackendInstanceRegistry {
    void register(AgentBackend backend);
    Optional<AgentBackend> resolve(String key, String instanceId);
    List<AgentBackend> resolveByKey(String key);
}
```

- `register(backend)` — registers a backend using its `key()` and `instanceId()`. If a backend with the same `(key, instanceId)` already exists, the new one replaces it (last-write-wins).
- `resolve(key, instanceId)` — lookup by compound key. Returns empty if no match.
- `resolveByKey(key)` — all instances for a given backend key. Used for diagnostics and status queries.

## Registry Implementation (agent-router)

### InMemoryBackendInstanceRegistry

```java
@ApplicationScoped
public class InMemoryBackendInstanceRegistry implements BackendInstanceRegistry {

    private final ConcurrentHashMap<BackendRef, AgentBackend> instances = new ConcurrentHashMap<>();

    @Override
    public void register(AgentBackend backend) {
        instances.put(new BackendRef(backend.key(), backend.instanceId()), backend);
    }

    @Override
    public Optional<AgentBackend> resolve(String key, String instanceId) {
        return Optional.ofNullable(instances.get(new BackendRef(key, instanceId)));
    }

    @Override
    public List<AgentBackend> resolveByKey(String key) {
        return instances.entrySet().stream()
            .filter(e -> e.getKey().key().equals(key))
            .map(Map.Entry::getValue)
            .toList();
    }
}
```

`ConcurrentHashMap` — thread-safe for concurrent registration and resolution. No locking beyond what `ConcurrentHashMap` provides.

### BackendInstanceCoordinator

```java
@ApplicationScoped
public class BackendInstanceCoordinator {

    @Inject @Any Instance<AgentBackend> cdiBackends;
    @Inject @Any Instance<BackendInstanceFactory> factories;
    @Inject BackendInstanceRegistry registry;
    @Inject LlmCredentialStore credentialStore;

    void onStartup(@Observes @Priority(75) StartupEvent event) {
        // 1. Register CDI-discovered backends
        for (AgentBackend backend : cdiBackends) {
            registry.register(backend);
        }

        // 2. Create factory instances from credential store
        List<String> refs = credentialStore.listRefs(TenancyConstants.PLATFORM_TENANT_ID);
        for (String ref : refs) {
            Map<String, String> creds = credentialStore.resolve(
                TenancyConstants.PLATFORM_TENANT_ID, ref);
            for (BackendInstanceFactory factory : factories) {
                if (factory.handles(ref, creds)) {
                    BackendInstance instance = factory.create(ref, creds);
                    // Wrap: set instanceId on the backend if not already set
                    registry.register(new InstanceWrapper(
                        factory.backendKey(), instance.instanceId(), instance.backend()));
                }
            }
        }
    }
}
```

**Priority 75** — after `CloudSourceCredentialBootstrap` (50) seeds credentials, before `ModelRegistryRefresher` (100) populates models. All backend instances are registered before any model resolution occurs.

**CDI backends first:** CDI-discovered backends register with their inherited `instanceId()` (typically `"default"`). Factory-created instances may override a CDI backend's registration if they produce the same `(key, instanceId)` — last-write-wins. In practice this doesn't happen: factories produce non-default instance IDs.

### InstanceWrapper

A lightweight wrapper that delegates all `AgentBackend` methods to the underlying backend but overrides `key()` and `instanceId()`:

```java
record InstanceWrapper(String key, String instanceId, AgentBackend delegate) implements AgentBackend {
    @Override public Multi<AgentEvent> invoke(AgentSessionConfig config) {
        return delegate.invoke(config);
    }
    @Override public AgentSession openSession(AgentSessionInit init) {
        return delegate.openSession(init);
    }
}
```

Needed because factory-created backends (e.g., a second `OpenAiAgentBackend`) may not have the correct `instanceId()` — the factory produces the instance ID, the wrapper carries it.

## Router Refactor

### RoutingAgentProvider — modified

```java
@ApplicationScoped
public class RoutingAgentProvider implements AgentProvider {

    private final BackendInstanceRegistry registry;
    private final String defaultBackendKey;
    private final ModelRegistry modelRegistry;

    @Inject
    public RoutingAgentProvider(BackendInstanceRegistry registry,
                                RoutingAgentProperties properties,
                                ModelRegistry modelRegistry) {
        this.registry = registry;
        this.defaultBackendKey = properties.defaultBackend();
        this.modelRegistry = modelRegistry;
    }

    // Test constructor
    RoutingAgentProvider(BackendInstanceRegistry registry,
                         String defaultKey,
                         ModelRegistry modelRegistry) {
        this.registry = registry;
        this.defaultBackendKey = defaultKey;
        this.modelRegistry = modelRegistry;
    }

    @Override
    public Multi<AgentEvent> invoke(AgentSessionConfig config) {
        var route = resolve(config.model());
        var rewritten = new AgentSessionConfig(
            config.systemPrompt(), config.userPrompt(), config.mcpServers(),
            config.timeout(), config.correlationId(), route.apiModelId());
        return route.backend().invoke(rewritten);
    }

    @Override
    public AgentSession openSession(AgentSessionInit init) {
        var route = resolve(init.model());
        var rewritten = new AgentSessionInit(
            init.systemPrompt(), init.mcpServers(),
            init.timeout(), init.correlationId(), route.apiModelId());
        return route.backend().openSession(rewritten);
    }

    private ResolvedRoute resolve(String model) {
        if (model == null) {
            // No model specified — use default backend's default instance
            var backend = registry.resolve(defaultBackendKey, "default");
            if (backend.isEmpty()) {
                throw new IllegalArgumentException(
                    "Default backend not found: " + defaultBackendKey);
            }
            return new ResolvedRoute(backend.get(), null);
        }

        // Step 1: Registry lookup — backendKey + instanceId from descriptor
        Optional<ModelDescriptor> descriptor = modelRegistry.resolveById(model);
        if (descriptor.isPresent()) {
            var d = descriptor.get();
            String instanceId = d.backendInstanceId() != null
                ? d.backendInstanceId() : "default";
            var backend = registry.resolve(d.backendKey(), instanceId);
            if (backend.isEmpty()) {
                throw new IllegalArgumentException(
                    "No backend instance for: " + d.backendKey()
                    + "/" + instanceId + " (model: " + model + ")");
            }
            return new ResolvedRoute(backend.get(), d.apiModelId());
        }

        // Step 2: Fallback — treat model as backend key, default instance
        var backend = registry.resolve(model, "default");
        if (backend.isPresent()) {
            return new ResolvedRoute(backend.get(), null);
        }

        throw new IllegalArgumentException("No model or backend for: " + model);
    }

    private record ResolvedRoute(AgentBackend backend, String apiModelId) {}
}
```

**Changes from current:**
- Injects `BackendInstanceRegistry` instead of `Instance<AgentBackend>`
- `resolve()` step 1 uses compound `(backendKey, instanceId)` lookup via registry
- Step 2 fallback uses `registry.resolve(model, "default")` instead of `backends.get(model)`
- No more internal `Map<String, AgentBackend>` — registry owns all instance state

**Backward compatibility:** Existing models with `backendInstanceId = null` resolve to `"default"` instance. All current CDI-discovered backends register as `"default"`. Behavior is identical to the current router for all existing use cases.

## ModelDescriptor Change (platform-api)

```java
public record ModelDescriptor(
    String id,
    String apiModelId,
    String backendKey,
    String backendInstanceId,    // NEW — nullable, null = "default"
    String vendor,
    String family,
    String displayName,
    ModelTier tier,
    Set<String> capabilities,
    int contextWindow,
    int maxOutput,
    ModelLocality locality,
    CostTier costTier,
    String authMethod,
    Map<String, String> properties
) {
    public ModelDescriptor {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(apiModelId, "apiModelId");
        Objects.requireNonNull(backendKey, "backendKey");
        Objects.requireNonNull(vendor, "vendor");
        Objects.requireNonNull(family, "family");
        Objects.requireNonNull(displayName, "displayName");
        Objects.requireNonNull(tier, "tier");
        Objects.requireNonNull(locality, "locality");
        capabilities = capabilities != null ? Set.copyOf(capabilities) : Set.of();
        properties = properties != null ? Map.copyOf(properties) : Map.of();
        // backendInstanceId is nullable — null means "default"
    }
}
```

The new field is inserted after `backendKey` (logically grouped). All existing `ModelDescriptor` constructor calls must be updated to include the new field — typically `null` for existing sources (seed catalog, cloud sources, Ollama source). Future Vertex/Bedrock cloud sources will set it to `"vertex"` / `"bedrock"`.

**Constructor call migration:** Record constructors are positional. Every call site adding `null` after `backendKey` is mechanical but must be done across: `SeedCatalogModelSource`, `AnthropicCloudModelSource`, `OpenAiCloudModelSource`, `VertexCloudModelSource`, `BedrockCloudModelSource`, `OllamaClient.parseModelsResponse()`, `ConfiguredModelSource`, and all test files that construct `ModelDescriptor`.

## OpenAI Factory (agent-openai)

### OpenAiDirectBackendFactory

```java
@ApplicationScoped
public class OpenAiDirectBackendFactory implements BackendInstanceFactory {

    @Override
    public String backendKey() { return "openai"; }

    @Override
    public boolean handles(String credentialRef, Map<String, String> credentials) {
        // Handles any credential ref containing an OpenAI API key
        return credentials.containsKey("api-key")
            && credentialRef.contains("openai");
    }

    @Override
    public BackendInstance create(String credentialRef, Map<String, String> credentials) {
        String apiKey = credentials.get("api-key");
        String instanceId = deriveInstanceId(credentialRef);

        OpenAIClient client = OpenAIOkHttpClient.builder()
            .apiKey(apiKey)
            .build();

        var backend = new OpenAiAgentBackend(client,
            OpenAiAgentProperties.defaults());
        return new BackendInstance(instanceId, backend);
    }

    private String deriveInstanceId(String credentialRef) {
        // "cloud-openai" → "default"
        // "extra-openai" → "extra"
        // "tenant-X-openai" → "tenant-X"
        if ("cloud-openai".equals(credentialRef)) return "default";
        return credentialRef.replace("-openai", "");
    }
}
```

**Instance ID derivation:** The `cloud-openai` credential ref produces the `"default"` instance (matching the CDI-discovered `OpenAiAgentBackend`). Additional credential refs produce named instances. The factory replaces the CDI-discovered backend's registration for `"default"` — this is correct because the factory-created instance uses credential-store credentials while the CDI bean uses config-property credentials.

**OpenAiAgentBackend constructor:** Currently `OpenAiAgentBackend` constructs its `OpenAIClient` internally from `@ConfigMapping` properties. The factory needs to create a backend with a pre-configured client. This requires adding a constructor (or static factory method) to `OpenAiAgentBackend` that accepts a pre-built `OpenAIClient`:

```java
// In OpenAiAgentBackend — new constructor for factory use
OpenAiAgentBackend(OpenAIClient client, OpenAiAgentProperties properties) {
    super(properties.maxConcurrentSessions());
    this.client = client;
    this.properties = properties;
}
```

The existing CDI constructor (with `@Inject`) remains — CDI-discovered beans use config-driven construction. Factory-created beans use the new constructor with a pre-built client.

### Existing CDI-discovered OpenAiAgentBackend

The CDI-discovered `OpenAiAgentBackend` continues to work. It registers as `(openai, default)` via the coordinator's CDI iteration. If an `OpenAiDirectBackendFactory` also produces a `"default"` instance from `cloud-openai` credentials, the factory instance replaces the CDI one — last-write-wins in the coordinator (factories run after CDI registration).

This is the correct behavior: the factory-created instance uses credential-store credentials (seeded from env vars by `CloudSourceCredentialBootstrap`), which is the authoritative credential source. The CDI bean's config-property-based construction is the fallback.

## Test Strategy

1. **AgentBackend.instanceId() default** — verify existing backends return `"default"` without code changes.
2. **BackendRef** — equality, hashCode, requireNonNull validation.
3. **InMemoryBackendInstanceRegistry** — register, resolve by compound key, resolveByKey, last-write-wins replacement, empty resolution for unknown keys.
4. **BackendInstanceCoordinator** — mock `Instance<AgentBackend>`, `Instance<BackendInstanceFactory>`, `LlmCredentialStore`. Verify: CDI backends registered first; factory instances registered from credential-store entries; factories skipped for non-matching credential refs.
5. **RoutingAgentProvider refactor** — mock `BackendInstanceRegistry`, `ModelRegistry`. Verify: null model → default backend; model resolved via descriptor with `backendInstanceId` → correct (key, instanceId) lookup; model resolved via descriptor with null instanceId → "default" fallback; model as key fallback → `(model, "default")` lookup; unknown model → throw.
6. **ModelDescriptor** — verify new field is nullable, existing constructor patterns still work with explicit null.
7. **OpenAiDirectBackendFactory** — verify: `handles()` returns true for openai credential refs with api-key; false for non-openai refs; `create()` produces a backend with correct instanceId; `deriveInstanceId()` maps `cloud-openai` → `"default"`, other patterns → extracted name.
8. **Integration** — full flow: credential bootstrap seeds `cloud-openai` + `extra-openai` → coordinator creates two OpenAI backend instances → model with `backendInstanceId="extra"` resolves to the second instance → invocation succeeds.

## Files Changed

### New files in agent-api/

| File | Purpose |
|------|---------|
| `BackendRef.java` | `(key, instanceId)` compound key record |
| `BackendInstance.java` | Factory output record: `(instanceId, AgentBackend)` |
| `BackendInstanceFactory.java` | Per-module factory SPI |
| `BackendInstanceRegistry.java` | Instance management SPI |

### Modified in agent-api/

| File | Change |
|------|--------|
| `AgentBackend.java` | Add `default String instanceId() { return "default"; }` |

### New files in agent-router/

| File | Purpose |
|------|---------|
| `InMemoryBackendInstanceRegistry.java` | `@ApplicationScoped` ConcurrentHashMap implementation |
| `BackendInstanceCoordinator.java` | `@Observes StartupEvent @Priority(75)` — CDI + factory wiring |
| `InstanceWrapper.java` | Lightweight delegating wrapper for factory-created backends |

### Modified in agent-router/

| File | Change |
|------|--------|
| `RoutingAgentProvider.java` | Inject `BackendInstanceRegistry` instead of `Instance<AgentBackend>`. Compound key resolution. |

### Modified in platform-api/

| File | Change |
|------|--------|
| `ModelDescriptor.java` | Add nullable `backendInstanceId` field after `backendKey` |

### New files in agent-openai/

| File | Purpose |
|------|---------|
| `OpenAiDirectBackendFactory.java` | `BackendInstanceFactory` impl — creates OpenAI backends from credential-store API keys |

### Modified in agent-openai/

| File | Change |
|------|--------|
| `OpenAiAgentBackend.java` | Add package-private constructor accepting pre-built `OpenAIClient` |

### Modified across codebase (ModelDescriptor constructor migration)

| File | Change |
|------|--------|
| `SeedCatalogModelSource.java` | Add `null` for `backendInstanceId` in constructor calls |
| `AnthropicCloudModelSource.java` | Same |
| `OpenAiCloudModelSource.java` | Same |
| `VertexCloudModelSource.java` | Same |
| `BedrockCloudModelSource.java` | Same |
| `OllamaClient.java` | Same (in `parseModelsResponse()`) |
| `ConfiguredModelSource.java` | Same |
| All `ModelDescriptor` test files | Same |

### Unchanged

- `agent-claude/` — `ClaudeAgentProvider` inherits `instanceId() = "default"`, no code changes
- `agent-ollama/` — same
- `agent-gate/` — `@Decorator` wraps `AgentProvider` (the router), not individual backends
- `platform/` — `InMemoryModelRegistry` unchanged (stores what `ModelSource` provides)

## Known Limitations

1. **CDI-discovered backends always register as "default"** — existing backends (`ClaudeAgentProvider`, `OllamaAgentBackend`) don't override `instanceId()`. They can only be accessed via the `"default"` instance ID. This is correct for single-instance backends.
2. **Factory instances are not CDI beans** — they don't participate in CDI lifecycle (`@PreDestroy`, interceptors, decorators). For resource cleanup, factories must handle shutdown themselves or the coordinator must track and clean up factory-created instances.
3. **Credential-ref naming convention** — `OpenAiDirectBackendFactory.handles()` matches by credential ref name containing `"openai"`. This is a convention, not a typed contract. Mis-named credential refs won't match.
4. **ModelDescriptor constructor migration** — adding a positional field to a record is a mechanical but widespread change. Every constructor call site must be updated.
5. **No runtime registration API** — backend instances can only be created at startup via the coordinator. Runtime registration (e.g., admin adds a new API key via UI) requires a platform restart or a manual coordinator refresh. Follow-up: expose `registry.register()` via an admin API.
6. **Single tenant for factory instances** — the coordinator reads credential refs from `PLATFORM_TENANT_ID` only. Per-tenant backend instances require extending the coordinator to iterate tenants. Follow-up.

## Downstream (not this issue)

| Item | Description |
|------|-------------|
| VertexClaudeBackendFactory | Factory in `agent-vertex/` (new module) — creates Vertex Claude backend from GCP ADC credentials |
| BedrockClaudeBackendFactory | Factory in `agent-bedrock/` (new module) — creates Bedrock Claude backend from AWS SigV4 credentials |
| Claude CLI factory | Different activation model — Claude CLI reads config from system, not credential-store. May not need a factory. |
| Runtime registration | Admin API to register/deregister backend instances without restart |
| Per-tenant backends | Extend coordinator to create per-tenant backend instances from per-tenant credentials |
| Factory lifecycle management | `@PreDestroy` equivalent for factory-created backends (client shutdown, executor cleanup) |

## References

- #288 spec: `specs/issue-288-cloud-model-sources/2026-09-12-cloud-model-sources-design.md` — cloud source credential bootstrap, startup sequence
- #289 spec: `specs/issue-288-cloud-model-sources/2026-09-13-local-model-sources-design.md` — `AbstractOpenAiSdkBackend` extraction, Ollama backend
- AgentBackend.java — current SPI (3 methods, no instance identity)
- RoutingAgentProvider.java — current router (Map<String, AgentBackend>, 3-step resolve)
- ModelDescriptor.java — current record (14 fields, backendKey is plain String)
- LlmCredentialStore.java — credential store SPI (store/resolve/delete/listRefs)
- CloudSourceCredentialBootstrap.java — startup credential detection at @Priority(50)
- ModelRegistryRefresher.java — model refresh at @Priority(100)
- GE-20260810-804c58 — CaseHub AgentProvider CDI tiering
- GE-20260626-c21b02 — @DefaultBean suppressed by Instance<T> peer
- GE-20260606-cd1c61 — CDI bean collision with same interface type
- Epic #285 — parent: LLM model registry
