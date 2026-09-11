# DisplayTermResolver — Design Spec

**Issue:** casehubio/platform#283
**Date:** 2026-09-11
**Status:** Draft

## Summary

Platform-wide SPI for resolving display labels from vocabulary terms and mapping terms across vocabularies. Two capabilities:

1. **Display label resolution** — `resolveLabel(value, vocabUri)` → human-readable label
2. **Cross-vocabulary mapping** — `mapTerm(value, sourceVocabUri, targetVocabUri)` → equivalent term in a different vocabulary, optionally scoped by a string context key

Zero-dependency SPI in `platform-api/`. No-op `@DefaultBean` passthrough in `platform/`. Eidos provides the real implementation via CDI priority (follow-up: casehubio/eidos#171).

## Problem

Multiple repos need vocabulary-aware display: blocks-ui (org diagrams, agent cards), qhorus (channel role display), engine (work item displays, routing explanations), platform admin (dashboards). Currently, eidos owns `DisplayTermResolver` with dependencies on `VocabularyRegistry`, `VocabularyTerm`, and `DispositionAxis` — all eidos-specific types. Consumers must pull in eidos-api just for display label resolution.

## Approach

Extract a generic platform SPI that eidos implements. The platform SPI uses string-based parameters only — no eidos type imports. Eidos's implementation maps string context keys to `DispositionAxis` values internally.

## API

```java
package io.casehub.platform.api.display;

public interface DisplayTermResolver {

    String resolveLabel(String value, String vocabUri);

    Optional<String> mapTerm(String value, String sourceVocabUri,
                             String targetVocabUri);

    Optional<String> mapTerm(String value, String sourceVocabUri,
                             String targetVocabUri, String mappingContext);
}
```

**`resolveLabel`** — returns the human-readable label for `value` in the vocabulary identified by `vocabUri`. Falls back to the raw `value` when the vocabulary is not registered or the value is not found.

**`mapTerm`** — maps `value` from one vocabulary to another. Returns `Optional.empty()` when no mapping exists. The `mappingContext` parameter scopes the mapping (e.g., eidos's disposition axes: `"autonomy"`, `"adaptability"`). Domains define their own context keys — platform doesn't constrain them.

## No-op default

```java
package io.casehub.platform.display;

@DefaultBean
@ApplicationScoped
public class NoOpDisplayTermResolver implements DisplayTermResolver {

    @Override
    public String resolveLabel(String value, String vocabUri) {
        return value;
    }

    @Override
    public Optional<String> mapTerm(String value, String sourceVocabUri,
                                    String targetVocabUri) {
        return Optional.empty();
    }

    @Override
    public Optional<String> mapTerm(String value, String sourceVocabUri,
                                    String targetVocabUri, String mappingContext) {
        return Optional.empty();
    }
}
```

Passthrough behavior: `resolveLabel` returns the raw value, `mapTerm` returns empty. The system still works — raw values shown instead of enriched labels. Matches `NoOpCaseMemoryStore` and `NoOpCredentialResolver` patterns.

## Test strategy

### Platform-side tests (this branch)

1. `NoOpDisplayTermResolver.resolveLabel` returns raw value
2. `NoOpDisplayTermResolver.mapTerm` returns empty
3. `NoOpDisplayTermResolver.mapTerm` with context returns empty
4. `resolveLabel` with null value returns null

### Eidos-side tests (casehubio/eidos#171)

5. `DefaultDisplayTermResolver` resolves label from registered vocabulary
6. `DefaultDisplayTermResolver` maps term across vocabularies
7. `DefaultDisplayTermResolver` maps with context key → DispositionAxis
8. Invalid context key → treats as unscoped mapping
9. Unregistered vocabulary → falls back to raw value

## Files changed

| File | Action |
|------|--------|
| `platform-api/src/main/java/io/casehub/platform/api/display/DisplayTermResolver.java` | New — SPI interface |
| `platform/src/main/java/io/casehub/platform/display/NoOpDisplayTermResolver.java` | New — @DefaultBean passthrough |
| `platform/src/test/java/io/casehub/platform/display/NoOpDisplayTermResolverTest.java` | New — unit tests |

## Downstream

| Issue | Repo | Dependency |
|-------|------|------------|
| casehubio/eidos#171 | casehubio/eidos | Bridge DefaultDisplayTermResolver to platform SPI |

## Future scope (not this branch)

- **Locale-aware labels** — `resolveLabel(value, vocabUri, Locale)` for i18n. Separate issue.
- **Vocabulary discovery** — `listVocabularies()`, `listTerms(vocabUri)` for UI pickers. Separate concern from resolution.

## References

- `io.casehub.eidos.api.DisplayTermResolver` — eidos SPI (source implementation)
- `io.casehub.eidos.runtime.display.DefaultDisplayTermResolver` — eidos implementation (63 lines)
- `io.casehub.eidos.api.VocabularyRegistry` — vocabulary data layer (eidos-specific)
- `io.casehub.eidos.api.VocabularyTerm` — vocabulary term model (eidos-specific)
- `io.casehub.platform.api.memory.CaseMemoryStore` — no-op @DefaultBean pattern reference
- `io.casehub.platform.api.credentials.CredentialResolver` — no-op @DefaultBean pattern reference
- casehubio/eidos#170 — initial DisplayTermResolver implementation in eidos (closed)
- casehubio/eidos#171 — eidos bridge to platform SPI (filed)
