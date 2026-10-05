# DataRealism E2E Verification — Design Spec

**Issue:** casehubio/platform#517
**Branch:** issue-517-datarealism-e2e-verification
**Date:** 2026-10-05

## Problem

The simulation decorator processor (#512) wires `DataRealism` through
`JournalEntry` — non-null for strategy-resolved calls, null for delegate
fallthrough. But the design (connectors#138) expects three distinct paths:

1. **Deterministic** (delegate is authoritative) → `dataRealism = null`
2. **Strategy-resolved** → `dataRealism = strategy.dataRealism()`
3. **Interpretive fallthrough** (delegate handles but response is structurally
   valid, not domain-accurate) → `dataRealism = STRUCTURALLY_VALID`

Path 3 doesn't work — the decorator processor hardcodes `null` for all
delegate calls (SimulationDecoratorProcessor.java:403/409), making no
distinction between deterministic and interpretive fallthrough.

## Solution

Add `fallthrough-realism` as a per-method config field in `simulation.yaml`.
When the delegate path executes (no strategy, or strategy can't resolve),
the generated decorator consults the runtime for the method's configured
fallthrough realism level instead of hardcoding null.

## Architecture

### Config Layer

**SimulationConfig** (simulation-api): new default method:
```java
default DataRealism fallthroughRealism(String qualifiedName) {
    return null;
}
```

**YamlSimulationConfig.MethodConfig** (simulation-config-core): new field:
```java
record MethodConfig(
    String strategy,
    Boolean capture,
    ExhaustionPolicy exhaustionPolicy,
    String keyExtractor,
    String scorer,
    Double threshold,
    List<CorpusEntry> corpus,
    List<String> corpusFiles,
    DataRealism fallthroughRealism  // NEW
)
```

**simulation.schema.json**: add to method-config properties:
```json
"fallthrough-realism": {
  "type": "string",
  "enum": ["GARBAGE", "PLACEHOLDER", "STRUCTURALLY_VALID",
           "DOMAIN_PLAUSIBLE", "RECORDED_REAL"]
}
```

**YAML usage:**
```yaml
methods:
  commerce-platform.productSearch.search:
    strategy: seq
    fallthrough-realism: STRUCTURALLY_VALID
    corpus-files:
      - classpath:simulation/commerce/search-corpus.yaml

  commerce-platform.productSearch.searchByBrand:
    fallthrough-realism: STRUCTURALLY_VALID
    # no strategy — always falls through, but tagged as interpretive

  # cart/checkout/orderTracking: no entry → fallthrough = null (deterministic)
```

### Runtime Layer

**SimulationRuntime** (simulation-core): new method following the same
overlay-stack walk pattern as `strategyFor()`:
```java
public DataRealism fallthroughRealism(String qualifiedName) {
    for (int i = overlayStack.size() - 1; i >= 0; i--) {
        DataRealism level = overlayStack.get(i).config().fallthroughRealism(qualifiedName);
        if (level != null) return level;
    }
    return config.fallthroughRealism(qualifiedName);
}
```

### Code Generation Layer

**SimulationDecoratorProcessor** (simulation-generator): one-line change
in the delegate path of `generateSimulatedMethod()`:

Before:
```java
simulation.recordJournal(qualifiedName, __simTenancyId, input, result, null);
```

After:
```java
simulation.recordJournal(qualifiedName, __simTenancyId, input, result,
    simulation.fallthroughRealism(qualifiedName));
```

Both the void and non-void delegate paths change identically (lines 403
and 409).

## Testing

### Unit Tests

**SimulationConfig / YamlSimulationConfig:**
- Parse `fallthrough-realism` from YAML → verify `fallthroughRealism(qn)`
  returns correct `DataRealism` enum value
- Verify unconfigured methods return null
- Verify profile override of fallthrough-realism

**SimulationRuntime:**
- `fallthroughRealism(qn)` returns null with no config
- `fallthroughRealism(qn)` returns configured level from base config
- Overlay overrides base fallthrough-realism (LIFO)
- Overlay pop reverts to base level

**SimulationDecoratorProcessor:**
- Update existing text-comparison test to verify generated delegate path
  calls `simulation.fallthroughRealism(qualifiedName)` instead of `null`

### E2E Integration Test

**Location:** `platform-simulation-core/src/test/java`

**Pattern:** Same as existing `SimulatedAccessControlProviderTest` —
reflection-injected decorator, programmatic `SimulationConfig`,
`InMemorySimulationCorpus`.

**SPI:** `EndpointRegistry` via `SimulatedEndpointRegistry` (4 methods:
register, resolve, discover, deregister).

**Test configuration:** assign each method a different role:

| Method | Strategy | Fallthrough | Expected DataRealism |
|--------|----------|-------------|---------------------|
| `discover` | `key-lookup` (corpus seeded, key matches) | `STRUCTURALLY_VALID` | `DOMAIN_PLAUSIBLE` (from key-lookup strategy) |
| `resolve` | none | `STRUCTURALLY_VALID` | `STRUCTURALLY_VALID` (fallthrough) |
| `register` | none | none | `null` (deterministic) |
| `deregister` | `key-lookup` (corpus seeded, key won't match) | `STRUCTURALLY_VALID` | `STRUCTURALLY_VALID` (strategy can't resolve → fallthrough) |

**Test methods:**

1. `strategyResolvedPath()` — call `discover` with seeded corpus →
   verify `JournalEntry.dataRealism() == DOMAIN_PLAUSIBLE` (key-lookup
   strategy's level) and `allSimulated()` passes

2. `interpretiveFallthroughPath()` — call `resolve` (no strategy,
   fallthrough-realism configured) → verify
   `JournalEntry.dataRealism() == STRUCTURALLY_VALID` via journal
   entry inspection

3. `deterministicDelegatePath()` — call `register` (no strategy, no
   fallthrough-realism) → verify `JournalEntry.dataRealism() == null`
   and `noneSimulated()` passes

4. `strategyCannotResolveFallsThrough()` — call `deregister` (strategy
   configured but corpus empty, fallthrough-realism configured) →
   verify `JournalEntry.dataRealism() == STRUCTURALLY_VALID` from
   fallthrough, not from strategy

**Assertions use** `SimulationVerifier` (method().matching(predicate))
and direct `JournalEntry.dataRealism()` field checks.

## Modules Changed

| Module | Change |
|--------|--------|
| `simulation-api` | `SimulationConfig.fallthroughRealism()` default method |
| `simulation-config-core` | `MethodConfig` record + YAML parsing + schema |
| `simulation-core` | `SimulationRuntime.fallthroughRealism()` overlay walk |
| `simulation-generator` | Delegate path codegen: `null` → `simulation.fallthroughRealism(qn)` |
| `platform-simulation-core` | E2E test: `DataRealismEndToEndTest` |

## Acceptance Criteria

- [ ] `fallthrough-realism` field parsed from simulation.yaml
- [ ] `SimulationRuntime.fallthroughRealism(qn)` walks overlay stack
- [ ] Generated decorators use `fallthroughRealism(qn)` in delegate path
- [ ] E2E test covers all 3 DataRealism paths: null (deterministic),
  strategy-resolved (with level), fallthrough (STRUCTURALLY_VALID)
- [ ] E2E test covers strategy-can't-resolve fallthrough (4th path)
- [ ] Existing tests pass (no regression from delegate path change)

## References

- SimulationDecoratorProcessor.java:389-416 — delegate path code generation
- SimulationRuntime.java:52-66 — strategyFor overlay walk (pattern for fallthroughRealism)
- SimulatedAccessControlProviderTest.java — reflection injection test pattern
- connectors#138 design spec — deterministic vs interpretive classification
- platform#512 — DataRealism wired through simulation pipeline
- DataRealism.java — enum: GARBAGE, PLACEHOLDER, STRUCTURALLY_VALID, DOMAIN_PLAUSIBLE, RECORDED_REAL
- EndpointRegistryQN.java — qualified name constants for test assertions
