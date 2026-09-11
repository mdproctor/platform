## D1: Platform SPI shape — scoping dimension abstraction

**Choice:** String-keyed context for cross-vocabulary mapping
**Alternatives:**
- Map<String, String> context bag — over-engineered, no known use case needs multi-dimensional mapping context. YAGNI.
- Marker interface (MappingContext) — forces downcast, implementation needs concrete type anyway. String key is simpler and equally expressive.
**Rationale:** A free-form `mappingContext` string parameter replaces eidos's `DispositionAxis` enum without coupling platform-api to eidos types. Eidos's implementation maps string keys to DispositionAxis values internally via a switch statement. Other domains add their own context keys without platform changes. Consumers that don't need cross-vocab mapping only call `resolveLabel()`.
**Trade-offs:** No compile-time checking on context key validity — invalid keys silently produce empty results. Acceptable because context keys are domain-specific and the implementation validates them.
**Sources:** eidos DisplayTermResolver.java (DispositionAxis parameter), eidos DefaultDisplayTermResolver.java (VocabularyRegistry delegation), platform-api zero-dependency rule (CLAUDE.md)
**Exploration:** quick
**Status:** captured

## D2: Module placement — SPI and default implementation

**Choice:** SPI in `platform-api/`, no-op `@DefaultBean` in `platform/`
**Alternatives:**
- New `display/` module — unnecessary isolation for a single interface, adds module count for no benefit
**Rationale:** Follows the established SPI + @DefaultBean pattern (CaseMemoryStore, PreferenceProvider, CredentialResolver). No-op default returns raw value as passthrough when no vocabulary backend is installed. Eidos provides the real implementation via CDI priority. Zero new modules needed.
**Trade-offs:** None significant — this is the canonical platform pattern.
**Sources:** platform-api package structure, platform NoOpCaseMemoryStore @DefaultBean pattern, CLAUDE.md zero-dependency rule
**Exploration:** quick
**Status:** captured

## D3: No-op default behavior — passthrough vs exception

**Choice:** Passthrough — return raw value for resolveLabel, Optional.empty() for mapTerm
**Alternatives:**
- Throw to signal vocabulary not configured — breaks the "optional capability" pattern, forces try-catch in every consumer
**Rationale:** Silent passthrough matches the established no-op pattern (NoOpCaseMemoryStore returns empty, NoOpCredentialResolver returns empty). The system still works — raw values shown instead of labels. No consumer breaks. "Not installed" means "raw values shown."
**Trade-offs:** Consumers can't distinguish "vocabulary not installed" from "value has no label" — both return the raw value. Acceptable: the consumer's display is still correct (just not enriched).
**Sources:** NoOpCaseMemoryStore pattern, NoOpCredentialResolver pattern
**Exploration:** quick
**Status:** captured

## D4: Eidos migration scope

**Choice:** Follow-up issue on eidos (casehubio/eidos#171)
**Depends on:** D2 (platform SPI must be published before eidos can implement it)
**Alternatives:**
- Cross-repo branch — couples the release, eidos can't consume until platform artifact is available
**Rationale:** Consistent with every other platform SPI extraction (#279 SealedHierarchyModule, #280 ShorthandModule). Platform publishes first in build order, consumers adopt independently. Eidos bridges DefaultDisplayTermResolver to the platform SPI, maps string context keys to DispositionAxis internally.
**Trade-offs:** Eidos temporarily has two DisplayTermResolver interfaces (its own + platform's) until migration completes.
**Sources:** #279 decisions.md D3, #280 decisions.md D4, build order (platform publishes before eidos)
**Exploration:** quick
**Status:** captured
