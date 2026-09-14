## D1: Cloud source architecture — wrap VendorClient

**Choice:** Each cloud model source is a thin `@ApplicationScoped ModelSource` bean that wraps the corresponding `VendorClient` + `LlmCredentialStore`. Per-vendor beans (AnthropicCloudModelSource, OpenAiCloudModelSource, VertexCloudModelSource, BedrockCloudModelSource), CDI-discovered by `ModelRegistryRefresher`. Each source caches `lastKnownModels` — on API failure, returns last successful result instead of empty (R1-04).
**Alternatives:**
- Independent standalone sources — own HTTP client + seed enrichment per source. Duplicates VendorClient logic.
- Single aggregate CloudModelSourceManager — one ModelSource for all vendors. Loses per-vendor error isolation.
**Rationale:** VendorClient was explicitly designed in #291 as "shared with #288." Reusing it avoids duplication and means new Vertex/Bedrock VendorClients serve both wizard and cloud flows. Per-vendor beans give individual error isolation (one vendor failing doesn't block others). Last-known-good caching matches ConfiguredModelSource's resilience pattern.
**Trade-offs:** Coupling to VendorClient interface — if VendorClient changes, cloud sources change too. Acceptable since VendorClient is stable and both flows share the same concern.
**Sources:** #291 spec §Vendor Clients (R1-05, R1-12), VendorClient.java, ConfiguredModelSource.java
**Exploration:** quick
**Status:** revised (R1-04 — added last-known-good caching)

## D2: Vendor scope — all four vendors with SDK-based auth

**Choice:** Four cloud model sources, each a distinct access path:
- **Anthropic (direct)** — `api.anthropic.com`, API key or `ANTHROPIC_API_KEY` env var. Reuses existing `AnthropicClient`.
- **OpenAI (direct)** — `api.openai.com`, API key or `OPENAI_API_KEY` env var. Reuses existing `OpenAiClient`.
- **Vertex (Anthropic on GCP)** — Vertex AI Anthropic endpoint, `google-auth-library-oauth2-http` for ADC (Application Default Credentials). New `VertexClient`.
- **Bedrock (Anthropic on AWS)** — `bedrock.{region}.amazonaws.com`, `software.amazon.awssdk:auth` for credential chain + SigV4 signing. New `BedrockClient`.
**Alternatives:**
- Anthropic + OpenAI only — leaves cloud-hosted Anthropic unsupported.
- Defer Vertex/Bedrock — punts the onboarding friction that motivated the issue.
**Rationale:** Making LLM configuration frictionless is vital for CaseHub onboarding. Auto-detection of environment credentials (API keys, GCP ADC, AWS credential chain) means zero-config startup in environments where LLM access is already set up. SDK dependencies are scoped to `llm-config/` only.
**Trade-offs:** Two new SDK dependencies (Google auth, AWS SDK auth). Scoped to llm-config/ — no platform-wide impact. Both are well-maintained, widely-used libraries.
**Sources:** Issue #288 scope, user clarification (Vertex = self-hosted Anthropic via GCP env vars)
**Exploration:** quick
**Status:** revised (R1-01 — corrected Vertex/Bedrock auth, added SDK deps)

## D3: Credential management — LlmCredentialStore at platform scope with env var bootstrap

**Choice:** Platform-global credentials stored in `LlmCredentialStore` at `TenancyConstants.PLATFORM_TENANT_ID` scope. A `@Startup` bootstrap bean detects well-known env vars and SDK credential chains, auto-populates the store if no entry exists. Admin can override via wizard.
Detected env vars: `ANTHROPIC_API_KEY`, `OPENAI_API_KEY`. Detected credential chains: Google ADC (`GoogleCredentials.getApplicationDefault()`), AWS default credentials (`DefaultCredentialsProvider.create()`).
**Alternatives:**
- Config properties + env var inference only — too rigid for dynamic configuration.
- LlmCredentialStore only, no env inference — more setup friction, no auto-detection.
- CredentialResolver (existing SPI) — general-purpose, not LLM-specific lifecycle.
**Rationale:** LlmCredentialStore is already the #291 credential SPI. Using it at platform scope gives dynamic control (add/remove credentials at runtime). Env var / credential chain bootstrap provides zero-friction activation.
**Trade-offs:** Two credential paths (env bootstrap + wizard) — store wins over env if entry exists. Env/credential chain detection runs only at startup.
**Sources:** #291 spec §Credential Storage, LlmCredentialStore SPI
**Exploration:** quick
**Status:** revised (R1-01 — expanded to include SDK credential chain detection)

## D4: Module location — llm-config/

**Choice:** Cloud model source beans live in `llm-config/` alongside VendorClient and ConfiguredModelSource.
**Alternatives:**
- New `cloud-models/` module — separates concerns but fragments model source logic.
- In `platform/` alongside SeedCatalogModelSource — would pull HTTP client code into platform/.
**Rationale:** VendorClients already live in llm-config/. Cloud sources reuse them. Module already has the right dependencies (platform-api, jackson, java.net.http). New SDK deps (Google auth, AWS auth) added as optional — only resolved when Vertex/Bedrock sources are used.
**Trade-offs:** llm-config/ grows — but it's cohesive (all model source management logic).
**Sources:** llm-config/pom.xml, #291 spec §Module
**Exploration:** quick
**Status:** captured

## D5: Priority — 5 (between seed and configured)

**Choice:** Cloud model sources at priority 5. Seed catalog is 0, configured sources are 10.
**Alternatives:**
- Priority 1 — just above seed, minimal disruption.
- Priority 15 — above configured, live data overrides tenant config.
**Rationale:** Live cloud data should beat static YAML (wins over seed=0 for the same model ID). Cloud sources and configured sources use different ID formats (plain vs tenant-scoped), so they coexist as separate registry entries — priority only governs the cloud-vs-seed interaction. The stated priority of "5 between seed and configured" is about the seed replacement semantics, not about configured sources overriding cloud sources.
**Trade-offs:** A model present in both cloud source and seed catalog will use cloud source metadata — which may be sparser. Mitigated by seed enrichment in VendorClient.
**Sources:** SeedCatalogModelSource (priority=0), ConfiguredModelSource (priority=10), InMemoryModelRegistry priority resolution
**Exploration:** quick
**Status:** revised (R1-02 — corrected rationale: cloud and configured sources don't compete on priority, they use different ID namespaces)

## D6: Activation model — always-present CDI beans, no-op when uncredentialed

**Choice:** All 4 cloud source beans are always CDI-managed. On refresh(), if credentials absent in LlmCredentialStore, return List.of() silently. When credentials appear, next refresh cycle discovers models.
**Alternatives:**
- @IfBuildProperty conditional activation — requires restart.
- Dynamic registration like ConfiguredModelSource — needs own refresh loop.
**Rationale:** Always-present beans are discovered by ModelRegistryRefresher without special handling. No-op refresh is cheap (one LlmCredentialStore lookup per uncredentialed source per cycle — ConcurrentHashMap read with InMemoryLlmCredentialStore). Credentials can appear at any time (env bootstrap, wizard) and take effect on next refresh cycle.
**Trade-offs:** 4 CDI beans always present even if unused. Per-cycle overhead: one credential store lookup per uncredentialed source. With a network-backed credential store (Vault), this would be 4 network calls per cycle — acceptable at 1h intervals but worth noting.
**Sources:** ModelRegistryRefresher (Instance<ModelSource> iteration), SeedCatalogModelSource pattern
**Exploration:** quick
**Status:** revised (R1-06 — qualified overhead claim)

## D7: Model ID format — plain apiModelId (same as seed catalog)

**Choice:** Cloud sources use plain `apiModelId` as the registry key (e.g., `claude-sonnet-5`), identical to seed catalog format. Priority resolution (cloud=5 > seed=0) means cloud entries replace seed entries for the same model.
**Alternatives:**
- Prefixed `cloud:{vendor}:{apiModelId}` — explicit but creates duplicate entries for the same model.
- Vendor-prefixed `{vendor}:{apiModelId}` — parsing ambiguity with tenant-scoped format `{vendor}:{tenancyId}:{apiModelId}`.
**Rationale:** Cloud sources and seed catalog serve the same purpose — providing the platform-global model catalog. Cloud sources are the live version of what the seed catalog provides statically. Priority resolution handles the overlap (higher priority wins). Tenant-configured sources use a different ID namespace (`vendor:tenancy:apiModelId`) and coexist independently.
**Trade-offs:** A model only in the seed catalog (not returned by the vendor API) disappears from the registry when the cloud source is active. Acceptable — if the vendor doesn't list it, it's not available.
**Sources:** SeedCatalogModelSource ID format, InMemoryModelRegistry priority resolution, #291 §Tenant Isolation
**Exploration:** quick
**Status:** captured

## D8: Startup ordering — credential bootstrap before model refresh

**Choice:** `CloudSourceCredentialBootstrap` uses `@Observes StartupEvent` with CDI `@Priority` ordering to run before `ModelRegistryRefresher`. This ensures credentials are in the store before the first refresh cycle attempts to use them.
**Alternatives:**
- No ordering guarantee — accept up to 1h cold-start gap where cloud catalog is empty.
- Bootstrap bean injects ModelRegistryRefresher and triggers manual refresh after seeding.
**Rationale:** CDI `@Priority` on `@Observes StartupEvent` is the standard ordering mechanism. A 1-hour gap with an empty catalog on first startup defeats the onboarding goal. Manual refresh trigger couples the bootstrap to the refresher.
**Trade-offs:** Priority ordering is fragile if other `@Startup` beans also need specific ordering. For this case, only one ordering constraint exists (bootstrap before refresh), which is manageable.
**Sources:** ModelRegistryRefresher @Startup, CDI @Priority spec
**Exploration:** quick (R1-03 — surfaced by decision review)
**Status:** captured
