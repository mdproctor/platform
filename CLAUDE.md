# casehub-platform Workspace

**Name:** casehub-platform

**Physical path:** `/Users/mdproctor/claude/casehub/platform/CLAUDE.md`
**Symlinked at:** `/Users/mdproctor/claude/public/casehub/platform/CLAUDE.md`
**Project repo:** `/Users/mdproctor/claude/casehub/platform`
**Workspace:** `/Users/mdproctor/claude/public/casehub/platform`
**Workspace type:** public

## Session Start

Run `add-dir /Users/mdproctor/claude/casehub/platform` and `add-dir /Users/mdproctor/claude/public/casehub/platform` before any other work.

## Artifact Locations

| Skill | Writes to |
|-------|-----------|
| brainstorming (specs) | `specs/` |
| writing-plans (plans) | `plans/` |
| handover | `HANDOFF.md` |
| idea-log | `IDEAS.md` |
| design-snapshot | `snapshots/` |
| java-update-design / update-primary-doc | `design/JOURNAL.md` (created by `epic`) |
| adr | `adr/` |
| write-blog | `blog/` |

## Git Discipline

Two git repositories are active in every session:
- **Workspace** (`/Users/mdproctor/claude/public/casehub/platform`) — plans, blog, specs, snapshots, handover
- **Project repo** (`/Users/mdproctor/claude/casehub/platform`) — source code, ADRs

Never rely on CWD for git operations:
```bash
git -C /Users/mdproctor/claude/public/casehub/platform ...   # workspace artifacts
git -C /Users/mdproctor/claude/casehub/platform ...          # project artifacts
```

Two remotes are configured on the project repo:
- `origin` → `casehubio/platform` (canonical)
- `mdproctor` → `mdproctor/platform` (personal fork)

**Git hooks:** `.githooks/pre-push` is committed. Activate on each clone:
```bash
git config core.hooksPath .githooks
```

Push to both after squash or significant merges:
```bash
git push --force-with-lease origin main
git push --force mdproctor main   # --force on first push after fork creation
```

## Routing

| Artifact   | Destination | Notes |
|------------|-------------|-------|
| adr        | project     | lands in `adr/` |
| protocols  | garden      | `/Users/mdproctor/claude/casehub/garden/docs/protocols/` — never create local protocol files |
| specs      | project     | lands in `docs/` |
| blog       | project     | lands in `docs/blog/` — promoted at work end |
| plans      | workspace   | stay in workspace permanently |
| design     | workspace   | epic journal stays in workspace |
| snapshots  | workspace   | |
| handover   | workspace   | |


Living docs — check for drift after significant changes:
- `ARC42STORIES.MD` — primary architecture record; check §4 (layer taxonomy), §5 (building block view), §8 (new layers), §13 (glossary) after module, SPI, or structural changes

## Platform Docs
- [Platform Index](https://raw.githubusercontent.com/casehubio/parent/main/docs/INDEX.md) — discovery index (start here)
- [Building Platform](https://raw.githubusercontent.com/casehubio/parent/main/docs/guides/building-platform.md) — platform contributor guide

## Repo Guide

This repo owns its own documentation, synced to parent via CI:
- `docs/guides/consumer-guide.md` — for app builders: modules, APIs, quick start
- `docs/guides/contributor-guide.md` — for platform builders: architecture, SPIs, internals

Update the relevant guide in the same session when implementation changes modules, SPIs, or public APIs. Do not defer — drift compounds.

Read `docs/guides/consumer-guide.md` for app-level work. Only read `docs/guides/contributor-guide.md` when modifying this repo's internals or extension points.

## Rules

- `platform-api/` must remain zero-dependency — no Quarkus, no JPA, no casehubio imports. Pure Java only.
- `platform/` contains Quarkus @DefaultBean implementations only — no domain logic
- Every SPI in platform-api gets a @DefaultBean implementation in platform
- Two @DefaultBean patterns exist:
  - **Configurable mock** (PreferenceProvider, CurrentPrincipal): returns values driven by @ConfigProperty — suitable when tests need to set specific return values
  - **Silent no-op** (CaseMemoryStore): always returns empty/void — suitable when the capability is optional and "not installed" means "nothing happens"
- `config/` reads YAML preference files at startup — declare as compile scope in production; not needed on test-only classpaths (mock handles test defaults via `application.properties`)
- `yaml-core/` must remain zero-dependency (J2CL-transpilable) — YAML processing primitives only
- `yaml-codegen/` is a Maven plugin (`maven-plugin` packaging) generating Java records and POJOs from JSON Schema. Depends on jsonschema2pojo-core for schema parsing. Sibling to `yaml-core` — different dependency profile (build-time vs runtime zero-dep)
- `yaml-jackson/` provides Jackson mixins for yaml-core types. Depends on yaml-core + jackson-databind only. Dynamic section capture via `@JsonAnySetter` builder, case-insensitive enum deserialization via `MapperFeature.ACCEPT_CASE_INSENSITIVE_ENUMS`

## Project Type

type: java

## Repository Role

Zero-dependency foundational SPIs and types shared across all casehub modules. Publishes before everything else in the build order.

**Build order position:** immediately after casehub-parent BOM. No casehubio dependencies.

**Consumers (do not modify these repos — raise issues instead):**
- casehub-ledger, casehub-work, casehub-qhorus, casehub-engine, claudony, devtown, aml, clinical

## Build Commands

```bash
mvn --batch-mode install
mvn --batch-mode deploy -DskipTests   # CI only — requires GITHUB_TOKEN
```

## Modules

| Module | Artifact | Purpose |
|--------|----------|---------|
| `platform-api/` | `casehub-platform-api` | Pure Java SPIs — zero deps |
| `platform/` | `casehub-platform` | Quarkus @DefaultBean implementations (configurable mocks + no-ops) + `NoOpPreferenceStore @DefaultBean` + `NoOpPreferenceSchemaRegistry @DefaultBean` + `PlatformPreferenceRegistrar @ApplicationScoped` (@Startup — registers 10 PreferenceSchemaDescriptors from PlatformPreferenceKeys (6 retention + engagement toggle + retry limit + digest retention + view cache TTL)) + `DataSourceRouter @ApplicationScoped` (CDI CloudEvent → DataSource bridge) + `CloudEventTypeDispatcher @ApplicationScoped` (re-fires CloudEvents with `@CloudEventType` qualifier for type-specific CDI observers) |
| `testing/` | `casehub-platform-testing` | @Alternative identity fixtures — no Quarkus runtime. FixedCurrentPrincipal @Priority(200) beats OidcCurrentPrincipal in tests; InMemoryGroupMembershipProvider @Priority(1) |
| `config/` | `casehub-platform-config` | Scope-aware YAML + SmallRye Config PreferenceProvider — displaces mock when on classpath |
| `oidc/` | `casehub-platform-oidc` | @Alternative @Priority(100) @RequestScoped OIDC-backed CurrentPrincipal — displaces all non-alternative CurrentPrincipal impls when on classpath. Reads actorId/groups from SecurityIdentity, tenancyId from JWT claim |
| `expression/` | `casehub-platform-expression` | Pluggable expression engines — DefaultExpressionEngineRegistry (@ApplicationScoped, CDI-discovers ExpressionEngine beans), MvelExpressionEngine (MVEL3 transpiler, lazy compilation, ConcurrentHashMap cache), JQExpressionEngine (jackson-jq wrapper, Boolean/List/scalar result types — ScalarJQExpression unwraps first JQ output element to target type via ObjectMapper, MapAdaptedJQExpression for Map<String,Object> context — converts via ObjectMapper.valueToTree before delegating to JsonNode-based evaluation). Also retains JQEvaluator for backward-compat scope injection ($secret, $config). MVEL3 dep: `org.mvel:mvel3:3.0.0-SNAPSHOT` (JBoss Nexus snapshots) |
| `persistence-jpa/` | `casehub-platform-persistence-jpa` | JPA-backed PreferenceProvider + JpaPreferenceStore @ApplicationScoped — scope-aware, hierarchy-resolved, tenant-filtered. Flyway V2 adds `tenancy_id`. No quarkus:build goal (CurrentPrincipal only on test classpath). Add as compile dep; consumers must add `classpath:db/platform/migration` to Flyway locations |
| `persistence-mongodb/` | `casehub-platform-persistence-mongodb` | MongoDB-backed PreferenceProvider + MongoPreferenceStore @Alternative @Priority(1) — beats JPA when co-deployed. Tenant-filtered, compound `_id` includes tenancyId. No Flyway; startup bean creates scope index. No quarkus:build goal (CurrentPrincipal only on test classpath) |
| `memory-inmem/` | `casehub-platform-memory-inmem` | @Alternative @Priority(10) volatile CaseMemoryStore — ConcurrentHashMap, constructor-injected CurrentPrincipal, no quarkus:build goal. Add as test scope for @QuarkusTest isolation; compile for ephemeral installs. Do NOT combine with memory-jpa or memory-sqlite in the same scope |
| `memory-jpa/` | `casehub-platform-memory-jpa` | @ApplicationScoped JPA CaseMemoryStore — PostgreSQL, Flyway V1000 (`classpath:db/memory/migration`), FTS via websearch_to_tsquery when question provided. No quarkus:build goal (CurrentPrincipal only on test classpath). Use @TestTransaction not @Transactional in tests |
| `memory-sqlite/` | `casehub-platform-memory-sqlite` | @Alternative @Priority(1) SQLite CaseMemoryStore — xerial JDBC + HikariCP (WAL mode) + FTS5 + Flyway programmatic. Configure `casehub.memory.sqlite.path`. No quarkus:build goal. Do NOT combine with memory-inmem or memory-jpa in the same scope |
| `memory-mem0/` | `casehub-platform-memory-mem0` | @Alternative @Priority(1) Mem0 REST CaseMemoryStore — vector embeddings via Mem0 OSS (Docker + pgvector), infer:false (verbatim storage). Tenant isolation via compound `user_id={tenantId}::{entityId}` (Mem0 OSS has no app_id). GET /memories unbounded; limit client-side. RELEVANCE uses POST /search with top_k + threshold. Configure: `quarkus.rest-client.mem0.url`, `casehub.memory.mem0.api-key`. No quarkus:build goal. `*IT.java` tests require Ollama — excluded from `mvn test`, run via `mvn verify`. Do NOT combine with memory-inmem or memory-sqlite in same scope |
| `memory-graphiti/` | `casehub-platform-memory-graphiti` | @Alternative @Priority(2) Graphiti REST GraphCaseMemoryStore — temporal knowledge graph (Neo4j/FalkorDB/Kuzu via Graphiti OSS). LLM entity extraction (async). group_id={tenantId}::{entityId}::{domain} (domain is the partition key — entity relationships span cases within a domain). ERASE_DOMAIN_CASE: domain-level deletion via DELETE /group (cascading, complete); case-level via DELETE /episode (best-effort, EpisodicNode only). ERASE_ENTITY: requires `casehub.memory.graphiti.known-domains` (comma-separated domain list). RELEVANCE/graphQuery() → POST /search per entity; CHRONOLOGICAL → GET /episodes/{group_id} per entity. Configure: `quarkus.rest-client.graphiti.url`, `casehub.memory.graphiti.api-key`. No Flyway. Do NOT combine with other @Priority(2) adapters |
| `datasource-alpha/` | `casehub-platform-datasource-alpha` | Rete alpha network runtime — AlphaDataSource, TypeNode, FilterNode, FanOutProcessor. Shared by datasource-inmem and datasource-jpa. Pure runtime — no CDI, no persistence |
| `datasource-inmem/` | `casehub-platform-datasource-inmem` | @Alternative @Priority(100) volatile InMemoryDataSourceRegistry — ConcurrentHashMap, reference-counted self-pruning (AlphaDataSource.markForRemoval). Idempotent register, lifecycle-aware deregister with CDI event. No Flyway, no quarkus:build goal. Do NOT combine with datasource-jpa in same scope |
| `datasource-jpa/` | `casehub-platform-datasource-jpa` | @ApplicationScoped JPA DataSourceRegistry — Hibernate ORM (blocking-only). PostgreSQL, Flyway V4000 (`classpath:db/datasource/migration`). @Observes StartupEvent reconciles persisted descriptors. No quarkus:build goal. Do NOT combine with datasource-inmem in production scope |
| `endpoints-memory/` | `casehub-platform-endpoints-memory` | @Alternative @Priority(100) volatile InMemoryEndpointRegistry — ConcurrentHashMap, tenant-filtered, platform-global visibility. Tier 4 CDI priority (beats JPA and NoSQL adapters). Data lost on restart. Add test scope for @QuarkusTest isolation; compile scope for ephemeral installs. Do NOT combine with a JPA endpoints backend in same scope |
| `endpoints-config/` | `casehub-platform-endpoints-config` | @Startup @ApplicationScoped YAML-backed endpoint populator — reads `casehub.platform.endpoints.files` at startup, parses into EndpointDescriptor records, calls EndpointRegistry.register(). Populator, not a registry implementation — populates whichever EndpointRegistry CDI selects. Requires a working registry backend (e.g. endpoints-memory) to be meaningful; silently registers into NoOpEndpointRegistry @DefaultBean otherwise (startup log reveals this). Multi-file: later files replace earlier files for same (path, tenancyId). No lifecycle reconciliation. Path separator read directly from casehub.platform.path.separator — no dependency on PathParserConfigurator. |
| `scim/` | `casehub-platform-scim` | @ApplicationScoped SCIM 2.0 GroupMembershipProvider — displaces @DefaultBean mock. Auth: casehub.platform.scim.token (static) or quarkus.oidc-client.scim.* (client-credentials). @CacheResult on membersOf(). Pagination: casehub.platform.scim.member-page-size (default 1000). No quarkus:build goal |
| `identity/` | `casehub-platform-identity` | CompositeDIDResolver @ApplicationScoped — iterates @DIDMethod-qualified DIDResolver beans by @Priority. Three method resolvers: WebDIDResolver @Priority(100) (did:web HTTPS fetch, Multibase publicKeyMultibase decoding), KeyDIDResolver @Priority(100) (did:key Multibase/base58btc decode, Ed25519 + P-256 + secp256k1, canonical SPKI — secp256k1 via manual ASN.1, no JCA), ScimDIDResolver @Priority(1000) (synthetic DIDDocument from SCIM x509Certificates via ScimAgentLookup shared cache). CompositeActorDIDProvider @ApplicationScoped — iterates @ActorDIDSource-qualified ActorDIDProvider beans by @Priority. Two providers: ConfiguredActorDIDProvider @ActorDIDSource @Priority(100) (config-based DID mapping), ScimActorDIDProvider @ActorDIDSource @Priority(200) (SCIM-backed, graceful when unconfigured). ScimAgentLookup @ApplicationScoped — shared SCIM HTTP client + cache. CdiPriorityUtils — shared priority sorting for both composites. @ApplicationScoped JwtVCValidator (W3C VC JWT credential validation, EdDSA/ES256, file-based credentials via `casehub.identity.credentials."actorId"`, TTL-cached via AbstractCachingIdentityProvider — EXPIRED never cached per SPI contract). Config prefix: casehub.identity.* |
| `credentials-quarkus/` | `casehub-platform-credentials-quarkus` | @Alternative @Priority(1) CredentialResolver bridge to Quarkus CredentialsProvider. @Any Instance injection with @PostConstruct fail-fast. Enables Vault/AWS/GCP secret backends by classpath presence. No quarkus:build goal |
| `agent-api/` | `casehub-platform-agent-api` | AgentProvider SPI (`invoke()` single-shot + `openSession()` multi-turn) + AgentBackend (implementor-facing SPI: `key()` + `invoke()` + `openSession()`) + AgentSession (multi-turn: serial query/interrupt/close) + AgentSessionInit (session config, no userPrompt, nullable `model`) + AgentSessionConfig (single-shot config, nullable `model`), AgentRuntime (SPI: `spawn(AgentRuntimeConfig)`) + AgentProcess (stdin/stdout/stderr/exitCode/destroy) + AgentRuntimeConfig (command, args, env, workingDirectory), AgentEvent (sealed: TextDelta, ThinkingDelta, ToolCallDelta, ToolCallComplete, ToolResult, InvocationComplete — terminal cost/usage/timing metadata), AgentMcpServer, typed exceptions (AgentProcessException, AgentRateLimitException, AgentSessionLimitException, AgentTimeoutException). Mutiny only — no Quarkus. Package: `io.casehub.platform.agent` |
| `agent-runtime/` | `casehub-platform-agent-runtime` | `SubprocessRuntime @ApplicationScoped implements AgentRuntime` — local process execution for CLI agent providers. `LocalAgentProcess` (package-private) wraps `java.lang.Process`. No quarkus:build goal |
| `agent-claude/` | `casehub-platform-agent-claude` | `ClaudeAgentProvider @ApplicationScoped implements AgentBackend` (key: "claude") + `ClaudeAgentClient @Startup` — discovered by RoutingAgentProvider via `Instance<AgentBackend>`. Requires Claude CLI. Concurrent-session semaphore. Single-shot: `run()`. Multi-turn: `openSession()` returns `ClaudeAgentSession` (IDLE/ACTIVE/CLOSED state machine, per-turn timeout, true-drain close). Package: `io.casehub.platform.agent.claude` |
| `agent-openai/` | `casehub-platform-agent-openai` | `OpenAiAgentBackend @ApplicationScoped implements AgentBackend` (key: "openai") — native OpenAI Java SDK (v4.50.0) with prompt_cache_key support. Streaming ChatCompletionChunk → AgentEvent mapping. Semaphore-gated concurrent sessions. Config: casehub.platform.agent.openai.{api-key, default-model, prompt-cache-retention, default-timeout, max-concurrent-sessions}. No quarkus:build goal |
| `agent-codex/` | `casehub-platform-agent-codex` | `CodexAgentBackend @ApplicationScoped implements AgentBackend` (key: "codex") — spawns Codex CLI via AgentRuntime. Subprocess I/O → AgentEvent (line-by-line TextDelta). Semaphore-gated. Config: casehub.platform.agent.codex.{binary-path, default-timeout, max-concurrent-sessions}. No quarkus:build goal |
| `agent-gemini/` | `casehub-platform-agent-gemini` | `GeminiAgentBackend @ApplicationScoped implements AgentBackend` (key: "gemini") — native Google GenAI SDK (v1.65.0) with explicit cachedContent support. Config: casehub.platform.agent.gemini.{api-key, default-model, cache-ttl, default-timeout, max-concurrent-sessions}. No quarkus:build goal |
| `agent-gemini-cli/` | `casehub-platform-agent-gemini-cli` | `GeminiCliAgentBackend @ApplicationScoped implements AgentBackend` (key: "gemini-cli") — spawns Gemini CLI via AgentRuntime. Same subprocess pattern as agent-codex. Config: casehub.platform.agent.gemini-cli.{binary-path, default-timeout, max-concurrent-sessions}. No quarkus:build goal |
| `agent-langchain4j/` | `casehub-platform-agent-langchain4j` | Bidirectional LangChain4j interop: `ChatModelAgentProvider @ApplicationScoped implements AgentBackend` (key: "langchain4j") — catch-all fallback for unknown provider keys. `AgentProviderChatModel` wraps AgentProvider (gets the router) as ChatModel (`@DefaultBean @Priority(10)`). `AgentSessionChatModel` (plain wrapper for caller-managed sessions). No quarkus:build goal |
| `agent-router/` | `casehub-platform-agent-router` | `RoutingAgentProvider @ApplicationScoped implements AgentProvider` — dispatches to AgentBackend implementations by key via `Instance<AgentBackend>`. Config: casehub.platform.agent.default-backend (default: "claude"). LangChain4j is the catch-all fallback for unknown keys. No quarkus:build goal |
| `agent-gate/` | `casehub-platform-agent-gate` | CDI @Decorator rate limiter for AgentProvider — token bucket (throughput) + Semaphore (concurrency). @Decorator @Priority(APPLICATION = 2000) wraps RoutingAgentProvider. Deferred admission via Multi.createFrom().deferred() + runSubscriptionOn(workerPool). Per-query rate limiting in GatedAgentSession. Compensating token release on concurrency failure. Idempotent close guard (AtomicBoolean CAS — prevents double semaphore release). SessionRegistry @ApplicationScoped (monotonic IDs, ConcurrentHashMap tracking). SessionLeakReaper @Scheduled (warn → force-close → evict escalation, config validation). Config: casehub.platform.agent.gate.{max-concurrent, permits-per-second, burst-capacity, acquire-timeout, query-acquire-timeout, reaper.scan-interval, reaper.warn-threshold, reaper.force-close-enabled, reaper.force-close-threshold, reaper.max-registry-age}. Ships ordinal-200 config override disabling provider semaphores. No quarkus:build goal |
| `notifications-inmem/` | `casehub-platform-notifications-inmem` | @Alternative @Priority(100) volatile InMemoryNotificationStore — ConcurrentHashMap, CDI events, bounded-size retention. No quarkus:build goal. Do NOT combine with notifications-jpa in production scope |
| `notifications-jpa/` | `casehub-platform-notifications-jpa` | @ApplicationScoped JPA NotificationStore — Hibernate ORM + @Transactional. PostgreSQL, Flyway V1 (`classpath:db/notification/migration`). CDI events via fireAsync(). @Scheduled retention purge (90d read/dismissed, 365d unread). No quarkus:build goal. Do NOT combine with notifications-inmem in production scope |
| `notifications/` | `casehub-platform-notifications` | REST API + push delivery — `@Path("/notifications")` JAX-RS endpoints (list, unread-count, markRead, dismiss, markAllRead, preferences, mute, snooze, channels) + `NotificationPushService` (CDI event observers → `EventBroadcaster.broadcast()` for WebSocket push via `casehub-pages-push`: `notifications:{userId}:new/updated/unread-count` topics) + PreferenceValidator (BUFFER_FOR_DIGEST validation on preference update). Uses NotificationStore (blocking, `@RunOnVirtualThread` on REST endpoints). CurrentPrincipal-enforced tenant + user isolation. No persistence logic |
| `notification-settings-inmem/` | `casehub-platform-notification-settings-inmem` | @Alternative @Priority(100) volatile InMemoryNotificationPreferenceStore + InMemorySuppressionStore — ConcurrentHashMap, lazy expiry eviction. No quarkus:build goal. Do NOT combine with notification-settings-jpa in production scope |
| `notification-settings-jpa/` | `casehub-platform-notification-settings-jpa` | @ApplicationScoped JPA NotificationPreferenceStore + SuppressionStore — Hibernate ORM Panache (blocking-only). PostgreSQL, Flyway V1 (`classpath:db/notification-settings/migration`). JSON columns for channelDefaults + quietHours. @Scheduled retention purge for expired mutes/snoozes. No quarkus:build goal. Do NOT combine with notification-settings-inmem in production scope |
| `delivery-channel-inmem/` | `casehub-platform-delivery-channel-inmem` | @ApplicationScoped InMemoryDeliveryChannelRegistry — ConcurrentHashMap, startup-populated. Production implementation — channels are static. Extracted from notification-dispatch |
| `notification-dispatch/` | `casehub-platform-notification-dispatch` | NotificationDispatcher (@ObservesAsync SubscriptionMatched) + TargetResolver + SuppressionEvaluator (evaluate + evaluateUserLevel) + ChannelRouter (digested flag + guaranteedMinSeverity) + InAppNotificationDeliverer + DigestFlushScheduler (@Scheduled tick, per-key error isolation, polymorphic isFlushDue, orphan drain, suppression deferral) + DeliveryTracker (delivery attempt recording, payload serialization, retry eligibility) + DeliveryRetryProcessor (@Scheduled tick, exponential backoff, per-attempt error isolation, DeliveryExhausted CDI event) + TemplateResolver + EngagementRecorder (engagement event construction, opt-in gate via `casehub.delivery.engagement.enabled`) + EngagementCallbackResource (@Path("/delivery/engagement") — SPI callback + direct recording) + InAppEngagementBridge (@ObservesAsync NotificationStatusChanged → engagement events). Orchestrates: target resolution → suppression → template resolution → channel routing → delivery (immediate or digest buffer) → tracking → retry. No quarkus:build goal |
| `digest-inmem/` | `casehub-platform-digest-inmem` | @Alternative @Priority(100) volatile InMemoryDigestBuffer — ConcurrentHashMap, max-size eviction via `casehub.notification.digest.max-buffer-size`. No quarkus:build goal. Do NOT combine with digest-jpa in production scope |
| `digest-jpa/` | `casehub-platform-digest-jpa` | @ApplicationScoped JPA DigestBuffer — Hibernate ORM Panache (blocking-only). PostgreSQL, Flyway V2000 (`classpath:db/digest/migration`). One row per notification, JSON payload. Configurable max-buffer-size (default 0 = no eviction). No quarkus:build goal. Do NOT combine with digest-inmem in production scope |
| `delivery-tracking-inmem/` | `casehub-platform-delivery-tracking-inmem` | @Alternative @Priority(100) volatile InMemoryDeliveryAttemptStore — ConcurrentHashMap, size-based eviction via `casehub.delivery.tracking.inmem.max-size` (default 10000). Engagement events in second ConcurrentHashMap with eviction cascade. No quarkus:build goal. Do NOT combine with delivery-tracking-jpa in production scope |
| `delivery-tracking-jpa/` | `casehub-platform-delivery-tracking-jpa` | @ApplicationScoped JPA DeliveryAttemptStore — Hibernate ORM Panache (blocking-only). PostgreSQL, Flyway V3000 (`classpath:db/delivery-tracking/migration`) — consolidated schema (delivery_attempt + engagement_event tables, sourceId/sourceType decoupling, FK ON DELETE SET NULL). EngagementEventEntity. `claimRetryable()` via `SELECT FOR UPDATE SKIP LOCKED` + claim-timeout advancement. Independent per-source-type retention: attemptRetentionPurge (03:00, per DeliverySourceType, status-aware) + engagementRetentionPurge (03:30). Config: casehub.delivery.retention.{attempt-days,failed-attempt-days,engagement-days} with per-source-type overrides. No quarkus:build goal. Do NOT combine with delivery-tracking-inmem in production scope |
| `platform-view/` | `casehub-platform-view` | Runtime orchestration — SubjectViewEvaluator `@ApplicationScoped` (membership evaluation: label paths × view patterns, scope-aware filtering, before/after computeEvents → ADDED/REMOVED/CHANGED events). SubjectViewOrchestrator `@ApplicationScoped` (evaluator + store + tracker composition, TTL-based view cache via `casehub.view.cache.ttl-seconds`, saveView/deleteView (proactive membership cleanup, REMOVED events, cache invalidation), single-subject and batch evaluation, scope-aware overloads). Pure Java + CDI, no persistence. No quarkus:build goal |
| `platform-view-inmem/` | `casehub-platform-view-inmem` | @Alternative @Priority(100) volatile InMemorySubjectViewStore + InMemoryViewMembershipTracker — ConcurrentHashMap. InMemorySubjectViewQuerySupport abstract helper (parallel to JpaLabelPatternQuerySupport — domains extend with concrete types, constructor takes subject source + label extractor + tenancy extractor + sort field resolver). Lightweight production (single-node) and test isolation. No quarkus:build goal. Do NOT combine with platform-view-jpa in production scope |
| `platform-view-jpa/` | `casehub-platform-view-jpa` | @ApplicationScoped JPA SubjectViewStore + ViewMembershipTracker — Hibernate ORM Panache (blocking-only). PostgreSQL, Flyway V5000 (`classpath:db/view/migration`). JpaLabelPatternQuerySupport abstract class for domain consumers to build thin SubjectViewQuery implementations with efficient SQL joins (Criteria API + Metamodel). LabelPatternPredicates (LIKE prefix escaping). No quarkus:build goal. Do NOT combine with platform-view-inmem in production scope |
| `subscriptions-inmem/` | `casehub-platform-subscriptions-inmem` | @Alternative @Priority(100) volatile InMemorySubscriptionStore — ConcurrentHashMap, CDI events. No quarkus:build goal. Do NOT combine with subscriptions-jpa in production scope |
| `subscriptions-jpa/` | `casehub-platform-subscriptions-jpa` | @ApplicationScoped JPA SubscriptionStore — Hibernate ORM + @Transactional. PostgreSQL, Flyway V1 (`classpath:db/subscription/migration`). JSON columns for filters + template. CDI events via fireAsync(). No quarkus:build goal. Do NOT combine with subscriptions-inmem in production scope |
| `subscriptions/` | `casehub-platform-subscriptions` | SubscriptionEngine (@ApplicationScoped) + REST API (@Path("/subscriptions") CRUD + enable/disable + `GET /subscriptions/event-types` discovery). InMemoryEventTypeRegistry (@ApplicationScoped ConcurrentHashMap). Registers platform-global notification DataSource, wires subscriptions into alpha network via EventTypeObjectType (supports glob patterns: `io.casehub.work.workitem.*`) + ExpressionEngineRegistry-compiled filters (SubscribableEvent-based tenant check, $me variable binding). Fires SubscriptionMatched CDI event (async) — delivery delegated to NotificationDispatcher in notification-dispatch/. POJOs must implement SubscribableEvent. ExpressionEvaluator-based filters (MVEL, JQ). Jackson ExpressionEvaluatorModule for polymorphic filter serialization. No quarkus:build goal |
| `acl-inmem/` | `casehub-platform-acl-inmem` | @Alternative @Priority(10) volatile AccessControlProvider — ConcurrentHashMap (separate grants + denies maps), constructor-injected GroupMembershipProvider + CurrentPrincipal, tenant-aware GrantKey + ParentKey, specificity-based canAccess (resolveAt: instance deny/grant → wildcard deny/grant → parent chain), action hierarchy via AclAction.satisfiedBy(), deny cascade via AclAction.deniedBy(), wildcard type-level grants (type:*), accessibleResourcesIncludingInherited (reverse parent-map traversal, deny-filtered), no quarkus:build goal. Add as test scope for @QuarkusTest isolation. Do NOT combine with acl-jpa in the same scope |
| `acl-admin/` | `casehub-platform-acl-admin` | REST API (`@Path("/acl")`) for ACL administration — grant/revoke/deny/removeDeny (+ batch), registerParent, canAccess check, paginated accessibleResources. `@RunOnVirtualThread` for blocking SPI calls. `@RolesAllowed(PlatformRoles.ADMIN)` on mutations; queries enforce self-access guard (non-admin callers can only query their own actorId). DTOs: AclEntryInput, ParentInput, AccessCheckResponse. No quarkus:build goal |
| `acl-worker/` | `casehub-platform-acl-worker` | Reusable WorkerCredentialFilter @Provider (token lookup, expiry, tenancy validation, resource scope enforcement via WorkerScopeExtractor SPI). FailClosedWorkerScopeExtractor @DefaultBean — rejects all worker credentials unless a real ScopeExtractor is provided (fail-closed by default). Add as compile dep to services receiving worker requests. Do NOT add to services that don't receive worker requests |
| `acl-jpa/` | `casehub-platform-acl-jpa` | @ApplicationScoped JPA AccessControlProvider — Hibernate ORM Panache (blocking-only), PostgreSQL, Flyway V3 (`classpath:db/acl/migration`). V2 adds tenancy_id to unique constraint + composite PK. V3 adds entry_type column (ALLOW/DENY) + expanded unique constraint. Tenant-filtered queries; reads bypass for isCrossTenantAdmin, mutations always scoped. Group-based grants via GroupMembershipProvider.groupsOf(). Deny entries via deny()/removeDeny()/denyBatch()/removeDenyBatch(). Specificity-based canAccess (resolveAt: instance deny/grant → wildcard deny/grant → parent chain). Deny cascade via AclAction.deniedBy(). Wildcard type-level grants (type:*). Resource parent inheritance with depth guard (20). Audit logging (GRANT/REVOKE/DENY/REVOKE_DENY) with tenancy. AclRetentionPurge @Scheduled: expired entry purge (daily 03:00, covers both ALLOW and DENY) + audit log purge (daily 03:30, default 365d). Action hierarchy via AclAction.satisfiedBy(). Paginated accessibleResources (keyset cursor, deny-filtered). accessibleResourcesIncludingInherited (recursive CTE on resource_parent, deny-filtered). grantBatch/revokeBatch/denyBatch/removeDenyBatch (@Transactional). Config: casehub.acl.retention.{expired-purge-cron, audit-purge-cron, audit-days}. No quarkus:build goal. Do NOT combine with acl-inmem in the same scope |
| `streams-kafka/` | `casehub-platform-streams-kafka` | @Startup @ApplicationScoped static Kafka channel ingestion — @Incoming("casehub-kafka-stream"), always raw byte[], builds CloudEvent from STREAM_EVENT_TYPE; sets datacontenttype when STREAM_DATA_CONTENT_TYPE present on descriptor. Does NOT observe EndpointRegistered. CAMEL and KAFKA are mutually exclusive for same topic. |
| `streams-amqp/` | `casehub-platform-streams-amqp` | @Startup @ApplicationScoped static AMQP channel ingestion — single address per channel (no multi-address; for multi-queue fan-in use streams-camel). Does NOT observe EndpointRegistered. |
| `streams-webhook/` | `casehub-platform-streams-webhook` | @Startup @ApplicationScoped JAX-RS receiver — POST /streams/webhook/{tenancyId}/{streamId}, structured CloudEvents HTTP binding (application/cloudevents+json), preserves incoming CloudEvent fields, enriches tenancyid from descriptor. Requires casehub.streams.webhook.public-url config. |
| `streams-poll/` | `casehub-platform-streams-poll` | @Startup @ApplicationScoped @Scheduled HTTP GET poller — java.net.http.HttpClient field, explicit status code check (HttpClient.send() does not throw for 4xx/5xx), per-endpoint exception handling. |
| `streams-camel/` | `casehub-platform-streams-camel` | @ApplicationScoped dynamic Camel route builder — @Observes StartupEvent discovers pre-startup CAMEL endpoints, @ObservesAsync EndpointRegistered for runtime additions (idempotent via routedUris set). P0: URI change requires restart. |
| `preferences-editor/` | `casehub-platform-preferences-editor` | REST API (`@Path("/preferences")`) for writing preferences to any backend + `GET /preferences/schema` (read-only type metadata for UI editors, ETag conditional GET via `PreferenceSchemaRegistry.version()` — returns 304 Not Modified when schema unchanged). `InMemoryPreferenceSchemaRegistry @ApplicationScoped` (ConcurrentHashMap + AtomicLong version counter, displaces `NoOpPreferenceSchemaRegistry @DefaultBean`). Depends on `PreferenceStore` SPI (writes), `PreferenceProvider` SPI (resolved view), and `PreferenceSchemaRegistry` SPI (schema metadata). Scope via `@QueryParam`, tenancyId from `CurrentPrincipal`. Separate DELETE endpoints for single-delete vs namespace-delete (accidental bulk prevention). No quarkus:build goal |
| `graphql/` | `casehub-platform-graphql` | GraphQL foundation — `@CustomScalar("JSON")` Json scalar (Map<String,Object> ↔ JSON string via Jackson + SmallRye `CustomStringScalar`), shared `PageInput` (@Input: offset/limit/cursor) + `PageInfo` (@Type: hasNext/hasPrevious/totalCount/cursor) + `PageResult<T>` (record: items + pageInfo) pagination types, `PaginationHelper.paginate(list, page, mapper)` (static utility — eliminates per-resolver pagination boilerplate), `GraphQLError` (@Type: RFC 7807 — type URI/title/status/detail/extensions). All domain GraphQL modules depend on this. No quarkus:build goal |
| `graphql-client/` | `casehub-graphql-client` | Typed CaseHub GraphQL client — per-domain `@GraphQLClientApi(configKey="casehub")` interfaces: `CaseClient` (cases/startCase/suspend/resume/cancel), `WorkItemClient` (workItems/claim/start/complete/delegate/cancel), `LedgerClient` (ledgerEntries/trustScore), `QhorusClient` (channels). Client-side DTOs in `io.casehub.client.dto`. Configure via `quarkus.smallrye-graphql-client.casehub.url`. No quarkus:build goal |
| `mcp/` | `casehub-platform-mcp` | MCP hierarchical model — `casehub_model` (`@Tool`, navigable operation catalog) + `casehub_action` (dynamic ToolManager registration with JSON Schema input — `SchemaMode.SIMPLE` description-based catalog or `SchemaMode.RICH` if-then per-domain enums, config: `casehub.mcp.schema-mode`). `GraphQLModelScanner` auto-discovers MCP domains at startup via CDI BeanManager: (1) `@GraphQLApi` resolvers annotated with `@McpDomain` (scans `@Query`/`@Mutation`/`@Subscription`), (2) CDI beans implementing `@McpDomain` interfaces (scans `@PlatformQuery`/`@PlatformMutation` — no GraphQL codegen required). GraphQL path takes precedence when both register the same domain. Fires `ModelScanComplete` CDI event. `DynamicToolRegistrar` observes event, builds schema via `McpSchemaBuilder`, registers tool. `ReflectiveOperationDispatcher` handles dispatch. Merges `ModelEnricher` beans for domain summaries + live state. Annotation-filtered dispatch (`@Query`/`@Mutation` or `@PlatformQuery`/`@PlatformMutation` methods invocable). CDI proxy resolution for interceptor correctness. Jackson ObjectMapper with JavaTimeModule. `McpResourceRegistryBridge @ApplicationScoped` (bridges McpResourceRegistry SPI → quarkus ResourceManager/ResourceTemplateManager/CompletionManager, virtual-thread handlers, optional server scoping via `casehub.mcp.server-name`, CDI events on registration, `McpResourceUpdated` observer). `DomainResourceRegistrar @ApplicationScoped` (observes ModelScanComplete → registers `casehub://domain-index` static resource + `casehub://domains/{domain}` template with completions). `DomainContentFormatter` (shared JSON formatting for domain catalog — used by CaseHubMcpTools and DomainResourceRegistrar). No quarkus:build goal |
| `governance/` | `casehub-platform-governance` | `PolicyEnforcer @ApplicationScoped` — generic retry/timeout/backoff/circuit-breaker enforcement for blocking operations. `ExecutionPolicy(timeoutMs, RetryPolicy, CircuitBreakerPolicy)` + `BackoffStrategy` (FIXED/EXPONENTIAL/EXPONENTIAL_WITH_JITTER) + `CircuitBreakerPolicy(failureThreshold, recoveryWindowMs)` in platform-api. `DefaultPolicyEnforcer` shares a virtual-thread executor (`Executors.newVirtualThreadPerTaskExecutor()`) with `@PreDestroy` shutdown — do NOT create per-call executors. Must not be called from Vert.x event-loop threads; callers are responsible for worker-thread execution context. No Flyway. No quarkus:build goal. |
| `callback-api/` | `casehub-platform-callback-api` | Pure Java callback registration SPI — `CallbackRegistration` (record: id, spiName, callbackUrl, credentialRef, tenancyId, timeoutMs, metadata, registeredAt, expiresAt, lastHeartbeatAt), `CallbackRegistrationRequest` (record: spiName, callbackUrl, credentialRef, tenancyId, timeoutMs, ttlSeconds, metadata), `CallbackRegistry` (SPI: register/deregister/heartbeat/findBySpi/findById), `CallbackRegistered`/`CallbackDeregistered` CDI events. Lease + graceful deregister model. Zero external dependencies |
| `callback-inmem/` | `casehub-platform-callback-inmem` | @Alternative @Priority(100) volatile InMemoryCallbackRegistry — ConcurrentHashMap, upsert-by-(spiName,url,tenancyId), lease expiry filtering on findBySpi, registeredAt ordering. No quarkus:build goal. Do NOT combine with callback-jpa in production scope |
| `callback/` | `casehub-platform-callback` | CallbackInvoker @ApplicationScoped — HTTP POST with retry via PolicyEnforcer (3x exponential backoff with jitter), Jackson serialization, X-CaseHub-Invocation-Id header for idempotency, Preferences→MapPreferences abstract type mapping, @PreDestroy HttpClient shutdown. LeaseReaper @Scheduled(every=60s) — purges expired registrations. CallbackRegistrationResource @Path("/casehub/callbacks") @RolesAllowed — REST API wrapping CallbackRegistry (register, heartbeat, deregister). No quarkus:build goal |
| `graphql-generator/` | `casehub-platform-graphql-generator` | Java annotation processor (APT) — scans Jandex indexes from dependency JARs for @McpDomain + @PlatformQuery/@PlatformMutation on SPI interfaces, generates @GraphQLApi resolver .java source files. Generated source is compiled, Jandex-indexed, discovered by SmallRye GraphQL naturally. Scans hand-written @GraphQLApi resolvers and skips methods that already exist. No quarkus:build goal |
| `callback-generator/` | `casehub-platform-callback-generator` | Java annotation processor (APT) — scans Jandex indexes for @CallbackEligible on SPI interfaces, generates @Decorator classes that route to CallbackInvoker when registrations exist, delegate to wrapped bean otherwise. Fan-out (invoke all) vs single-impl (first wins) controlled by @CallbackEligible(fanOut). Kebab-case SPI name derivation from class name when @CallbackEligible(name=""). No quarkus:build goal |
| `yaml-core/` | `casehub-platform-yaml-core` | Pure Java YAML declaration primitives — VariableResolver (pluggable VariableSource chain, deferred prefixes, each/row context, DeferredPrefixHandler callback), ForEachExpander (generic adapter pattern via ForEachDirective sealed type (GroupRef + InlineIteration), when conditions via Truthiness, stamped IDs, LinkedHashMap result keyed by ID, post-expansion reference rewriting via ForEachAdapter.Reference), Truthiness (boolean string evaluation), CsvParser (typed columns: STRING/INTEGER/BOOLEAN/NUMBER, parse-time validation). Module system: YamlModule (generic sections Map), YamlModuleParameter (typed constraints: minLength/maxLength/pattern/minimum/maximum/allowedValues/constraintDescription + Builder with constraint/type coherence validation), ParameterValidator (collect-all, dual API: validate() returns List + validateOrThrow()), ParameterType enum (STRING/LIST/INTEGER/NUMBER/BOOLEAN + canAccept type compatibility, parse() → ParsedValue sealed type), ModuleExpander (alias prefixing, parameter resolution, import merging, structural validation + validateModuleRefs cross-module type checking), ModuleBridge<T> (typed bridge: fromSections/toSections/rewriter/deriveOutputs), TypedExpandedModule<T> (typed expansion result: T content + moduleScopes/importConditions/moduleOutputs/outputSource()), YamlImport, YamlModuleFile. JSON Schema fragments in src/main/resources/schema/. Zero dependencies, J2CL-transpilable. No quarkus:build goal |
| `yaml-jackson/` | `casehub-platform-yaml-jackson` | Jackson mixins for yaml-core types — YamlCoreJacksonModule (registers mixins via setupModule), YamlModuleFileMixin + YamlModuleFileBuilder (@JsonAnySetter dynamic section capture: top-level YAML keys become sections, module/imports are known fields). Case-insensitive ParameterType via MapperFeature.ACCEPT_CASE_INSENSITIVE_ENUMS. No quarkus:build goal |
| `schema-generator/` | `casehub-platform-schema-generator` | Shared JSON Schema generation from Java types — victools/jsonschema-generator (Draft 2020-12, OptionPreset.PLAIN_JSON). PlatformSchemaGenerator(Module... customModules) → generate(Class<?>) → JsonNode. Built-in modules: JacksonModule, JakartaValidationModule, EnumInliningModule (inline enum values, no $ref), UnevaluatedPropertiesModule. SchemaPostProcessor ($schema insertion). Domain-specific modules stay in consuming repos. No quarkus:build goal |
| `platform-pdf/` | `casehub-platform-pdf` | HTML-to-PDF generation with PDF/A-2b conformance via OpenHTMLtoPDF + PDFBox 3.0.3. Implements `PdfGenerator` SPI from platform-api. `@ApplicationScoped` displaces `NoOpPdfGenerator @DefaultBean`. Optional module activated by classpath presence. No quarkus:build goal |
| `platform-signing/` | `casehub-platform-signing` | EU DSS 6.2-backed document signing — PAdES PDF embedded signatures + CAdES detached (.p7s). `DssDocumentSigningService @ApplicationScoped` displaces `NoOpDocumentSigningService @DefaultBean`. `DssDocumentVerificationService @ApplicationScoped` displaces `NoOpDocumentVerificationService @DefaultBean`. `KeyStoreManager @ApplicationScoped` (PKCS#12 loading, `@Inject` from DssSigningConfig, per-tenant alias resolution). `DssSigningConfig @ConfigMapping(prefix = "casehub.signing")` — keystorePath, keystorePassword, keystoreType (PKCS12), keyAlias, padesProfile (B_T), tsaUrl, expiryWarningDays (default 30), trustedListUrl. `lifecycle/` subpackage: `CertificateExpiryMonitor` (scans keystore certs, fires `CertificateExpiryEvent` CDI event), `CertificateExpiryScheduler` (`@Scheduled` every 6h), `KeyStoreRotationService` (AtomicReference swap — failed rotations keep old manager). `tenant/` subpackage: `TenantKeyStoreResolver` (maps tenancyId to per-tenant PKCS#12 via `TenantKeyStoreConfig`, ConcurrentHashMap cache, fallback to default). `TrustedListManager @ApplicationScoped` — EU LOTL loading via `dss-tsl-validation`, integrated into `DssDocumentVerificationService` as trusted cert source (config: `casehub.signing.trusted-list-url`; disabled by default; file-cached 24h). Profile enforcement: B_T+ without TSA throws (no silent B_B downgrade). PDFBox 3.0.4 via DSS (compatible with platform-pdf's 3.0.3). Optional module activated by classpath presence. No quarkus:build goal |
| `callback-client/` | `casehub-platform-callback-client` | Client-side callback infrastructure — CallbackAutoRegistrar @Startup (discovers local @CallbackEligible SPIs via CDI, registers with CaseHub server over HTTP, heartbeat renewal at configurable interval, @Readiness health check, graceful deregister on @PreDestroy; skips @DefaultBean and @Decorator beans). CallbackDispatchResource @Path("/casehub/callbacks/{spiName}/{methodName}") — receives invocations from CallbackInvoker, X-CaseHub-SPI header validation, parameter-count method disambiguation, routes to local CDI beans. CallbackObjectMapperCustomizer (Preferences→MapPreferences mapping). Config: casehub.callback.server-url, casehub.callback.public-url (both Optional — disabled when absent). No quarkus:build goal |

## Package Structure (platform-api)

```
io.casehub.platform.api
  .acl           — AccessControlProvider (SPI: blocking grant/revoke/canAccess/revokeAll/registerParent/accessibleResources/grantBatch/revokeBatch/accessibleResourcesIncludingInherited/deny/removeDeny/denyBatch/removeDenyBatch — all resource parameters use ResourceId),
                   AclAction (enum: READ/WRITE/ADMIN/CLAIM; satisfiedBy() returns actions that satisfy a check — ADMIN satisfies WRITE+READ, WRITE satisfies READ, CLAIM is orthogonal; deniedBy() returns actions whose denial cascades to block this action — deny READ blocks WRITE+ADMIN, deny WRITE blocks ADMIN, deny CLAIM blocks only CLAIM),
                   AclEntryType (enum: ALLOW/DENY),
                   AclResourceType (constants: CASE/PLAN_ITEM/WORK_ITEM/EVENT_LOG/CASE_DEFINITION),
                   AclEntry (record: actorId, ResourceId resourceId, action, entryType, grantedAt, expiresAt, tenancyId),
                   AclEntryRequest (record: actorId, ResourceId resourceId, action, expiresAt — input type for grant/revoke/deny batch operations),
                   AclQuery (record: actorId, resourceType, action, cursor, limit — paginated accessibleResources input),
                   AclPage (record: List<ResourceId> resourceIds, nextCursor — paginated accessibleResources result),
                   AccessDeniedException (extends SecurityException: actorId, ResourceId resourceId, action),
                   ResourceId (record: type, id — structured resource identifier with parse/toString, colon-delimited),
                   WorkerAction (record: name, AclAction aclAction — extensible action type, domains define constants),
                   WorkerAuthorizationContext (marker interface — domains implement with their own fields),
                   WorkerAuthorizationPolicy (SPI: evaluate(WorkerPermissionRequest) → AuthorizationDecision, default: approve),
                   WorkerPermissionRequest (record: actorId, resourceType, Set<WorkerAction> actions, WorkerAuthorizationContext context, tenancyId),
                   WorkerCredential (record: token, actorId, ResourceId resourceId, tenancyId, Set<WorkerAction> actions, expiresAt, createdAt),
                   WorkerCredentialStore (SPI: store/lookup/revoke/revokeByResource/revokeByActor/findActiveByActorAndResource — no-op defaults),
                   AuthorizationDecision (record: approved, reason — approve()/deny(String) factories),
                   WorkerAuthorizationDeniedException (extends SecurityException: actorId, definitionId, reason)
  .actor         — ActorStateContributor (SPI: contribute data to a unified actor state view, @ApplicationScoped),
                   ActorStateAccumulator (visitor: trustScore, capabilityScore — assembled concurrently by aggregator)
  .capacity      — CapacitySignal (record: actorId, source, pressure 0.0–1.0, timestamp — validated),
                   CapacitySignalSource (SPI: sourceName + signals() → List<CapacitySignal>),
                   ActorCapacityView (SPI: aggregatedPressure/signalsByActor/allAggregatedPressures),
                   RedistributionAction (enum: NONE/COMPRESS/REDISTRIBUTE/ESCALATE),
                   RedistributionContext (record: actorId, aggregatedSignal, sourceSignals),
                   RedistributionDecision (record+factories: action, reason — none()/compress()/redistribute()/escalate()),
                   RedistributionPolicy (SPI: evaluate(RedistributionContext) → RedistributionDecision),
                   CapacityPressureEvent (CDI event record: actorId, decision, aggregatedSignal, firedAt)
  .notification  — NotificationStore (SPI: blocking store/storeAll/find/unreadCount/markRead/dismiss/markAllRead),
                   Notification (record: id, userId, tenancyId, title, body, category, severity, actionUrl, source, status, createdAt, readAt, dismissedAt),
                   NotificationInput (record: routing layer input — no id/status/timestamps), NotificationSource (record: eventId, entityType, entityId, actorId),
                   NotificationQuery (record: userId, tenancyId, status?, category?, cursor?, limit),
                   NotificationPage (record: notifications, nextCursor — cursor-keyset pagination),
                   NotificationSeverity (enum: INFO/WARNING/URGENT), NotificationStatus (enum: UNREAD/READ/DISMISSED),
                   NotificationCreated, NotificationStatusChanged, AllNotificationsRead (CDI events)
  .subscription  — SubscribableEvent (interface: compile-time contract for POJOs entering the subscription engine — type() + tenancyId()),
                   SubscriptionStore (SPI: blocking store/findById/find/update/delete/findAllEnabled — ownerId replaces userId),
                   Subscription (record: id, ownerId, tenancyId, name, eventType, filters, targets, includeActor, template, enabled, createdAt, updatedAt),
                   SubscriptionInput (record: routing layer input — no id/timestamps),
                   SubscriptionUpdate (record: partial update — all fields nullable),
                   SubscriptionQuery (record: ownerId, tenancyId, enabled?, cursor?, limit),
                   SubscriptionPage (record: subscriptions, nextCursor — cursor-keyset pagination),
                   NotificationTarget (record: type, id), TargetType (enum: USER/GROUP/EVENT_FIELD),
                   SubscriptionMatched (CDI event record: subscription, pojo — fired by SubscriptionEngine, observed by NotificationDispatcher),
                   NotificationTemplate (record: titlePattern, bodyPattern, severity, category, actionUrlPattern, entityType, entityIdField, actorIdField),
                   SubscriptionCreated, SubscriptionUpdated, SubscriptionDeleted (CDI events),
                   SubscriptionConstants (NOTIFICATION_DATASOURCE_PATH — well-known DataSource path for domain bridges),
                   EventTypeRegistry (SPI: register/resolve/discover event type metadata),
                   EventTypeDescriptor (record: eventType, displayName, description, fields),
                   EventFieldDescriptor (record: name, displayName, type)
  .path          — Path, hierarchical scope/label paths
  .preferences   — PreferenceProvider, Preferences, PreferenceKey<T> (carries defaultValue + parser),
                   SettingsScope (tenancyId, scope, effectiveAt), MapPreferences,
                   Preference (interface: toSerializedValue()), SingleValuePreference, MultiValuePreference,
                   IntPreference, DoublePreference, BooleanPreference, DurationPreference (typed preference records),
                   PreferenceStore (SPI: blocking set/delete/list/deleteAll — upsert by tenancyId+scope+namespace+name+subKey),
                   PreferenceRecord (record: tenancyId, scope, namespace, name, subKey, value),
                   PreferenceQuery (record: tenancyId, scope, namespace),
                   PreferencePermissions (static tenant assertion utility),
                   PreferenceChanged (CDI event record: tenancyId, scope, namespace — fired async after writes),
                   PreferenceSchemaRegistry (SPI: register/resolve/discover/version preference key metadata),
                   PreferenceSchemaDescriptor (record+builder: namespace, name, qualifiedName, type, label, description, defaultValue, multiValue, constraints, options — built from PreferenceKey with type inference),
                   EnumOption (record: value, label), PreferenceConstraintKeys (constants: MIN, MAX, PATTERN, MIN_LENGTH, MAX_LENGTH)
  .identity      — CurrentPrincipal, GroupMembershipProvider, PlatformRoles (constants: ADMIN — domain-prefixed role names for @RolesAllowed),
                   MissingTenancyException (thrown by tenancyId() when tenancy unresolvable), DIDMethod (CDI @Qualifier for DIDResolver method implementations),
                   ActorDIDProvider (SPI: didFor(actorId) → Optional<String>, default invalidate(actorId)),
                   ActorDIDSource (CDI @Qualifier for ActorDIDProvider source implementations),
                   VerificationMethodType (constants: ED25519, P256, SECP256K1),
                   DIDResolver (SPI: resolve(did) → Optional<DIDDocument>),
                   AgentCredentialValidator (SPI: validate(actorId, did) → Optional<CredentialValidationResult>),
                   DIDDocument (record: id, verificationMethods, alsoKnownAs),
                   VerificationMethod (record: id, type, publicKeyBytes — SPKI X.509 SubjectPublicKeyInfo DER, defensive copy),
                   IdentityVerificationResult (VALID | UNVERIFIABLE | UNSIGNED | DID_UNRESOLVABLE | IDENTITY_MISMATCH | KEY_MISMATCH),
                   CredentialValidationResult (VALID | EXPIRED | INVALID_SIGNATURE | ISSUER_UNKNOWN | NOT_FOUND),
                   IdentityBindingStatus (VALID | UNSIGNED | DID_UNRESOLVABLE | IDENTITY_MISMATCH | KEY_MISMATCH | CREDENTIAL_EXPIRED | CREDENTIAL_INVALID),
                   AgentIdentityValidatedEvent (CDI event record: VALID binding),
                   AgentIdentityViolationEvent (CDI event record: non-VALID binding)
  .datasource    — DataSource (SPI: event boundary + subscribe), DataSourceRegistry (register/resolve/discover/deregister/update),
                   ObjectType (pluggable type discriminator), ClassObjectType, DataProcessor, SubscriptionHandle,
                   FilterExpression (shareable predicate), DataSourceDescriptor (marshallerKeys: eventType → marshallerKey map), DataSourceQuery,
                   DataSourceRegistered (CDI event), DataSourceDeregistered (CDI event: descriptor + DataSource instance for identity comparison),
                   DataSourceUpdated (CDI event record: oldDescriptor, newDescriptor, dataSource),
                   Marshaller, MarshalException, MarshallerRegistry (SPI: register/resolve named Marshaller instances)
  .expression    — CompiledExpression<C,R> (runtime contract: type() + eval(C)),
                   ExpressionEvaluator (marker: type() discriminator),
                   ExpressionEngine (factory SPI: compile/validate, typed compile with variables),
                   ExpressionEngineRegistry (SPI: register/resolve/compile/validate by type key),
                   JQExpressionEvaluator (record: expression), MvelExpressionEvaluator (record: expression),
                   LambdaExpression<C,R> (implements both ExpressionEvaluator + CompiledExpression, wraps Function<C,R>),
                   ExpressionCompilationException, ExpressionEvaluationException,
                   SecretManager, ConfigManager, ConfigMapNotFoundException, SecretNotFoundException
  .governance    — ExecutionPolicy (record: timeoutMs, RetryPolicy, CircuitBreakerPolicy), RetryPolicy (record: maxRetries, delay, BackoffStrategy),
                   BackoffStrategy (enum: FIXED/EXPONENTIAL/EXPONENTIAL_WITH_JITTER),
                   CircuitBreakerPolicy (record: failureThreshold, recoveryWindowMs)
  .callback      — CallbackRegistration (record: id, spiName, callbackUrl, credentialRef, tenancyId, timeoutMs, metadata, registeredAt, expiresAt, lastHeartbeatAt),
                   CallbackRegistrationRequest (record), CallbackRegistry (SPI: register/deregister/heartbeat/findBySpi/findById),
                   CallbackRegistered, CallbackDeregistered (CDI events)
  .mcp           — McpDomain (annotation: domain grouping), ModelEnricher (interface: summary + state),
                   PlatformQuery (annotation: marks query method for GraphQL generation),
                   PlatformMutation (annotation: marks mutation method for GraphQL generation),
                   CallbackEligible (annotation: marks SPI for callback proxy generation, name + fanOut),
                   McpResourceDescriptor (sealed: StaticResourceDescriptor + TemplateResourceDescriptor — name, uri/uriTemplate, mimeType, description, subscribable),
                   McpResourceReadRequest (record: uri, templateArgs),
                   McpResourceContent (record: uri, text, mimeType),
                   McpResourceHandler (functional interface: read(McpResourceReadRequest) throws Exception → McpResourceContent),
                   McpResourceHandle (interface: notifyUpdate(uri), deregister()),
                   McpResourceRegistry (SPI: newResource → McpResourceRegistration builder → register/deregister/resolve/list),
                   McpResourceRegistration (builder: handler/completion/serverName/register),
                   McpResourceRegistered (CDI event record: descriptor),
                   McpResourceUpdated (CDI event record: uri — decoupled notification relay)
  .endpoints     — EndpointRegistry (SPI: register/resolve/discover/deregister by (Path, tenancyId)),
                   EndpointDescriptor (record: path, tenancyId, type, protocol, properties, credentialRef, capabilities),
                   EndpointPermissions (static: assertTenant(tenancyId, principal) — write-auth for runtime registration),
                   EndpointRegistered (CDI event record: fired by EndpointRegistry on successful registration),
                   EndpointType (enum: SYSTEM/SERVICE/WORKER/AGENT),
                   EndpointProtocol (enum: HTTP/GRPC/KAFKA/MCP/CAMEL/QHORUS/AMQP),
                   EndpointCapability (enum: SEND/RECEIVE/QUERY/DISPATCH),
                   EndpointQuery (record: tenancyId, type, protocol, requiredCapabilities),
                   EndpointPropertyKeys (reserved cross-protocol property keys: URL, TOPIC, STREAM_EVENT_TYPE, STREAM_DATA_CONTENT_TYPE)
  .memory        — CaseMemoryStore (blocking SPI) + GraphCaseMemoryStore (graph-native extension: graphQuery(GraphMemoryQuery)),
                   MemoryCapability (enum: declared adapter capabilities), MemoryCapabilityException,
                   MemoryResultType (DEFAULT/FACTS), GraphMemoryQuery (graph-native query: tenantId, entityIds, domain,
                   question, limit, since, validAt, entityTypes, resultType),
                   MemoryDomain, MemoryInput, Memory,
                   MemoryQuery (entityIds: List<String>, MemoryOrder, with* fluent API),
                   EraseRequest, MemoryPermissions (static tenant assertion utility),
                   MemoryOrder (enum: CHRONOLOGICAL / RELEVANCE),
                   MemoryAttributeKeys (reserved cross-domain keys + confidence helpers + VALID_FROM/VALID_UNTIL + SOLUTION),
                   StoreAllResult (storeAll() return: stored IDs + StoreFailure list; SecurityException always propagates, backend failures collected),
                   StoreFailure (inputIndex, input, cause — for retry correlation after partial storeAll failure),
                   CbrCaseEntry (CBR Retain step schema: problem→MemoryInput.text, solution→SOLUTION attr, outcome→OUTCOME attr, confidence→CONFIDENCE attr; toMemoryInput()/from(Memory))
  .actor         — ActorStateContributor (SPI: contribute actor workload data to an ActorStateAccumulator),
                   ActorStateAccumulator (visitor passed to each contributor to accumulate active-cases,
                   open-WorkItems, and open-obligation slices of the actor state view)
  .notification.settings — NotificationPreferenceStore (SPI: blocking get/update per user),
                   NotificationPreferences (record: userId, tenancyId, channelDefaults, quietHours, updatedAt),
                   ChannelPreference (record: enabled, minSeverity, digestSchedule), QuietHours (record: start, end, timezone),
                   NotificationPreferenceUpdate (record: channelDefaults, quietHours, clearQuietHours),
                   SuppressionStore (SPI: mute CRUD + snooze activate/cancel),
                   MuteRule (record: id, userId, tenancyId, scope, scopeId, entityType, createdAt, expiresAt),
                   MuteScope (enum: ENTITY/CATEGORY), MuteRuleInput, Snooze (record: userId, tenancyId, until, createdAt),
                   SnoozeInput, SuppressionResult (record: isMuted, isSnoozed, quietHoursActive)
  .event         — CloudEventType (CDI @Qualifier: type-based CloudEvent observer filtering)
  .delivery      — DeliveryChannelRegistry (SPI: register descriptor+deliverer/resolve/discover),
                   DestinationResolver (SPI: resolve userId → channel-specific destination address),
                   DeliveryChannelDescriptor (record: channelId, displayName, external, defaultEnabled, defaultMinSeverity, defaultDigestSchedule, guaranteedMinSeverity),
                   DeliveryChannels (constants: IN_APP, EMAIL, SMS, PUSH, WHATSAPP),
                   NotificationDeliverer (SPI: channelId + deliver → DeliveryResult + default deliverDigest(DigestSummary)),
                   DeliveryResult (record: success, failureReason),
                   DeliveryType (enum: IMMEDIATE/DIGEST), DeliveryStatus (enum: DELIVERED/FAILED/RETRYING/EXPIRED),
                   DeliverySourceType (enum: NOTIFICATION),
                   DeliveryAttempt (record: id, sourceId, DeliverySourceType sourceType, channelId, userId, tenancyId, deliveryType, status, attemptCount, createdAt, lastAttemptedAt, deliveredAt, nextRetryAt, failureReason, payload, firstOpenedAt, firstClickedAt),
                   DeliveryAttemptStore (SPI: store/update/findById/claimRetryable/find/findBySource/recordEngagement/findEngagementsByAttemptId/findEngagementsBySource),
                   EngagementType (enum: OPENED/CLICKED/DISMISSED/REPLIED/CONVERTED),
                   EngagementEvent (record: id, attemptId, sourceId, DeliverySourceType sourceType, channelId, userId, tenancyId, type, recordedAt, metadata),
                   RawEngagement (record: attemptId, type, metadata),
                   EngagementRecorded (CDI event record: event — fired on engagement recording),
                   EngagementCallbackHandler (SPI: channelId + translate(rawPayload) → List<RawEngagement>),
                   DeliveryAttemptQuery (record: userId, tenancyId, channelId, status, DeliverySourceType sourceType, cursor, limit),
                   DeliveryAttemptPage (record: attempts, nextCursor — cursor-keyset pagination),
                   DeliveryExhausted (CDI event record: attempt — fired when retries exhausted),
                   DigestSchedule (sealed interface: isFlushDue — Interval(Duration) + DailyAt(LocalTime, ZoneId) + WeeklyAt(DayOfWeek, LocalTime, ZoneId)),
                   DigestBuffer (SPI: add/drain/pendingKeys/oldestPendingTimestamp),
                   DigestBufferKey (record: userId, tenancyId, channelId),
                   DigestSummary (record: userId, tenancyId, channelId, notifications, periodStart, periodEnd)
  .credentials   — CredentialResolver (SPI: resolve(credentialRef) → Map<String, String>),
                   CredentialPropertyKeys (reserved keys: USER, PASSWORD, BEARER_TOKEN, API_KEY, EXPIRES_AT, SIGNING_SECRET)
  .signing.document — DocumentSigningService (SPI: signPdf(byte[], SigningIdentity) → Optional<SignedDocument>, signDetached(byte[], SigningIdentity) → Optional<DetachedSignature>),
                   DocumentVerificationService (SPI: verifyPdf(byte[]) → DocumentVerificationResult, verifyDetached(byte[], byte[]) → DocumentVerificationResult),
                   SigningIdentity (record: actorId, tenancyId),
                   SignedDocument (record: signedBytes, signerDn, signedAt, keyRef, profile — defensive copy),
                   DetachedSignature (record: signatureBytes, signerDn, signedAt, keyRef, profile — defensive copy),
                   SigningProfile (enum: B_B/B_T/B_LT/B_LTA; requiresTimestamp()),
                   DocumentVerificationResult (record: status, signerDn, signedAt, keyRef, detectedProfile, certificateChain, diagnosticMessage; static unsigned()),
                   VerificationStatus (enum: VALID/INVALID/UNSIGNED/UNSUPPORTED_FORMAT/ERROR),
                   CertificateInfo (record: subjectDn, issuerDn, validFrom, validTo, claimsQualified)
  .label          — LabelAction (sealed: Add | Remove — condition-driven label mutations),
                   LabelRule (record: name, CompiledExpression<Map<String,Object>,Boolean> condition, List<LabelAction> actions;
                   static evaluate(rules, context) → List<LabelAction>)
  .util           — UUIDv7 (utility: time-ordered UUID per RFC 9562 §5.7, monotonic within same millisecond)
  .view           — LabelPatternMatcher (static: matches(pattern, path) — exact, /*, /**),
                   SubjectViewSpec (record: id, name, tenancyId, labelPattern, scope, sortField, sortDirection, createdAt),
                   SubjectViewStore (SPI: save/findById/findByTenancy/delete),
                   ViewMembershipTracker (SPI: getLastKnownMembership/updateMembership/removeMembership/getSubjectsByView/removeMembershipByView),
                   SubjectViewQuery<S> (SPI: findByView/findByView(paginated)/countByView),
                   ViewEventType (enum: ADDED/REMOVED/CHANGED),
                   SubjectViewEvent (record: subjectId, viewId, viewName, type, tenancyId)
```



## Writing Style Guide

**The writing style guide at `~/claude-workspace/writing-styles/blog-technical.md` is mandatory for all blog and diary entries.** Load it in full before drafting. Complete the pre-draft voice classification (I / we / Claude-named) before generating any prose. Do not show a draft without verifying it against the style guide.

## Work Tracking

**Issue tracking:** enabled
**GitHub repo:** casehubio/platform
**Changelog:** GitHub Releases
