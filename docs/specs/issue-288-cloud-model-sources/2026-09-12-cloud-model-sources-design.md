# Cloud Model Sources — Design Spec

**Issues:** casehubio/platform#288
**Date:** 2026-09-12
**Status:** Draft (R1 — revised post-review)

## Summary

Platform-global `ModelSource` beans that auto-discover available LLM models by calling vendor listing APIs. On startup, detect environment credentials (API keys, GCP ADC, AWS credential chain), populate the model registry with live vendor data, and guide users toward configuring any missing providers.

Four cloud sources — one per access path:
- **Anthropic (direct)** — `api.anthropic.com`
- **OpenAI (direct)** — `api.openai.com`
- **Vertex (Anthropic on GCP)** — Vertex AI Anthropic endpoint
- **Bedrock (Anthropic on AWS)** — Bedrock ListFoundationModels

These are distinct from per-tenant `ConfiguredModelSource` (from #291). Cloud sources are platform-global, use platform-level credentials, and are CDI-discovered by `ModelRegistryRefresher`. They provide the live truth for what models are available; the seed catalog (priority 0) is the air-gapped fallback.

**Onboarding motivation:** LLM/model configuration is one of the first hurdles for new CaseHub users. Cloud sources reduce this to zero-config in environments where credentials are already present, and provide clear guidance when they're not.

## Architecture

```
┌─────────────────────────────────────────────────────────────────┐
│  ModelRegistryRefresher (@Startup + @Scheduled 1h)              │
│  iterates Instance<ModelSource>, calls refresh() per source     │
├─────────────────────────────────────────────────────────────────┤
│  Cloud Model Sources (CDI beans, priority 5)                    │
│  ┌──────────────┐ ┌──────────────┐ ┌────────────┐ ┌──────────┐ │
│  │ Anthropic    │ │ OpenAI       │ │ Vertex     │ │ Bedrock  │ │
│  │ CloudModel   │ │ CloudModel   │ │ CloudModel │ │ CloudMod │ │
│  │ Source       │ │ Source       │ │ Source     │ │ elSource │ │
│  └──────┬───────┘ └──────┬───────┘ └─────┬──────┘ └────┬─────┘ │
│         │                │               │              │       │
│  ┌──────▼───────┐ ┌──────▼───────┐ ┌─────▼──────┐ ┌────▼─────┐ │
│  │ Anthropic    │ │ OpenAI       │ │ Vertex     │ │ Bedrock  │ │
│  │ Client       │ │ Client       │ │ Client     │ │ Client   │ │
│  │ (existing)   │ │ (existing)   │ │ (new)      │ │ (new)    │ │
│  └──────────────┘ └──────────────┘ └────────────┘ └──────────┘ │
├─────────────────────────────────────────────────────────────────┤
│  LlmCredentialStore (platform tenant scope)                     │
│  ← seeded by CloudSourceCredentialBootstrap @Startup            │
├─────────────────────────────────────────────────────────────────┤
│  Coexists with:                                                 │
│  - SeedCatalogModelSource (priority 0, classpath YAML)          │
│  - ConfiguredModelSource (priority 10, per-tenant, from wizard) │
└─────────────────────────────────────────────────────────────────┘
```

### Priority Model

| Source | Priority | ID format | Purpose |
|--------|----------|-----------|---------|
| Seed catalog | 0 | plain `apiModelId` | Static fallback, air-gapped |
| Cloud sources | 5 | plain `apiModelId` | Live vendor truth |
| Configured (per-tenant) | 10 | `vendor:tenancyId:apiModelId` | Tenant-specific config |

Cloud sources and seed catalog share the same ID namespace. `InMemoryModelRegistry` priority resolution means cloud (5) replaces seed (0) for the same model ID. Configured sources use a different ID namespace (tenant-scoped) — they coexist with cloud sources, not compete.

### Startup Sequence

1. `CloudSourceCredentialBootstrap` (`@Observes StartupEvent @Priority(50)`) — detects env vars and credential chains, seeds `LlmCredentialStore`
2. `ModelRegistryRefresher` (`@Startup`, default CDI priority 2500) — iterates all `ModelSource` beans **sorted by priority ascending** (R1-03), calls `refresh()`, populates registry. Seed catalog (priority 0) refreshes first, then cloud sources (priority 5), then configured sources (priority 10). This ensures higher-priority sources can seed-enrich from lower-priority data already in the registry.
3. Cloud sources find credentials already in the store, call vendor APIs, return models with full seed-enriched metadata

## Module Structure

Cloud source beans and the credential bootstrap live in `llm-config/`. Vertex and Bedrock VendorClients live in separate modules — following the platform's established `agent-*` pattern (R1-04).

### Why separate modules for Vertex/Bedrock?

Quarkus discovers CDI beans at build time via Jandex indexing. If `VertexClient` is in `llm-config/` with SDK classes as `optional` dependencies, the bean IS discovered and instantiated by CDI regardless of whether the SDK JARs are on the classpath. SDK class references in method bodies trigger `NoClassDefFoundError` at runtime — an `Error`, not an `Exception`. `ModelRegistryRefresher.refreshAll()` catches `Exception`, so the error propagates uncaught and crashes the entire refresh cycle.

Separate modules avoid this entirely: if `llm-config-vertex/` isn't on the classpath, its beans don't exist in the Jandex index, CDI never discovers them, and `Instance<VendorClient>` simply doesn't include them.

### Module layout

| Module | Contents | Dependencies |
|--------|----------|-------------|
| `llm-config/` | Cloud source beans, bootstrap, status API, AnthropicClient, OpenAiClient, GoogleClient, ConfiguredModelSource, wizard | platform-api, jackson, java.net.http |
| `llm-config-vertex/` | `VertexClient` VendorClient impl | platform-api, llm-config (VendorClient interface), google-auth-library-oauth2-http |
| `llm-config-bedrock/` | `BedrockClient` VendorClient impl | platform-api, llm-config (VendorClient interface), software.amazon.awssdk:auth, software.amazon.awssdk:regions |

Cloud source beans in `llm-config/` inject `@Any Instance<VendorClient>` and match by `vendorKey()`. If the Vertex/Bedrock module isn't deployed, the corresponding `VendorClient` is absent and the cloud source returns empty — no class loading errors, no `isResolvable()` gymnastics.

## Cloud Source Beans

Each cloud source follows the same pattern — thin `ModelSource` wrapper around a `VendorClient`:

```java
@ApplicationScoped
public class AnthropicCloudModelSource implements ModelSource {

    @Inject AnthropicClient client;
    @Inject LlmCredentialStore credentialStore;
    private volatile List<ModelDescriptor> lastKnownModels;

    @Override public String sourceId() { return "cloud:anthropic"; }
    @Override public int priority() { return 5; }

    @Override
    public List<ModelDescriptor> refresh() {
        Map<String, String> creds = credentialStore.resolve(
            TenancyConstants.PLATFORM_TENANT_ID, "cloud-anthropic");
        if (creds.isEmpty()) {
            return lastKnownModels != null ? lastKnownModels : List.of();
        }
        var result = client.listModels(creds);
        if (result.valid()) {
            lastKnownModels = result.models();
            return lastKnownModels;
        }
        return lastKnownModels != null ? lastKnownModels : List.of();
    }

    CloudSourceStatus status() { /* package-private, used by LlmConfigService */ }
}
```

### Resilience: last-known-good caching

Each cloud source caches `lastKnownModels` (volatile field). On API failure — network timeout, 503, rate limit — the source returns the last successful result instead of empty. This prevents transient vendor outages from wiping models out of the registry. Matches the `ConfiguredModelSource` resilience pattern.

Lifecycle:
- No credentials, no cache → `List.of()`
- No credentials, cache exists → return `lastKnownModels` (credential absence may be transient — e.g., in-memory store lost on restart after env var removal)
- First successful refresh → cache populated, returned
- Subsequent API failure → cache returned with WARN log

This aligns with `ConfiguredModelSource`'s behavior (R1-09) — both return the last known good result when credentials or API calls fail. The cloud source never actively clears its cache; the cache is only replaced by a successful refresh.

### Per-vendor configuration

| Bean | VendorClient | Credential ref | Source ID |
|------|-------------|----------------|-----------|
| `AnthropicCloudModelSource` | `AnthropicClient` (existing) | `cloud-anthropic` | `cloud:anthropic` |
| `OpenAiCloudModelSource` | `OpenAiClient` (existing) | `cloud-openai` | `cloud:openai` |
| `VertexCloudModelSource` | `VertexClient` (new) | `cloud-vertex` | `cloud:vertex` |
| `BedrockCloudModelSource` | `BedrockClient` (new) | `cloud-bedrock` | `cloud:bedrock` |

### Model ID format

Cloud sources use plain `apiModelId` as the registry key (e.g., `claude-sonnet-5`), identical to the seed catalog format. Priority resolution (cloud=5 > seed=0) means cloud entries replace seed entries for the same model. This is correct: cloud sources provide the same catalog as the seed, just live.

A model present only in the seed catalog (not returned by any cloud source) persists in the registry (R1-05). `InMemoryModelRegistry.rebuildView()` iterates sources in descending priority and uses `putIfAbsent` — models unique to the seed catalog (e.g., `mistral-large` with no Mistral cloud source) are added because no higher-priority source has that ID. Cloud sources only replace seed entries for models they also return.

## New VendorClients

### VertexClient — Anthropic on Google Cloud

```java
@ApplicationScoped
public class VertexClient implements VendorClient {

    @Override public String vendorKey() { return "vertex"; }
    @Override public String backendKey() { return "claude"; }
    @Override public String displayName() { return "Vertex AI (Anthropic)"; }
    @Override public String authMethod() { return "gcp-adc"; }
    @Override public List<String> requiredFields() { return List.of("project-id", "region"); }

    @Override
    public ValidationResult listModels(Map<String, String> credentials) {
        // 1. Resolve bearer token from GoogleCredentials (ADC)
        // 2. GET https://{region}-aiplatform.googleapis.com/v1/projects/{projectId}
        //        /locations/{region}/publishers/anthropic/models
        // 3. Parse response, seed-enrich, return ModelDescriptors
    }
}
```

- **Auth:** `GoogleCredentials.getApplicationDefault()` → `refreshIfExpired()` → `getAccessToken()`. Bearer token in Authorization header.
- **Credentials map:** `project-id` (from `GOOGLE_CLOUD_PROJECT`), `region` (from `CLOUD_ML_REGION` or config)
- **Response mapping:** Same seed enrichment strategy as other VendorClients — match `apiModelId` against seed catalog, merge metadata for known models, defaults for unknown.
- **Scope (R1-10):** Scoped to Anthropic's publisher endpoint (`/publishers/anthropic/models`) because models require a matching `AgentBackend` for invocation, and only the Claude backend exists. Gemini models on Vertex are discoverable via the `GoogleClient` in the wizard flow.
- **HttpClient (R1-08):** Holds a `private final HttpClient` field created at construction time — no per-call HttpClient creation. Same improvement applied to existing VendorClients.

### BedrockClient — Anthropic on AWS

```java
@ApplicationScoped
public class BedrockClient implements VendorClient {

    @Override public String vendorKey() { return "bedrock"; }
    @Override public String backendKey() { return "claude"; }
    @Override public String displayName() { return "Amazon Bedrock (Anthropic)"; }
    @Override public String authMethod() { return "aws-sigv4"; }
    @Override public List<String> requiredFields() { return List.of("region"); }

    @Override
    public ValidationResult listModels(Map<String, String> credentials) {
        // 1. Resolve AWS credentials via DefaultCredentialsProvider
        // 2. GET https://bedrock.{region}.amazonaws.com/foundation-models
        //    with SigV4-signed request via Aws4Signer
        // 3. Filter to Anthropic provider (Bedrock lists all vendors)
        // 4. Parse response, seed-enrich, return ModelDescriptors
    }
}
```

- **Auth:** `DefaultCredentialsProvider.create()` → `resolveCredentials()`. `Aws4Signer` signs each HTTP request (SigV4).
- **Credentials map:** `region` (from `AWS_REGION` or config)
- **Response filtering:** Bedrock's ListFoundationModels returns models from all providers. Filter to `providerName == "Anthropic"`.
- **Response mapping:** Same seed enrichment strategy. Bedrock provides richer metadata than the direct API (input/output modalities, customization support) — map where applicable.
- **HttpClient (R1-08):** Same reusable HttpClient pattern as VertexClient.

### backendKey design note (R1-11)

Both `VertexClient` and `BedrockClient` use `backendKey = "claude"`. This routes discovered models through the platform's Claude backend (direct API, CLI subprocess) — not through Vertex/Bedrock endpoints. This is correct for pre-release: there are no `VertexAgentBackend` or `BedrockAgentBackend` modules. When per-platform backends land, the `backendKey` for cloud-platform models will change (e.g., `"vertex-claude"`, `"bedrock-claude"`), requiring migration of any callers that resolved models by ID. Track as downstream.

## Credential Bootstrap

`CloudSourceCredentialBootstrap` — `@Observes StartupEvent @Priority(50)` (runs before `ModelRegistryRefresher` at `@Priority(100)`):

### Detection strategy

| Source | Detection | Credential ref | Stored fields |
|--------|-----------|---------------|---------------|
| Anthropic | `ANTHROPIC_API_KEY` env var | `cloud-anthropic` | `api-key` |
| OpenAI | `OPENAI_API_KEY` env var | `cloud-openai` | `api-key` |
| Vertex | `GoogleCredentials.getApplicationDefault()` succeeds + `GOOGLE_CLOUD_PROJECT` set | `cloud-vertex` | `project-id`, `region` |
| Bedrock | `DefaultCredentialsProvider.create().resolveCredentials()` succeeds + `AWS_REGION` set | `cloud-bedrock` | `region` |

### Precedence

- If `LlmCredentialStore` already has an entry for a credential ref (e.g., admin configured via wizard), the bootstrap does NOT overwrite it.
- Env vars only seed the store on startup — they are not re-checked on subsequent refresh cycles.
- Admin can override via the wizard at any time; the next refresh cycle picks up the new credentials.

### Startup log + user guidance

The bootstrap logs a summary on startup:

```
Cloud model sources:
  anthropic: active (ANTHROPIC_API_KEY detected)
  openai:    active (OPENAI_API_KEY detected)
  vertex:    inactive — set GOOGLE_APPLICATION_CREDENTIALS + GOOGLE_CLOUD_PROJECT
  bedrock:   inactive — configure AWS credentials + AWS_REGION
```

For each inactive source, the message tells the user exactly what to set. For active sources, it confirms what was detected.

### CloudSourceStatus query API

Status is also queryable via the `LlmConfigApi`:

```java
@PlatformQuery("List cloud model source status — active, inactive, or error with guidance")
List<CloudSourceStatus> cloudSourceStatus();
```

```java
public record CloudSourceStatus(
    String sourceId,       // "cloud:anthropic"
    String vendor,         // "Anthropic"
    State state,           // ACTIVE, INACTIVE, ERROR
    String message,        // "3 models discovered" or "set ANTHROPIC_API_KEY"
    int modelCount         // 0 when inactive
) {}

public enum State { ACTIVE, INACTIVE, ERROR }
```

This enables CLI/UI/MCP agents to show users their LLM configuration state and guide them through setup.

### Status assembly (R1-07)

`LlmConfigService.cloudSourceStatus()` injects `@Any Instance<CloudModelSource>` — a marker interface extending `ModelSource` that adds a `status()` method:

```java
public interface CloudModelSource extends ModelSource {
    CloudSourceStatus status();
}
```

All four cloud source beans implement `CloudModelSource` instead of raw `ModelSource`. This gives `LlmConfigService` a type-safe injection target without coupling to concrete classes. `ModelRegistryRefresher` still discovers them via `Instance<ModelSource>` (since `CloudModelSource extends ModelSource`).

## Seed Enrichment — Refresh Ordering (R1-03)

VendorClients inject `ModelRegistry` and query seed catalog data during `listModels()` to enrich sparse vendor API responses with curated metadata (tier, capabilities, contextWindow, etc.).

To ensure seed data is available when cloud sources refresh, `ModelRegistryRefresher.refreshAll()` sorts sources by `priority()` ascending before iterating. Seed catalog (priority 0) refreshes first and populates the registry, then cloud sources (priority 5) refresh and find seed data available for enrichment. This is a one-line change to `ModelRegistryRefresher` — sort the `Instance<ModelSource>` stream before processing.

## Known Limitations

1. **Vertex/Bedrock as separate modules (R1-04):** `VertexClient` and `BedrockClient` live in `llm-config-vertex/` and `llm-config-bedrock/` respectively. If the module isn't on the classpath, its beans don't exist in the Jandex index, CDI never discovers them, and `Instance<VendorClient>` simply doesn't include them. No class loading errors, no runtime guards needed.
6. **Discovery-invocation disconnect (R1-06):** Models discovered via Vertex/Bedrock are invoked through the platform's configured Claude backend (direct API), not through the cloud platform's endpoint. A user with GCP ADC but no direct Anthropic API key will see models discovered but not be able to invoke them until the platform's Claude backend credentials are configured. Dedicated `VertexAgentBackend` / `BedrockAgentBackend` modules are required for end-to-end cloud-platform invocation — tracked as downstream.
2. **Refresh frequency:** Cloud sources share the existing 1h refresh interval (`casehub.model.registry.refresh-interval`). Model catalogs change rarely (monthly new model releases). 4 API calls per hour is acceptable. Per-vendor refresh intervals are a follow-up if needed.
3. **No tenant scoping:** Cloud sources produce platform-global models. Tenant-specific model configuration is handled by `ConfiguredModelSource` from #291.
4. **Per-cycle credential lookups:** Each uncredentialed cloud source performs one `LlmCredentialStore.resolve()` call per refresh cycle. With `InMemoryLlmCredentialStore` (ConcurrentHashMap), this is negligible. With a network-backed store (Vault, KMS), 4 extra lookups per hour is acceptable but worth noting for capacity planning.
5. **Credential chain detection at startup only:** Env vars and SDK credential chains are checked once at startup. If credentials are added to the environment after startup, they must be configured via the wizard or require a restart. A future `refreshCredentials()` method on the bootstrap bean could address this.

## Test Strategy

1. **Cloud source beans (per vendor)** — mock `VendorClient` + `LlmCredentialStore`. Verify: credentials present → returns models; credentials absent → returns empty; API failure → returns last known good; credentials appear after initial empty → next refresh discovers models.
2. **VertexClient** — mock HTTP responses from Vertex AI Anthropic endpoint. Verify: model list → `ModelDescriptor` mapping, seed enrichment, ADC token refresh, error handling.
3. **BedrockClient** — mock HTTP responses from Bedrock ListFoundationModels. Verify: filters to Anthropic models, `ModelDescriptor` mapping, SigV4 signing applied, seed enrichment, error handling.
4. **CloudSourceCredentialBootstrap** — mock env vars and credential chains. Verify: detected vars populate store; existing store entries not overwritten; status log output correct; inactive sources show guidance messages.
5. **Startup ordering** — verify bootstrap runs before first refresh cycle (credential store populated before cloud sources are refreshed).
6. **Integration** — full flow: bootstrap seeds credentials → refresher runs → cloud source discovers models → `ModelRegistry.resolveById()` finds cloud-sourced model → priority 5 replaces seed catalog entry.
7. **CloudSourceStatus query** — verify status reflects current state (active/inactive/error, model count, guidance message per vendor).

## Files Changed

### New module: `llm-config-vertex/`

| File | Purpose |
|------|---------|
| `llm-config-vertex/pom.xml` | google-auth-library-oauth2-http dependency |
| `VertexClient.java` | VendorClient impl — Vertex AI Anthropic listing API, ADC auth |
| `VertexClientTest.java` | Unit tests |

### New module: `llm-config-bedrock/`

| File | Purpose |
|------|---------|
| `llm-config-bedrock/pom.xml` | software.amazon.awssdk:auth + regions dependencies |
| `BedrockClient.java` | VendorClient impl — Bedrock ListFoundationModels, SigV4 auth |
| `BedrockClientTest.java` | Unit tests |

### New files in `llm-config/`

| File | Purpose |
|------|---------|
| `CloudModelSource.java` | Marker interface extending ModelSource with status() |
| `AnthropicCloudModelSource.java` | CloudModelSource wrapping AnthropicClient, priority 5 |
| `OpenAiCloudModelSource.java` | CloudModelSource wrapping OpenAiClient, priority 5 |
| `VertexCloudModelSource.java` | CloudModelSource wrapping VertexClient (via Instance), priority 5 |
| `BedrockCloudModelSource.java` | CloudModelSource wrapping BedrockClient (via Instance), priority 5 |
| `CloudSourceCredentialBootstrap.java` | @Startup env/credential chain detection + store seeding |
| `CloudSourceStatus.java` | Status DTO + State enum for guidance API |
| `AnthropicCloudModelSourceTest.java` | Unit tests |
| `OpenAiCloudModelSourceTest.java` | Unit tests |
| `VertexCloudModelSourceTest.java` | Unit tests |
| `BedrockCloudModelSourceTest.java` | Unit tests |
| `CloudSourceCredentialBootstrapTest.java` | Unit tests |
| `CloudSourceStatusTest.java` | Integration test |

### Modified files

| File | Change |
|------|--------|
| `pom.xml` (root) | Add `llm-config-vertex`, `llm-config-bedrock` to modules list |
| `LlmConfigApi.java` | Add `cloudSourceStatus()` query method |
| `LlmConfigService.java` | Implement `cloudSourceStatus()` via `Instance<CloudModelSource>` |
| `AnthropicClient.java` | Reusable HttpClient field (R1-08) |
| `OpenAiClient.java` | Reusable HttpClient field (R1-08) |
| `GoogleClient.java` | Reusable HttpClient field (R1-08) |
| `ModelRegistryRefresher.java` | Sort sources by priority ascending before refreshing (R1-03) |

### Unchanged

- `platform-api/` — no SPI changes (CloudModelSource is in llm-config/, not an SPI)
- `InMemoryModelRegistry` — unchanged
- `agent-router/` — `RoutingAgentProvider` unchanged

## Downstream (not this branch)

| Item | Description |
|------|-------------|
| Per-vendor refresh intervals | Cloud sources may benefit from longer intervals (24h) given how rarely model catalogs change |
| Runtime credential refresh | Re-detect env vars without restart |
| OAuth2 token lifecycle | Token expiry/refresh management for long-running Vertex sessions |
| Bedrock model filtering | Filter by availability status, not just provider name |
| VertexAgentBackend / BedrockAgentBackend | Per-platform invocation backends so discovered models route through the cloud platform's endpoint, not the direct Anthropic API (R1-06) |
| backendKey migration | When per-platform backends land, Vertex/Bedrock models change backendKey from "claude" to platform-specific keys (R1-11) |

## References

- #291 spec: `specs/issue-291-llm-config-wizard-api/2026-09-12-llm-config-wizard-api-design.md`
- `VendorClient.java` — shared abstraction designed in #291 for reuse by #288
- `ConfiguredModelSource.java` — resilience pattern (lastKnownModels caching)
- `ModelRegistryRefresher.java` — CDI `Instance<ModelSource>` iteration, error isolation
- `SeedCatalogModelSource.java` — priority 0 pattern, plain model ID format
- `InMemoryModelRegistry.java` — priority resolution, `replaceSource()` semantics
- GE-20260804-face79 — Vertex AI rawPredict via java.net.http.HttpClient
- Epic #285 — parent: LLM model registry
