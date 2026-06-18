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
| blog       | workspace   | staged; published to mdproctor.github.io via publish-blog |
| plans      | workspace   | stay in workspace permanently |
| design     | workspace   | epic journal stays in workspace |
| snapshots  | workspace   | |
| handover   | workspace   | |

**Blog directory:** `/Users/mdproctor/claude/public/casehub/platform/blog/`

## Rules

- `platform-api/` must remain zero-dependency — no Quarkus, no JPA, no casehubio imports. Pure Java only.
- `platform/` contains Quarkus @DefaultBean implementations only — no domain logic
- Every SPI in platform-api gets a @DefaultBean implementation in platform
- Two @DefaultBean patterns exist:
  - **Configurable mock** (PreferenceProvider, CurrentPrincipal): returns values driven by @ConfigProperty — suitable when tests need to set specific return values
  - **Silent no-op** (CaseMemoryStore): always returns empty/void — suitable when the capability is optional and "not installed" means "nothing happens"
- `config/` reads YAML preference files at startup — declare as compile scope in production; not needed on test-only classpaths (mock handles test defaults via `application.properties`)

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
| `platform/` | `casehub-platform` | Quarkus @DefaultBean implementations (configurable mocks + no-ops) + `BlockingToReactiveBridge` |
| `testing/` | `casehub-platform-testing` | @Alternative @Priority(1) identity fixtures — no Quarkus runtime |
| `config/` | `casehub-platform-config` | Scope-aware YAML + SmallRye Config PreferenceProvider — displaces mock when on classpath |
| `oidc/` | `casehub-platform-oidc` | @RequestScoped OIDC-backed CurrentPrincipal — reads actorId/groups from SecurityIdentity |
| `expression/` | `casehub-platform-expression` | JQ expression evaluation (JQEvaluator) |
| `persistence-jpa/` | `casehub-platform-persistence-jpa` | JPA-backed PreferenceProvider — scope-aware, hierarchy-resolved, current-only. Add as compile dep; consumers must add `classpath:db/platform/migration` to Flyway locations |
| `persistence-mongodb/` | `casehub-platform-persistence-mongodb` | MongoDB-backed PreferenceProvider — @Alternative @Priority(1), beats JPA when co-deployed. No Flyway; startup bean creates scope index |
| `memory-inmem/` | `casehub-platform-memory-inmem` | @Alternative @Priority(10) volatile CaseMemoryStore — ConcurrentHashMap, constructor-injected CurrentPrincipal, no quarkus:build goal. Add as test scope for @QuarkusTest isolation; compile for ephemeral installs. Do NOT combine with memory-jpa or memory-sqlite in the same scope |
| `memory-jpa/` | `casehub-platform-memory-jpa` | @ApplicationScoped JPA CaseMemoryStore — PostgreSQL, Flyway V1000 (`classpath:db/memory/migration`), FTS via websearch_to_tsquery when question provided. No quarkus:build goal (CurrentPrincipal only on test classpath). Use @TestTransaction not @Transactional in tests |
| `memory-sqlite/` | `casehub-platform-memory-sqlite` | @Alternative @Priority(1) SQLite CaseMemoryStore — xerial JDBC + HikariCP (WAL mode) + FTS5 + Flyway programmatic. Configure `casehub.memory.sqlite.path`. No quarkus:build goal. Do NOT combine with memory-inmem or memory-jpa in the same scope |
| `memory-mem0/` | `casehub-platform-memory-mem0` | @Alternative @Priority(1) Mem0 REST CaseMemoryStore — vector embeddings via Mem0 OSS (Docker + pgvector), infer:false (verbatim storage). Tenant isolation via compound `user_id={tenantId}::{entityId}` (Mem0 OSS has no app_id). GET /memories unbounded; limit client-side. RELEVANCE uses POST /search with top_k + threshold. Configure: `quarkus.rest-client.mem0.url`, `casehub.memory.mem0.api-key`. No quarkus:build goal. Do NOT combine with memory-inmem or memory-sqlite in same scope |
| `memory-graphiti/` | `casehub-platform-memory-graphiti` | @Alternative @Priority(2) Graphiti REST GraphCaseMemoryStore — temporal knowledge graph (Neo4j/FalkorDB/Kuzu via Graphiti OSS). LLM entity extraction (async). group_id={tenantId}::{entityId}::{domain} (domain is the partition key — entity relationships span cases within a domain). ERASE_DOMAIN_CASE: domain-level deletion via DELETE /group (cascading, complete); case-level via DELETE /episode (best-effort, EpisodicNode only). ERASE_ENTITY: requires `casehub.memory.graphiti.known-domains` (comma-separated domain list). RELEVANCE/graphQuery() → POST /search per entity; CHRONOLOGICAL → GET /episodes/{group_id} per entity. Configure: `quarkus.rest-client.graphiti.url`, `casehub.memory.graphiti.api-key`. No Flyway. Do NOT combine with other @Priority(2) adapters |
| `endpoints-memory/` | `casehub-platform-endpoints-memory` | @Alternative @Priority(100) volatile InMemoryEndpointRegistry — ConcurrentHashMap, tenant-filtered, platform-global visibility. Tier 4 CDI priority (beats JPA and NoSQL adapters). Data lost on restart. Add test scope for @QuarkusTest isolation; compile scope for ephemeral installs. Do NOT combine with a JPA endpoints backend in same scope |
| `endpoints-config/` | `casehub-platform-endpoints-config` | @Startup @ApplicationScoped YAML-backed endpoint populator — reads `casehub.platform.endpoints.files` at startup, parses into EndpointDescriptor records, calls EndpointRegistry.register(). Populator, not a registry implementation — populates whichever EndpointRegistry CDI selects. Requires a working registry backend (e.g. endpoints-memory) to be meaningful; silently registers into NoOpEndpointRegistry @DefaultBean otherwise (startup log reveals this). Multi-file: later files replace earlier files for same (path, tenancyId). No lifecycle reconciliation. Path separator read directly from casehub.platform.path.separator — no dependency on PathParserConfigurator. |
| `scim/` | `casehub-platform-scim` | @ApplicationScoped SCIM 2.0 GroupMembershipProvider — displaces @DefaultBean mock. Auth: casehub.platform.scim.token (static) or quarkus.oidc-client.scim.* (client-credentials). @CacheResult on membersOf(). Pagination: casehub.platform.scim.member-page-size (default 1000). No quarkus:build goal |
| `identity/` | `casehub-platform-identity` | @Alternative ActorDIDProvider/DIDResolver/AgentCredentialValidator impls — did:key, did:web, SCIM2, config-based. SPIs and model types in platform-api. Config prefix: casehub.identity.* |
| `agent-api/` | `casehub-platform-agent-api` | AgentProvider SPI (`invoke()` single-shot + `openSession()` multi-turn) + AgentSession (multi-turn: serial query/interrupt/close) + AgentSessionInit (session config, no userPrompt) + AgentSessionConfig (single-shot config), AgentEvent, AgentMcpServer, typed exceptions (AgentProcessException, AgentSessionLimitException, AgentTimeoutException). Mutiny only — no Quarkus. Package: `io.casehub.platform.agent` |
| `agent-claude/` | `casehub-platform-agent-claude` | `ClaudeAgentProvider @ApplicationScoped` + `ClaudeAgentClient @Startup` — activates by classpath presence, requires Claude CLI. Concurrent-session semaphore enforces `AgentSessionConfig.maxConcurrentSessions`. Single-shot: `run()` with per-invocation semaphore. Multi-turn: `openSession()` returns `ClaudeAgentSession` (IDLE/ACTIVE/CLOSED state machine, per-turn timeout, true-drain close). Package: `io.casehub.platform.agent.claude` |
| `agent-claude-langchain4j/` | `casehub-platform-agent-claude-langchain4j` | `ChatModel` + `StreamingChatModel` adapters backed by `AgentSession`. Two paths: `ClaudeAgentChatModel` (`@Alternative @Priority(10) @ApplicationScoped` — fresh session per call, system prompt cached at Anthropic level) and `AgentSessionChatModel` (plain wrapper — caller-supplied session, multi-turn). Not compatible with `engine.Agent` (which forces `ResponseFormatType.JSON`). No quarkus:build goal. `listeners()` injects CDI `ChatModelListener` beans on `ClaudeAgentChatModel`; `AgentSessionChatModel` v1 gap. |
| `streams-kafka/` | `casehub-platform-streams-kafka` | @Startup @ApplicationScoped static Kafka channel ingestion — @Incoming("casehub-kafka-stream"), always raw byte[], builds CloudEvent from STREAM_EVENT_TYPE. Does NOT observe EndpointRegistered. CAMEL and KAFKA are mutually exclusive for same topic. |
| `streams-amqp/` | `casehub-platform-streams-amqp` | @Startup @ApplicationScoped static AMQP channel ingestion — single address per channel (no multi-address; for multi-queue fan-in use streams-camel). Does NOT observe EndpointRegistered. |
| `streams-webhook/` | `casehub-platform-streams-webhook` | @Startup @ApplicationScoped JAX-RS receiver — POST /streams/webhook/{tenancyId}/{streamId}, structured CloudEvents HTTP binding (application/cloudevents+json), preserves incoming CloudEvent fields, enriches tenancyid from descriptor. Requires casehub.streams.webhook.public-url config. |
| `streams-poll/` | `casehub-platform-streams-poll` | @Startup @ApplicationScoped @Scheduled HTTP GET poller — java.net.http.HttpClient field, explicit status code check (HttpClient.send() does not throw for 4xx/5xx), per-endpoint exception handling. |
| `streams-camel/` | `casehub-platform-streams-camel` | @ApplicationScoped dynamic Camel route builder — @Observes StartupEvent discovers pre-startup CAMEL endpoints, @ObservesAsync EndpointRegistered for runtime additions (idempotent via routedUris set). P0: URI change requires restart. |

## Package Structure (platform-api)

```
io.casehub.platform.api
  .actor         — ActorStateContributor (SPI: contribute data to a unified actor state view, @ApplicationScoped),
                   ActorStateAccumulator (visitor: trustScore, capabilityScore — assembled concurrently by aggregator)
  .path          — Path, hierarchical scope/label paths
  .preferences   — PreferenceProvider, Preferences, PreferenceKey<T> (carries defaultValue + parser),
                   SettingsScope, MapPreferences, Preference, SingleValuePreference, MultiValuePreference
  .identity      — CurrentPrincipal, GroupMembershipProvider,
                   ActorDIDProvider (SPI: didFor(actorId) → Optional<String>),
                   DIDResolver (SPI: resolve(did) → Optional<DIDDocument>),
                   AgentCredentialValidator (SPI: validate(actorId, did) → Optional<CredentialValidationResult>),
                   DIDDocument (record: id, verificationMethods, alsoKnownAs),
                   VerificationMethod (record: id, type, publicKeyBytes — defensive copy),
                   IdentityVerificationResult (VALID | UNVERIFIABLE | UNSIGNED | DID_UNRESOLVABLE | IDENTITY_MISMATCH | KEY_MISMATCH),
                   CredentialValidationResult (VALID | EXPIRED | INVALID_SIGNATURE | ISSUER_UNKNOWN | NOT_FOUND),
                   IdentityBindingStatus (VALID | UNSIGNED | DID_UNRESOLVABLE | IDENTITY_MISMATCH | KEY_MISMATCH | CREDENTIAL_EXPIRED | CREDENTIAL_INVALID),
                   AgentIdentityValidatedEvent (CDI event record: VALID binding),
                   AgentIdentityViolationEvent (CDI event record: non-VALID binding)
  .endpoints     — EndpointRegistry (SPI: register/resolve/discover/deregister by (Path, tenancyId)),
                   EndpointDescriptor (record: path, tenancyId, type, protocol, properties, credentialRef, capabilities),
                   EndpointPermissions (static: assertTenant(tenancyId, principal) — write-auth for runtime registration),
                   EndpointRegistered (CDI event record: fired by EndpointRegistry on successful registration),
                   EndpointType (enum: SYSTEM/SERVICE/WORKER/AGENT),
                   EndpointProtocol (enum: HTTP/GRPC/KAFKA/MCP/CAMEL/QHORUS/AMQP),
                   EndpointCapability (enum: SEND/RECEIVE/QUERY/DISPATCH),
                   EndpointQuery (record: tenancyId, type, protocol, requiredCapabilities),
                   EndpointPropertyKeys (reserved cross-protocol property keys: URL, TOPIC, STREAM_EVENT_TYPE)
  .memory        — CaseMemoryStore (blocking SPI) + GraphCaseMemoryStore (graph-native extension: graphQuery(GraphMemoryQuery)),
                   ReactiveCaseMemoryStore (Mutiny SPI),
                   MemoryCapability (enum: declared adapter capabilities), MemoryCapabilityException,
                   MemoryResultType (DEFAULT/FACTS), GraphMemoryQuery (graph-native query: tenantId, entityIds, domain,
                   question, limit, since, validAt, entityTypes, resultType),
                   MemoryDomain, MemoryInput, Memory,
                   MemoryQuery (entityIds: List<String>, MemoryOrder, with* fluent API),
                   EraseRequest, MemoryPermissions (static tenant assertion utility),
                   MemoryOrder (enum: CHRONOLOGICAL / RELEVANCE),
                   MemoryAttributeKeys (reserved cross-domain keys + confidence helpers + VALID_FROM/VALID_UNTIL)
  .actor         — ActorStateContributor (SPI: contribute actor workload data to an ActorStateAccumulator),
                   ActorStateAccumulator (visitor passed to each contributor to accumulate active-cases,
                   open-WorkItems, and open-obligation slices of the actor state view)
```

`platform/` exposes `BlockingToReactiveBridge @DefaultBean` at `io.casehub.platform.memory`.

## Writing Style Guide

**The writing style guide at `~/claude-workspace/writing-styles/blog-technical.md` is mandatory for all blog and diary entries.** Load it in full before drafting. Complete the pre-draft voice classification (I / we / Claude-named) before generating any prose. Do not show a draft without verifying it against the style guide.

## Work Tracking

**Issue tracking:** enabled
**GitHub repo:** casehubio/platform
**Changelog:** GitHub Releases
