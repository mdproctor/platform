# MCP Tools for Model Registry — Design Spec

**Issue:** #292
**Scale:** S | **Complexity:** Low
**Branch:** issue-288-cloud-model-sources
**Date:** 2026-09-14

## Summary

Expose the model registry as a new `models` MCP domain — read-only browsing and forced refresh. Configuration and vendor operations remain in the existing `llm-config` domain. The interface lives in `platform-api` (zero-dep), the implementation in `platform/`, and the APT generates GraphQL + REST endpoints automatically.

## Scope

### In scope

- `ModelRegistryApi` interface in `platform-api` with `@McpDomain("models")`
- `ModelRegistryService` implementation in `platform/`
- `RefreshResult` record in `platform-api`
- `ModelRegistryEnricher` in `platform/`
- `ModelRegistryRefresher.refreshAll()` refactor to return aggregate delta

### Out of scope

- Moving operations from `llm-config` — `configure`, `unconfigure`, `pullModel`, etc. stay where they are
- Provider configuration wizard
- Model capability filtering by `requiredCapabilities` or `authMethod` (can be added later)

## Architecture

### Domain boundary

| Domain | Module | Purpose |
|--------|--------|---------|
| `models` | platform-api (interface) + platform (impl) | Read-only registry queries + refresh |
| `llm-config` | llm-config | Provider configuration, credential management, local model lifecycle |

An LLM agent browses models via `models`, configures providers via `llm-config`. No cross-dependency — `models` depends only on `ModelRegistry` SPI.

### New types in platform-api

#### ModelRegistryApi

```java
package io.casehub.platform.api.model;

@McpDomain("models")
public interface ModelRegistryApi {

    @PlatformQuery("List available models — filter by vendor, family, tier, locality, or cost tier. All params optional.")
    List<ModelDescriptor> listModels(String vendor, String family,
                                     String tier, String locality,
                                     String maxCostTier);

    @PlatformQuery("Get detailed model info by registry ID")
    ModelDescriptor getModel(String modelId);

    @PlatformMutation("Force refresh from all model sources — returns what changed")
    RefreshResult refreshRegistry();
}
```

The interface uses `@McpDomain`, `@PlatformQuery`, and `@PlatformMutation` annotations from `platform-api`. The `GraphQLResolverProcessor` APT generates `GeneratedModelsResolver` (GraphQL) and `GeneratedModelsResource` (REST at `/api/models/`). The `GraphQLModelScanner` discovers the generated resolver and registers it in the `DomainModelRegistry`. `DynamicToolRegistrar` exposes it via `casehub_action` and `casehub_activate`.

#### RefreshResult

```java
package io.casehub.platform.api.model;

public record RefreshResult(
    int sourcesRefreshed,
    int totalModels,
    int added,
    int removed,
    int updated
) {}
```

### Implementation in platform/

#### ModelRegistryService

```java
package io.casehub.platform.model;

@ApplicationScoped
public class ModelRegistryService implements ModelRegistryApi {

    @Inject ModelRegistry registry;
    @Inject ModelRegistryRefresher refresher;

    @Override
    public List<ModelDescriptor> listModels(String vendor, String family,
                                             String tier, String locality,
                                             String maxCostTier) {
        var builder = ModelQuery.builder();
        if (vendor != null && !vendor.isBlank()) builder.vendor(vendor);
        if (family != null && !family.isBlank()) builder.family(family);
        if (tier != null && !tier.isBlank()) builder.tier(ModelTier.valueOf(tier.toUpperCase()));
        if (locality != null && !locality.isBlank()) builder.locality(ModelLocality.valueOf(locality.toUpperCase()));
        if (maxCostTier != null && !maxCostTier.isBlank()) builder.maxCostTier(CostTier.valueOf(maxCostTier.toUpperCase()));
        return registry.query(builder.build());
    }

    @Override
    public ModelDescriptor getModel(String modelId) {
        return registry.resolveById(modelId)
            .orElseThrow(() -> new IllegalArgumentException("Unknown model: " + modelId));
    }

    @Override
    public RefreshResult refreshRegistry() {
        return refresher.refreshAllWithResult();
    }
}
```

All params are optional — `listModels(null, null, null, null, null)` returns the full registry (equivalent to `ModelQuery.all()`). String-to-enum conversion uses `valueOf()` — invalid values throw `IllegalArgumentException`, which the MCP layer wraps as a tool error via `@WrapBusinessError` on `CaseHubMcpTools`.

#### ModelRegistryEnricher

```java
package io.casehub.platform.model;

@McpDomain("models")
@ApplicationScoped
public class ModelRegistryEnricher implements ModelEnricher {

    @Inject ModelRegistry registry;

    @Override
    public String summary() {
        return "Model registry — query available LLM models across vendors, tiers, and capabilities";
    }

    @Override
    public Map<String, Object> state() {
        var all = registry.all();
        var vendors = all.stream().map(ModelDescriptor::vendor).distinct().sorted().toList();
        return Map.of(
            "modelCount", all.size(),
            "vendors", vendors
        );
    }
}
```

### Refactor: ModelRegistryRefresher

Current `refreshAll()` is void — it fires `ModelCatalogChangedEvent` per source but doesn't return a summary. Add a `refreshAllWithResult()` method that accumulates deltas:

```java
RefreshResult refreshAllWithResult() {
    int sourcesRefreshed = 0, added = 0, removed = 0, updated = 0;

    for (ModelSource source : sortedSources()) {
        try {
            List<ModelDescriptor> models = source.refresh();
            var delta = registry.replaceSource(source.sourceId(), source.priority(), models);
            sourcesRefreshed++;
            added += delta.addedIds().size();
            removed += delta.removedIds().size();
            updated += delta.updatedIds().size();
            if (delta.hasChanges()) {
                catalogChanged.fire(new ModelCatalogChangedEvent(
                    source.sourceId(), delta.addedIds(), delta.removedIds(), delta.updatedIds()));
            }
        } catch (Exception e) {
            LOG.warnf("Model source '%s' refresh failed: %s", source.sourceId(), e.getMessage());
        }
    }

    return new RefreshResult(sourcesRefreshed, registry.all().size(), added, removed, updated);
}
```

The existing `refreshAll()` delegates to `refreshAllWithResult()` and discards the return value — no behavioral change for existing callers.

### MCP tool hierarchy

After `casehub_activate("models")`:

| Tool name | Type | Description |
|-----------|------|-------------|
| `models_listModels` | Query | List available models with optional filters |
| `models_getModel` | Query | Get detailed model info by ID |
| `models_refreshRegistry` | Mutation | Force refresh from all sources |

Without activation, all operations are available via `casehub_action` with `domain: "models"`.

In the `casehub_model` catalog, the `models` domain shows:
```json
{
  "name": "models",
  "summary": "Model registry — query available LLM models across vendors, tiers, and capabilities",
  "operationCount": 3,
  "state": { "modelCount": 12, "vendors": ["anthropic", "google", "openai"] }
}
```

### Generated code

The `GraphQLResolverProcessor` APT produces:

- `io.casehub.platform.graphql.generated.GeneratedModelsResolver` — `@GraphQLApi @McpDomain("models") @ApplicationScoped`, injects `ModelRegistryApi`, delegates each method
- `io.casehub.platform.rest.generated.GeneratedModelsResource` — `@Path("/api/models") @ApplicationScoped`, injects `ModelRegistryApi`, maps queries to `@GET` with `@QueryParam`, mutations to `@POST`

No hand-written resolvers or REST resources needed.

### Testing

- **Unit tests** (`ModelRegistryServiceTest`): plain JUnit, mock `ModelRegistry` and `ModelRegistryRefresher`. Verify:
  - `listModels()` with no params returns `all()`
  - `listModels("anthropic", null, ...)` constructs correct `ModelQuery`
  - Invalid enum strings throw `IllegalArgumentException`
  - `getModel` with unknown ID throws
  - `refreshRegistry` delegates and returns aggregated result

- **MCP dispatch test** (`ModelRegistryMcpTest`): reflective scan of `ModelRegistryApi` interface per GE-20260818-c2f072. Verify annotations, parameter names, and dispatch without CDI.

- **Enricher test** (`ModelRegistryEnricherTest`): verify summary and state map contents.

- **ModelRegistryRefresher test update**: verify `refreshAllWithResult()` aggregates deltas correctly.

## References

- `platform-api/.../model/ModelRegistry.java` — SPI being exposed
- `platform-api/.../model/ModelDescriptor.java` — return type for queries
- `platform-api/.../model/ModelQuery.java` — query construction
- `platform/.../model/ModelRegistryRefresher.java` — refresh logic to refactor
- `llm-config/.../LlmConfigApi.java` — existing `@McpDomain` interface pattern
- `llm-config/.../LlmConfigService.java` — existing implementation pattern
- `graphql-generator/.../GraphQLResolverProcessor.java` — APT that generates GraphQL + REST
- `mcp/.../GraphQLModelScanner.java` — scanner that discovers generated resolvers
- `mcp/.../DynamicToolRegistrar.java` — tool registration from scanned domains
- GE-20260818-c2f072 — testing MCP domain dispatch without CDI
- GE-20260814-6b054e — validation errors as schema reinjection for hierarchical MCP
- Issue #292 — MCP tools for model registry
- Issue #285 — parent epic (LLM model registry)
