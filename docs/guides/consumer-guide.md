# casehub-platform -- Consumer Guide

> Zero-dependency SPIs and types shared across all casehub modules -- the foundation layer every app builds on.

**Repo:** [`casehubio/platform`](https://github.com/casehubio/platform)
**Tier:** Foundation (first in build order, zero casehubio dependencies)

---

## Purpose

casehub-platform defines the domain abstractions that every casehub module shares: identity, preferences, paths, memory, data sources, endpoints, notifications, subscriptions, expressions, access control, credentials, governance, labels, subject views, agent infrastructure, and simulation. These are pure Java SPIs with zero external dependencies in `platform-api/`. Quarkus-specific implementations live in companion modules that activate by classpath presence via CDI `@DefaultBean` displacement.

This repo is not a parallel framework to Quarkus -- it is a thin domain layer that Quarkus-specific code implements. `CurrentPrincipal` wraps `SecurityIdentity`, `PreferenceProvider` complements `@ConfigMapping`, and `Path` replaces `java.nio.file.Path` with domain semantics. They solve different problems and belong together.

---

## Modules to Depend On

### Always needed

| Artifact | What it gives you |
|----------|-------------------|
| `casehub-platform-api` | All SPIs and value types -- zero deps, pure Java |
| `casehub-platform` | `@DefaultBean` mocks and no-ops -- safe dev/test defaults; `DataSourceRouter`; `CloudEventTypeDispatcher` |
| `casehub-platform-testing` (test scope) | `FixedCurrentPrincipal`, `InMemoryGroupMembershipProvider` -- programmatic test control |

### Activate by adding as compile dependency

Each displaces its `@DefaultBean` mock automatically -- no exclusion config needed.

| Artifact | What it activates |
|----------|-------------------|
| `casehub-platform-config` | Scope-aware YAML preference provider (replaces mock) |
| `casehub-platform-oidc` | OIDC-backed `CurrentPrincipal` from JWT (replaces mock) |
| `casehub-platform-scim` | SCIM 2.0 `GroupMembershipProvider` (replaces mock) |
| `casehub-platform-expression` | JQ + MVEL3 + JEXL3 expression engines; `DefaultExpressionEngineRegistry`; `ConfigManager`; `SecretManager` |
| `casehub-platform-persistence-jpa` | JPA-backed scoped preference overrides (`JpaPreferenceProvider`, `JpaPreferenceStore`) |
| `casehub-platform-persistence-mongodb` | MongoDB preference backend (beats JPA when co-deployed via CDI priority) |
| `casehub-platform-credentials-quarkus` | Bridge `CredentialResolver` to Quarkus `CredentialsProvider` (Vault/AWS/GCP) |

### Notification system (add what you need)

| Artifact | What it provides |
|----------|------------------|
| `casehub-platform-notifications` | REST + push presentation layer -- list, mark-read, dismiss, unread-count |
| `casehub-platform-notifications-inmem` | In-memory notification store (test/ephemeral) |
| `casehub-platform-notifications-jpa` | JPA notification store (production) -- keyset pagination, retention scheduler |
| `casehub-platform-notification-dispatch` | Three-path delivery pipeline (digest/suppress/immediate); `DigestFlushScheduler`; `DeliveryRetryProcessor` |
| `casehub-platform-notification-settings-inmem` | In-memory preference/suppression store |
| `casehub-platform-notification-settings-jpa` | JPA preference/suppression store -- JSON TEXT columns, retention scheduler |
| `casehub-platform-subscriptions` | Subscription matching engine + REST -- alpha network wiring, expression compilation |
| `casehub-platform-subscriptions-inmem` | In-memory subscription store (test/ephemeral) |
| `casehub-platform-subscriptions-jpa` | JPA subscription store (production) -- OR-disjunction scope queries |
| `casehub-platform-delivery-channel-inmem` | Channel-to-deliverer registry -- **production implementation** (channels are static) |
| `casehub-platform-delivery-tracking-inmem` | In-memory `DeliveryAttemptStore` |
| `casehub-platform-delivery-tracking-jpa` | JPA `DeliveryAttemptStore` -- `SKIP LOCKED` claims, retention purge |
| `casehub-platform-digest-inmem` | In-memory `DigestBuffer` |
| `casehub-platform-digest-jpa` | JPA `DigestBuffer` -- drain via SELECT+DELETE in transaction |

### YAML declaration primitives

| Artifact | What it provides |
|----------|------------------|
| `casehub-platform-yaml-core` | Pure Java YAML primitives — `VariableResolver` (pluggable sources, deferred prefixes, `DeferredPrefixHandler`, `${each.*}` context), `ForEachExpander` (generic adapter, inline + named groups, `when` conditions, ID-keyed results), `Truthiness` (boolean string eval), `CsvParser` (typed columns). Module system: `YamlModule` (generic sections), `YamlModuleParameter` (typed constraints), `ParameterValidator` (collect-all), `ModuleExpander` (alias prefixing, import merging, typed expansion via `ModuleBridge<T>`), `TypedExpandedModule<T>` (typed expansion result). JSON Schema fragments for composable YAML validation. Zero deps, J2CL-transpilable |
| `casehub-platform-yaml-jackson` | Jackson mixins for yaml-core types — `YamlCoreJacksonModule` (register on ObjectMapper). Dynamic section capture: top-level YAML keys become sections automatically (no `sections:` wrapper). Case-insensitive `ParameterType` deserialization via `MapperFeature.ACCEPT_CASE_INSENSITIVE_ENUMS`. Depends on yaml-core + jackson-databind |
| `casehub-platform-ts-core` | TypeScript execution SPI — `TsExecutor` interface with `evaluate(String)` and `evaluate(Path)` returning `TsEvalResult`. `NodeTsExecutor` (Node.js subprocess via `npx tsx`). Repos consuming TS-defined configurations depend on this for the executor SPI and build their own domain-specific processors |

### Data source and event streams

| Artifact | What it provides |
|----------|------------------|
| `casehub-platform-datasource-alpha` | Rete-style alpha network for event routing |
| `casehub-platform-datasource-inmem` | In-memory DataSource registry (test/ephemeral) |
| `casehub-platform-datasource-jpa` | JPA DataSource registry (production) -- startup reconciliation |
| `casehub-platform-endpoints-memory` | In-memory endpoint registry |
| `casehub-platform-endpoints-config` | YAML-backed endpoint populator -- `${VAR}` interpolation, multi-file |
| `casehub-platform-streams-core` | Shared CloudEvent construction -- `StreamCloudEventFactory` |
| `casehub-platform-streams-kafka` | Kafka event stream connector -- static `@Incoming`, CloudEvent builder |
| `casehub-platform-streams-amqp` | AMQP event stream connector -- single address per channel |
| `casehub-platform-streams-webhook` | Webhook event stream connector -- structured CloudEvents HTTP binding |
| `casehub-platform-streams-poll` | Polling event stream connector -- `@Scheduled`, per-endpoint failure isolation |
| `casehub-platform-streams-camel` | Apache Camel event stream connector (runtime-dynamic routes) |

### Agent infrastructure

Callers inject `AgentProvider` — the `RoutingAgentProvider` resolves the `model` field via three-step resolution: (1) `ModelRegistry` lookup by model ID (routes to backend via descriptor's `backendKey`), (2) direct backend key match, (3) fail-fast. Add one or more backend modules to the classpath; the router discovers them automatically.

| Artifact | What it provides |
|----------|------------------|
| `casehub-platform-agent-api` | `AgentProvider` + `AgentBackend` SPIs; `AgentRuntime` + `AgentProcess` (subprocess abstraction); `AgentEvent` sealed interface; `AgentMcpServer` (Stdio/Sse/Http); Mutiny only, no Quarkus |
| `casehub-platform-agent-runtime` | `SubprocessRuntime` -- local process execution for CLI agent providers |
| `casehub-platform-agent-router` | `RoutingAgentProvider` -- three-step model resolution (registry → key → fail-fast) with config rewriting. Config: `casehub.platform.agent.default-backend` |
| `casehub-platform-agent-claude` | AgentBackend "claude" -- Claude CLI subprocess via `claude-code-sdk` |
| `casehub-platform-agent-openai` | AgentBackend "openai" -- native OpenAI Java SDK with `prompt_cache_key` support |
| `casehub-platform-agent-codex` | AgentBackend "codex" -- Codex CLI via `AgentRuntime` |
| `casehub-platform-agent-gemini` | AgentBackend "gemini" -- native Google GenAI SDK with explicit caching |
| `casehub-platform-agent-gemini-cli` | AgentBackend "gemini-cli" -- Gemini CLI via `AgentRuntime` |
| `casehub-platform-agent-langchain4j` | AgentBackend "langchain4j" -- bidirectional LangChain4j interop |
| `casehub-platform-agent-gate` | CDI `@Decorator` rate limiter -- wraps `RoutingAgentProvider` transparently |

#### Securing remote manifest sources

The `sources:` section in `agent-config.yaml` supports authentication headers and content integrity verification for remote model catalogs. All fields are optional — existing manifests without `auth:` or `integrity:` blocks continue to work unchanged.

**Authentication** — typed `auth:` block with `bearer`, `header`, or `basic` types:

```yaml
sources:
  - uri: https://corp.example/approved-models.yaml
    priority: 40
    auth:
      type: bearer
      credential: ref:vault/catalog-token

  - uri: https://partner.example/models.yaml
    priority: 45
    auth:
      type: header
      credential: env:PARTNER_API_KEY
      header-name: X-Api-Key
```

**Content integrity** — SHA-256 digest with optional DID-based signature:

```yaml
sources:
  - uri: https://registry.example/models.yaml
    priority: 50
    integrity:
      digest: sha256:abcdef0123456789abcdef0123456789abcdef0123456789abcdef0123456789
      signature: dGVzdHNpZ25hdHVyZQ          # optional, base64url-encoded
      signer: did:web:registry.example        # required when signature is present
```

**HTTPS enforcement** — HTTP is auto-allowed for loopback/private addresses. Public HTTP is rejected by default. Override for local dev: `casehub.agent.manifest.allow-insecure=true`.

**Fail-closed posture** — declared auth without a credential resolver, digest mismatch, or signature verification failure all reject the source (ERROR log). Missing fields mean no check — only declared intent is enforced.

### Simulation

Complete SPI testing framework — replaces Mockito for platform SPI tests. Configurable simulation for any SPI: real responses when you have a real backend, simulated responses when you don't, captured traffic when you want to build a corpus, and a verification API for asserting SPI interactions. See the [Simulation Guide](simulation-guide.md) for full documentation.

**Core framework:**

| Artifact | Scope | What it provides |
|----------|-------|------------------|
| `casehub-platform-simulation-api` | compile | Core SPIs: `SimulationStrategy<I,O>`, `SimulationCorpus<I,O>`, `InvocationRecord<I,O>` (with `.of()` factories), `CorpusSeed<I,O>` (typed accumulator with `withKeyExtractor`/`withOutputMapper`), `KeyExtractor<I>`, `SimilarityScorer<I>`, `DataRealism`, `ExhaustionPolicy`, `@SimulationEligible` (name + `capabilities` for recursive wrapper generation), `SimulationConfig` (`speed()` global multiplier default 1.0). Zero deps |
| `casehub-platform-simulation-core` | compile | Strategy implementations (Sequential, KeyLookup, Random, RecordedReplay, NearestMatch) + `SimulationRuntime` (strategy factory, overlay stack, profile activation, `globalSpeed`/`setGlobalSpeed` volatile multiplier) + `SimulationOverlay` (per-scenario isolation) + `InvocationJournal`/`JournalEntry` (call recording with tenancyId) + `SimulationVerifier`/`MethodVerification` (fluent verification API) + `ProfileSource`/`SimulationProfile` + `RestInvocation`/`RestClientKeyExtractor` |
| `casehub-platform-simulation-inmem` | compile | `InMemorySimulationCorpus` — volatile, thread-safe, ConcurrentHashMap-backed |

**Configuration and wiring:**

| Artifact | Scope | What it provides |
|----------|-------|------------------|
| `casehub-platform-simulation-config-core` | compile | `YamlSimulationConfig` (unified YAML parser: strategy + inline corpus + profiles), `CorpusLoader` SPI + `YamlCorpusLoader`/`JsonCorpusLoader`/`CsvCorpusLoader` + `CompositeCorpusLoader`, `ParameterRegistry` (APT-emitted parameter metadata), `DeclarativeExtractorFactory` (bare parameter name resolution), `DeclarativeScorerFactory`, `RecordFieldScorer`, JSON Schema (`schema/simulation.schema.json`) |
| `casehub-platform-simulation-config` | compile | Quarkus CDI beans: `@Produces SimulationConfig`, `SimulationCorpus`, `SimulationRuntime`; `@Startup` corpus populator; profile wiring. Required alongside `simulation-generator` |

**Code generation (annotation processors):**

| Artifact | Scope | What it provides |
|----------|-------|------------------|
| `casehub-platform-simulation-generator` | provided | APT: generates `@Decorator` + `*QN` constants class + `META-INF/simulation-parameters.properties` per `@SimulationEligible` SPI. Capability-based SPIs: `capabilities` attribute triggers recursive wrapper inner class generation, `supports()` override, dotted QN constants |
| `casehub-platform-rest-client-simulation-generator` | provided | APT: generates `@Decorator` for `@RegisterRestClient` interfaces with `RestInvocation` input |

**Pre-built simulation adapters:**

| Artifact | Scope | What it provides |
|----------|-------|------------------|
| `casehub-platform-platform-simulation-core` | compile | Generated decorators for 11 platform-api SPIs (AccessControlProvider, DataSourceRegistry, SubscriptionStore, NotificationStore, EndpointRegistry, ExpressionEngineRegistry, DocumentSigningService, CredentialResolver, ModelRegistry, PreferenceProvider, CurrentPrincipal) |
| `casehub-platform-memory-simulation-core` | compile | Generated decorator for `CaseMemoryStore` |
| `casehub-platform-agent-simulation-core` | compile | `SimulatedAgentBackend` (Path B), `AgentCorpus` descriptor, `AgentSimulationInput` |

**Event simulation:**

| Artifact | Scope | What it provides |
|----------|-------|------------------|
| `casehub-platform-simulation-core` | compile | `TimedEntry<E>`/`TimedSequence<E>` (relative delays, time multiplier, `map()` payload transformation), `TemporalProfile<E>` (named sequence + loop + speed, `map()` for type-safe domain conversion), `TemporalSimulationDriver<E>` (lifecycle: start/pause/resume/stop/setSpeed/resetSpeed, 3-level speed composition: profile.speed × globalMultiplier with per-driver override, journal integration), `TemporalEventSink<E>`, `TemporalDriverFactory<E>` |
| `casehub-platform-event-simulation-core` | compile | `SimulatedEventEmitter` (tick-based), `EventTrigger`, `CloudEventFixtureBuilder`, `EventSequenceRunner` (one-shot virtual-thread executor) |
| `casehub-platform-event-simulation` | compile | Quarkus CDI wiring: `@Produces SimulatedEventEmitter` with `Event<CloudEvent>` sink, `@Scheduled` continuous tick, `TemporalDriverFactory<Map<String, Object>>` (map → CloudEvent conversion). `TemporalDriverService @McpDomain("temporal-drivers")` — remote control API for temporal simulation drivers (start/stop/pause/resume/setSpeed/resetDriverSpeed/setGlobalSpeed/globalSpeed/status/list), named profile references and inline profile definitions with YAML/Java parity. GraphQL + REST endpoints generated by graphql-generator APT |

**Test utilities:**

| Artifact | Scope | What it provides |
|----------|-------|------------------|
| `casehub-platform-simulation-testing` | test | Per-SPI corpus descriptors (`AclCorpus`, `ModelCorpus`, `NotificationCorpus`, `PreferenceCorpus`, `CredentialCorpus`), `LlmCorpusPopulator` (LLM-generated corpus), `RandomCorpusPopulator` (schema-driven random generation) |
| `casehub-platform-schema-generator` | compile/test | `PlatformSchemaGenerator` (Java → JSON Schema), `SchemaDataGenerator` (JSON Schema → random instances with constraint support) |

**Quick start (3 steps):**

1. Add `simulation-starter` (single aggregate dependency) + `simulation-generator` (provided, for APT)
2. Create `simulation.yaml` on the classpath root (convention discovery):
   ```yaml
   methods:
     access-control-provider.canAccess:
       strategy: key-lookup
       corpus:
         - key: "case:123"
           input: { actorId: "actor-1", resourceId: "case:123", action: "READ" }
           output: true
     case-memory-store.store:
       strategy: sequential
       corpus:
         - input: { domain: cardiology }
           output: "stored"
   ```
3. Optionally reference external corpus files per method:
   ```yaml
   methods:
     case-memory-store.query:
       strategy: key
       corpus-files:
         - classpath:simulation/domain-corpus.yaml
   ```

**Verification (replaces Mockito verify):**

```java
var verifier = SimulationVerifier.on(overlay.journal());
verifier.method("case-memory-store.store").forTenant("hospital-a").wasCalled(2);
verifier.method("case-memory-store.erase").wasNeverCalled();
verifier.inOrder("case-memory-store.query", "case-memory-store.store");
verifier.noUnverifiedCalls();
```

**Five data population paths:**

| Path | Realism level | When to use |
|------|--------------|-------------|
| Inline corpus (`simulation.yaml`) | Domain-plausible | Quick scenario setup |
| `CorpusSeed` + descriptors | Domain-plausible | Typed, per-SPI, programmatic |
| `LlmCorpusPopulator` | Domain-plausible | LLM-generated realistic data |
| `RandomCorpusPopulator` / `SchemaDataGenerator` | Structurally valid | Load testing, integration tests |
| Capture mode (`capture=true`) | Recorded real | CI replay, regression |

**Configuration reference:**

Simulation config lives in `simulation.yaml` (classpath root, convention-discovered). Per-method settings are under the `methods:` key. Profiles are under `profiles:`. Environment-level knobs remain in MicroProfile Config:

| MicroProfile Config Property | Values | Default |
|------------------------------|--------|---------|
| `casehub.simulation.config` | classpath: or filesystem path | `simulation.yaml` (convention) |
| `casehub.simulation.active-profile` | profile name | (none) |
| `casehub.simulation.default-tenancy-id` | tenant ID string | (none) |

Per-method YAML keys: `strategy`, `capture`, `exhaustion-policy`, `key-extractor`, `scorer`, `threshold`, `corpus`, `corpus-files`. See `schema/simulation.schema.json` for the full schema.

**Corpus file formats:** The `corpus-files:` key supports YAML (`.yaml`/`.yml`), JSON (`.json`), and CSV (`.csv`). CSV is useful for industries with tabular reference data (finance, clinical, regulatory):

```csv
_qualified_name,_key,_tenancy_id,accountId,name,balance
bank-feed.balance,acct-123,tenant-a,acct-123,Checking,1234.56
bank-feed.balance,acct-456,tenant-a,acct-456,Savings,5678.90
```

Reserved columns: `_qualified_name` (required), `_key` (optional), `_tenancy_id` (optional). All other columns become the output map.

**Key extractor — parameter names:** For `@SimulationEligible` SPIs, use method parameter names directly as key-extractor specs:

```yaml
methods:
  bank-feed-platform.balance:
    strategy: key
    key-extractor: accountId    # resolves to the 'accountId' parameter
    corpus:
      - key: acct-123
        input: acct-123
        output: "1234.56"
```

The simulation generator emits parameter metadata at build time. For single-arg methods, `key-extractor` defaults to identity — no declaration needed.

### Access control

| Artifact | What it provides |
|----------|------------------|
| `casehub-platform-acl-inmem` | In-memory ACL store (test/ephemeral) -- group-based grants, parent-child hierarchy, deny entries |
| `casehub-platform-acl-jpa` | JPA ACL store with audit logging (production) -- recursive CTE hierarchy, tenant isolation, retention purge |
| `casehub-platform-acl-admin` | REST API for ACL administration -- `@RunOnVirtualThread`, `@RolesAllowed("admin")` |

### Subject views and labels

| Artifact | What it provides |
|----------|------------------|
| `casehub-platform-view` | `SubjectViewEvaluator` + `SubjectViewOrchestrator` -- label-path view evaluation with caching |
| `casehub-platform-view-inmem` | In-memory view store + membership tracker |
| `casehub-platform-view-jpa` | JPA view store -- `JpaLabelPatternQuerySupport` for domain consumers |

### Preference management

| Artifact | What it provides |
|----------|------------------|
| `casehub-platform-preferences-editor` | REST API for preference writes + schema discovery + validation; `PreferenceValidator`; `InMemoryPreferenceSchemaRegistry` |

### PDF generation

| Artifact | What it provides |
|----------|------------------|
| `casehub-platform-pdf` | HTML-to-PDF conversion with PDF/A-2b conformance. `OpenHtmlToPdfGenerator` implements `PdfGenerator` SPI. Bundled Liberation Sans + Mono fonts for reproducible rendering. Classpath-activated — when absent, `NoOpPdfGenerator` returns `Optional.empty()` |

**SPI:** `PdfGenerator.generateFromHtml(String html, PdfOptions options)` returns `Optional<byte[]>`.

**PdfOptions:** `title`, `author`, `createdAt`, `reportType`, `conformance` (default `PdfAConformance.PDFA_2_B`). Use `PdfOptions.defaults()` for basic conversion.

### Document signing

| Artifact | What it provides |
|----------|------------------|
| `casehub-platform-signing` | EU DSS 6.2-backed PAdES PDF signing + CAdES detached signatures. `DssDocumentSigningService` implements `DocumentSigningService`. `DssDocumentVerificationService` implements `DocumentVerificationService`. Classpath-activated — when absent, `NoOp*` defaults return `Optional.empty()` / `UNSIGNED` |

**SPIs** (in `platform-api`, package `io.casehub.platform.api.signing.document`):
- `DocumentSigningService.signPdf(byte[], SigningIdentity)` → `Optional<SignedDocument>` — PAdES embedded
- `DocumentSigningService.signDetached(byte[], SigningIdentity)` → `Optional<DetachedSignature>` — CAdES .p7s
- `DocumentVerificationService.verifyPdf(byte[])` → `DocumentVerificationResult`
- `DocumentVerificationService.verifyDetached(byte[], byte[])` → `DocumentVerificationResult`

**Configuration** (prefix `casehub.signing`):
- `keystore-path` — path to PKCS#12 keystore (signing disabled when absent)
- `keystore-password` — keystore password (resolved via `CredentialResolver` in production)
- `keystore-type` — default `PKCS12`
- `key-alias` — alias for the signing key (default: first alias in keystore)
- `pades-profile` — `B_B`, `B_T` (default), `B_LT`, `B_LTA`
- `tsa-url` — RFC 3161 TSA endpoint (required for B_T+; absent + B_T = fail)
- `expiry-warning-days` — certificate expiry warning threshold (default 30). `CertificateExpiryEvent` CDI event fired when any keystore certificate is within this threshold. `@Scheduled` check every 6h
- `trusted-list-url` — EU LOTL URL for Trusted List validation (e.g. `https://ec.europa.eu/tools/lotl/eu-lotl.xml`). When set, `DssDocumentVerificationService` validates signer certificates against the EU Trusted List. File-cached with 24h expiry. Disabled by default

**Per-tenant keystores:** `TenantKeyStoreResolver` maps tenant IDs to dedicated PKCS#12 files. Unknown tenants fall back to the default keystore. Configure tenant keystores programmatically via `TenantKeyStoreConfig`.

**Runtime rotation:** `KeyStoreRotationService` atomically swaps the active keystore without restart. Failed rotations (wrong password, missing file) keep the existing keystore — no downtime on bad config.

**Profile enforcement:** B_T+ configured without TSA throws `IllegalStateException` — no silent downgrade to B_B.

---

## Key Abstractions and SPIs

### Identity

| SPI | Purpose | Mock behaviour |
|-----|---------|----------------|
| `CurrentPrincipal` | Who is acting -- `actorId()`, `groups()`, `roles()`, `tenancyId()`, `actorType()`, `isSystem()`, `isAuthenticated()`, `isCrossTenantAdmin()` | `@ApplicationScoped` with `@ConfigProperty` values |
| `GroupMembershipProvider` | Inverse membership -- "who is in group X?" | Returns configured groups |

`CurrentPrincipal` is not `SecurityIdentity`. casehub actors include AI agents, system actors, and internal services that operate outside HTTP request context. Real implementations are `@RequestScoped` and delegate to `SecurityIdentity`; the mock is `@ApplicationScoped` (no request context in dev/test).

`GroupMembershipProvider.membersOf(groupName, tenancyId)` is tenant-scoped -- every call requires a `tenancyId` parameter for tenant isolation. `groupsOf(actorId, tenancyId)` provides the reverse lookup.

**Tenancy:** `tenancyId()` is abstract -- every implementor must provide it. Single-tenant deployments return `TenancyConstants.DEFAULT_TENANT_ID`. `isCrossTenantAdmin()` controls cross-tenant data access.

**Actor types:** `ActorType` enum with `HUMAN`, `AGENT`, `SYSTEM`. `ActorTypeResolver.resolve(actorId)` derives the type from the actor ID string. `actorType()` and `isSystem()` use this.

#### Identity Hierarchy

casehub uses a three-level identity hierarchy. All levels share the `type:id`
string format (e.g., `human:john.smith`, `agent:claude:analyst@v1`, `system:scheduler`).

| Level | Type | Use when |
|-------|------|----------|
| **Principal** | `PrincipalId` | Ownership, permissions, ACLs, preferences, memory |
| **Actor** | `ActorId` | Execution context, audit logs, delegation, tool calls |
| **Participant** | `ParticipantId` | Multi-party interaction membership (sessions, conversations) |

**Rules of thumb:**

- Whose memory/preference/permission is this? → `PrincipalId`
- Who performed this action? Who is delegating? → `ActorId`
- Who is in this conversation/session? → `ParticipantId`
- Which tenant's data? → `tenancyId` (not an identity type — see below)

**Conversions:**

```java
// Down — adding context
ActorId actor = ActorId.of(principal);
ParticipantId participant = ParticipantId.of(actor);

// Up — extracting stable identity
PrincipalId principal = actorId.principalId();
PrincipalId principal = participantId.principalId(); // shorthand
```

**Creating identities:**

```java
PrincipalId alice = PrincipalId.human("alice");
PrincipalId claude = PrincipalId.agent("claude:analyst@v1");
PrincipalId cron = PrincipalId.system("scheduler");

// Or parse from a stored string
PrincipalId parsed = PrincipalId.parse("agent:claude");
```

**Tenancy is not identity.** `tenancyId` answers "where" (which organisational boundary), not "who." They are orthogonal — a `PrincipalId` exists within a tenant but is not scoped by it. Never use `tenancyId` as an ownership key. Never use `PrincipalId` as a tenant filter.

**Migration from raw strings:** Existing SPIs use `String actorId`, `String userId`, `String ownerId` — these are all `PrincipalId` semantically. New code should use the typed identity types. Existing SPI signatures will migrate in future issues.

### Path

Hierarchical, scope-labelling type for case types, preference scopes, label paths. Not a filesystem path -- strict validation, no empty segments, no leading/trailing slashes.

```java
Path.of("casehubio", "devtown", "pr-review")  // explicit construction
Path.parse("casehubio/devtown/pr-review")      // uses configured separator
Path.root()                                     // root scope
path.parent()                                   // parent scope
path.isAncestorOf(other)                        // hierarchy check
```

Convention: org segment / app segment / case-type segment. Scope inheritance follows the hierarchy.

JAX-RS integration: `@PathParam` and `@QueryParam` of type `Path` work directly -- converters ship in `platform/`.

### Preferences

| | SmallRye Config | `PreferenceProvider` |
|--|--|--|
| When resolved | Startup | Per-request, per scope |
| Can change without restart | No | Yes |
| Varies per case type | No | Yes |
| Scope hierarchy | No | `casehubio` -> `devtown` -> `pr-review` |

SmallRye Config is for deployment configuration (DB URLs, pool sizes). `PreferenceProvider` is for business configuration (rules that vary per case type and installation). They complement each other.

`PreferenceKey<T>` carries a parser -- `key.parse(raw)` converts strings from any source. Use `key.qualifiedName()` as map keys, never the `PreferenceKey` object (records with `Function` components have identity-only equality).

**Built-in preference types:** `BooleanPreference`, `IntPreference`, `DoublePreference` (all `SingleValuePreference`), `DurationPreference`, `MultiValuePreference`, `MapPreferences`.

**PreferenceStore SPI:** The write path for preferences. Methods: `set(tenancyId, scope, namespace, name, subKey, value)`, `delete(...)`, `list(PreferenceQuery)`, `deleteAll(tenancyId, scope, namespace)`. Implementations in `persistence-jpa/` (JPA) and `persistence-mongodb/` (MongoDB).

**PreferenceSchemaRegistry:** Register, resolve, and discover preference schemas at runtime. `register(PreferenceSchemaDescriptor)`, `resolve(qualifiedName)`, `discover()`, `version()` (monotonic counter for ETag support). `PreferenceSchemaDescriptor` carries namespace, name, type (string/integer/number/boolean/duration/enum), label, description, defaultValue, constraints, and enum options.

**PreferenceValidator:** Server-side validation against schema constraints. Validates type parsing (integer, number, boolean, duration) and constraint checking (`min`, `max`, `minLength`, `maxLength`, `pattern` regex, enum options). Constraint keys are constants in `PreferenceConstraintKeys`.

**Registering preference schemas:** Each module registers its preference key metadata at startup so UIs can discover and render editors. Define `PreferenceKey<T>` constants in a keys class, then create an `@ApplicationScoped` registrar bean:

```java
@ApplicationScoped
public class MyPreferenceRegistrar {
    @Inject PreferenceSchemaRegistry registry;

    void onStart(@Observes StartupEvent event) {
        registry.register(PreferenceSchemaDescriptor.of(MyPreferenceKeys.RETENTION_DAYS)
                .label("Retention (days)")
                .description("Days to retain records before purge")
                .constraints(Map.of(PreferenceConstraintKeys.MIN, 1, PreferenceConstraintKeys.MAX, 3650))
                .build());
    }
}
```

`PlatformPreferenceRegistrar` in `platform/` is the canonical example — it registers 10 preference schemas (6 retention + engagement toggle + retry limit + digest retention + view cache TTL). Type is inferred from the key's `defaultValue` (`IntPreference` → `"integer"`, `BooleanPreference` → `"boolean"`). When `preferences-editor/` is on the classpath, `InMemoryPreferenceSchemaRegistry` captures registrations; otherwise `NoOpPreferenceSchemaRegistry` silently drops them.

**Platform preference keys** (all namespace `casehub.platform`):

| Key | Type | Default | Purpose |
|-----|------|---------|---------|
| `notification.retention-days` | integer | 90 | Days to retain read/dismissed notifications |
| `notification.unread-retention-days` | integer | 365 | Days to retain unread notifications |
| `acl.audit-retention-days` | integer | 365 | Days to retain ACL audit log entries |
| `delivery.attempt-retention-days` | integer | 30 | Days to retain delivery attempts |
| `delivery.failed-retention-days` | integer | 365 | Days to retain failed delivery attempts |
| `delivery.engagement-retention-days` | integer | 90 | Days to retain engagement events |
| `delivery.engagement-enabled` | boolean | false | Enable engagement event recording |
| `delivery.retry-max-retries` | integer | 5 | Max retry attempts before delivery expiry |
| `notification.digest-retention-days` | integer | 90 | Days to retain digest buffer entries |
| `view.cache-ttl-seconds` | integer | 0 | View cache TTL (0 = disabled) |

### DataSource and Alpha Network

Rete-style event routing: `DataSource<T>` ingests objects, `ObjectType<T>` discriminates by type, `FilterExpression<T>` evaluates predicates. Four `subscribe()` overloads with increasing specificity. Self-pruning deregistration lifecycle handles shutdown gracefully.

`DataSourceRegistry` is tenant-scoped -- `resolve(Path, tenancyId)` returns tenant-specific before platform-global. `DataSourceDescriptor` carries `path`, `tenancyId`, `objectType`, and `acceptedEventTypes` for CloudEvent pre-filtering.

**DataSourceRouter** (in `platform/`): CDI bridge that routes `@ObservesAsync CloudEvent` events to registered DataSources. Extracts `tenancyid` extension from CloudEvents for tenant routing. Convergent event-handler design -- correct wiring state regardless of CDI event processing order.

**CloudEventTypeDispatcher** (in `platform/`): Routes unqualified `@ObservesAsync CloudEvent` events to observers qualified with `@CloudEventType("io.casehub.some.type")`. Enables type-specific CloudEvent handling without raw type string comparisons.

### Notifications and Subscriptions

Domain modules produce `SubscribableEvent` objects into the notification DataSource. The subscription engine evaluates them against the alpha network, fires `SubscriptionMatched`, and the dispatch pipeline handles delivery (immediate, digest, or suppressed). REST endpoints and WebSocket push (via `EventBroadcaster`) expose notifications to clients.

**SubscribableEvent interface:** Compile-time contract for subscription POJOs. Must implement `type()` (reverse-DNS event type string, e.g. `"io.casehub.work.workitem.completed"`) and `tenancyId()`. POJOs not implementing this interface are silently rejected by the subscription engine.

**SubscriptionScope:** `USER` (per-user subscriptions) or `SYSTEM` (admin-managed, system-wide subscriptions with admin authorization).

**Event type glob matching:** Subscription `eventType` fields support prefix patterns (e.g. `"io.casehub.work.*"`) for matching groups of event types.

### Notification Delivery

**Delivery channels:** Well-known constants in `DeliveryChannels`: `IN_APP`, `EMAIL`, `SMS`, `PUSH`, `WHATSAPP`.

**NotificationDeliverer SPI:** Implement to deliver notifications via a specific channel. Methods: `channelId()`, `deliver(NotificationInput)`, `deliverDigest(DigestSummary)`. Self-registers its `DeliveryChannelDescriptor` in the `DeliveryChannelRegistry` at `@PostConstruct`.

**DestinationResolver SPI:** Resolves a user's delivery destination for a specific channel. Methods: `channelId()`, `resolve(userId, tenancyId)`. One implementation per channel type.

**DestinationScope:** `PER_USER` (email, SMS, WhatsApp -- resolves to user contact attribute) or `PER_TENANT` (future -- Slack, Teams -- resolves to shared webhook URL).

**Digest system:** Configurable digest schedules via `DigestSchedule` sealed interface:
- `DigestSchedule.Interval(Duration period)` -- fixed period (minimum 1 minute)
- `DigestSchedule.DailyAt(LocalTime time, ZoneId timezone)` -- once per day
- `DigestSchedule.WeeklyAt(DayOfWeek day, LocalTime time, ZoneId timezone)` -- once per week

**DigestGroupBy:** `FLAT` (no grouping), `CATEGORY` (by notification category), `ENTITY` (by entity type and ID).

**Engagement tracking:** `EngagementType` enum: `OPENED`, `CLICKED`, `DISMISSED`, `REPLIED`, `CONVERTED`. `EngagementCallbackHandler` SPI translates provider-specific webhook payloads into platform engagement events (must verify request signatures via provider-specific headers).

### Expression Evaluation

`ExpressionEngineRegistry` dispatches by type key. Three engines are available:

| Engine | Type Key | Backend | Context Type | Notes |
|--------|----------|---------|-------------|-------|
| `JQExpressionEngine` | `"jq"` | jackson-jq 1.6 | `JsonNode` or `Map<String, Object>` (auto-adapted) | Boolean, List, and Scalar result types. `$config` and `$secret` scope injection. |
| `MvelExpressionEngine` | `"mvel"` | MVEL3 3.0.0-SNAPSHOT | `Map<String, Object>` or POJO (auto-adapted via BeanInfo) | Block expressions (semicolon-delimited). Lazy compilation on first eval. |
| `JexlExpressionEngine` | `"jexl"` | Commons JEXL 3.4.0 | `Map<String, Object>` | MapContext-based. Strict mode off, silent mode off. Cached compilation. |

**ConfigManager SPI:** Provides access to configuration properties in JQ expressions via `$config.{configMapName}.{property}`. Default implementation reads from SmallRye Config (MicroProfile Config API). Supports Kubernetes ConfigMaps via optional `quarkus-kubernetes-config` dependency.

**SecretManager SPI:** Resolves secrets in JQ expressions via `$secret.{secretName}.{property}`. Default reads from `casehub.platform.secrets.{secretName}.{property}` config keys. Supports Kubernetes Secrets via optional `quarkus-kubernetes-config`.

**StringExpressionEvaluator:** Sub-interface of `ExpressionEvaluator` for string-based evaluators (carries `expression()` string). Concrete records: `JQExpressionEvaluator`, `MvelExpressionEvaluator`.

### Signing

`SigningProvider` SPI for cryptographic signing operations. `SignatureVerifier` for verification. `NoOpSigningProvider` `@DefaultBean` is a silent no-op. Real implementations plug in via CDI displacement.

### SessionIsolator

`SessionIsolator` SPI — virtual-thread-safe Hibernate session isolation. Wraps JPA calls that would otherwise fail on virtual threads due to Hibernate's thread-local session management. Use for any blocking JPA operations in `@RunOnVirtualThread` contexts (e.g. `NotificationPushService`).

### Access Control

`AccessControlProvider` provides blocking access control with resource hierarchy inheritance. Group-based grants resolve via `GroupMembershipProvider`. Parent-child hierarchy with depth guard of 20.

**ResourceId:** Type-safe resource identifier replacing raw `String resourceId`. `new ResourceId(type, id)` creates a typed reference; `ResourceId.parse("case:123")` parses the `type:id` format; `ResourceId.fromString(value)` is an alias for `parse`. All ACL SPI methods now accept `ResourceId` instead of separate `resourceType` + `resourceId` parameters.

**Action hierarchy:** `AclAction` enum: `READ`, `WRITE`, `ADMIN`, `CLAIM`. ADMIN implies WRITE implies READ -- a WRITE grant satisfies a READ check; an ADMIN grant satisfies both READ and WRITE. CLAIM is independent. `satisfiedBy()` and `deniedBy()` methods encode this hierarchy.

**Deny entries:** `deny(actorId, resourceId, action, expires)` creates explicit deny entries. Resolution order: instance deny -> instance grant -> wildcard deny -> wildcard grant -> parent chain. Deny wins at each specificity level.

**Wildcard type-level grants:** Grant `"case:*"` to give an actor access to all resources of type `case`. Checked after instance-level entries.

**Bulk operations:** `grantBatch(Collection<AclEntryRequest>)`, `revokeBatch(...)`, `denyBatch(...)`, `removeDenyBatch(...)`.

**Paginated queries:** `accessibleResources(AclQuery)` returns `AclPage` with cursor-based pagination (default limit 100, max 500).

**Inherited children:** `accessibleResourcesIncludingInherited(actorId, resourceType, action)` walks the parent-child hierarchy to surface children of directly-granted resources.

**Well-known resource types:** Constants in `AclResourceType`: `CASE`, `PLAN_ITEM`, `WORK_ITEM`, `EVENT_LOG`, `CASE_DEFINITION`.

### Modular Notification Targets

The notification pipeline supports two target kinds:
- **USER** (TargetType: `USER`, `GROUP`, `EVENT_FIELD`, `ENTITY_WATCHERS`) — full pipeline with preferences, suppression, digest, inbox persistence
- **NON_USER** (TargetType: `AGENT`, `SYSTEM`) — skip suppression, fire-and-forget via `CdiEventDeliverer`

To make a domain event subscribable, implement `SubscribableEvent` (`type()`, `tenancyId()`). The subscription engine matches it automatically. Create subscriptions with `AGENT` or `SYSTEM` targets for operational alerts.

Built-in subscribable events: `CapacityPressureEvent` (`capacity.pressure`), `CertificateExpiryEvent` (`certificate.expiry`).

### Subject Views and Labels

**SubjectViewSpec:** A view definition with `id`, `name`, `tenancyId`, `labelPattern` (supports glob patterns `/**` and `/*`), `scope` (Path, optional), `sortField`, `sortDirection`, `additionalConditions`, and `createdAt`.

**SubjectViewEvaluator:** Evaluates subject membership in views by matching label paths against view label patterns. Scope-aware overload filters views by subject scope hierarchy. `computeEvents()` diffs before/after membership to produce `SubjectViewEvent` records with `ADDED`, `REMOVED`, or `CHANGED` types.

**SubjectViewOrchestrator:** High-level coordinator with optional view caching (`casehub.view.cache.ttl-seconds`). Methods: `evaluateAndTrack(subjectId, tenancyId, labelPaths)` (single subject), `evaluateAndTrackBatch(subjectLabelPaths, tenancyId)` (bulk), scope-aware variants, `saveView(spec)`, `deleteView(viewId)` (with proactive membership cleanup and REMOVED events).

**ViewMembershipTracker:** Tracks which subjects belong to which views. `getLastKnownMembership(subjectId)` (single), `getLastKnownMembership(Set<UUID> subjectIds)` (bulk), `updateMembership(...)`, `removeMembership(...)`, `getSubjectsByView(viewId)`, `removeMembershipByView(viewId)`.

**CrossTenantSubjectViewStore:** `findDistinctTenancyIds()` -- for cross-tenant operations.

**LabelPatternMatcher:** Utility for matching label paths against patterns. Supports exact match, single-level wildcard (`status/*`), and recursive wildcard (`status/**`).

**Label infrastructure:** `LabelRule` record with `name`, `condition` (CompiledExpression), `actions` (List<LabelAction>), `triggerEvents` (Set<String>, optional). Static `evaluate(rules, context)` and `evaluate(rules, context, event)` methods. `LabelAction` is a sealed interface with `Add(label)` and `Remove(label)` variants.

### CaseMemoryStore (migrated)

The `CaseMemoryStore` SPI and related types (`MemoryDomain`, `MemoryPermissions`, `MemoryQuery`) migrated to casehub-neocortex. The `@DefaultBean` no-op (`NoOpCaseMemoryStore`) also lives in neocortex. Platform no longer owns memory abstractions — consume `casehub-neocortex-memory-api` directly.

### Credentials

`CredentialResolver` resolves outbound endpoint credentials by logical reference name. Returns `Map<String, String>` keyed by `CredentialPropertyKeys` constants: `USER`, `PASSWORD`, `BEARER_TOKEN`, `API_KEY`, `EXPIRES_AT`, `SIGNING_SECRET`. Distinct from inbound Verifiable Credential validation in identity.

### Governance

`ExecutionPolicy` + `RetryPolicy` + `BackoffStrategy` -- generic retry/timeout/backoff for blocking operations via `PolicyEnforcer.execute(policy, action)`.

`BackoffStrategy` enum: `FIXED`, `EXPONENTIAL`, `EXPONENTIAL_WITH_JITTER`. `RetryPolicy` record: `maxAttempts`, `delayMs`, `backoffStrategy`, `maxDelayMs`. The `DefaultPolicyEnforcer` runs on a virtual-thread executor.

### Actor State

`ActorStateContributor` SPI: domain modules implement this to contribute to a unified actor state view. `sourceName()` identifies the source; `contribute(actorId, accumulator)` feeds data into the `ActorStateAccumulator`. The accumulator collects `trustScore`, `capabilityScore`, `workItem`, `commitment`, and `engineActiveCaseId` data from multiple backends concurrently. Contributors must be atomic -- all-or-nothing per source.

### Strategy Resolution

`StrategyResolver` discovers `NamedStrategy` beans by type and ID. `resolve(type, id)`, `find(type, id)`, `defaultStrategy(type)`, `available(type)`. Used for pluggable strategy selection across the platform.

### Agent Infrastructure

**Two-SPI design:** `AgentProvider` is the caller-facing SPI. `AgentBackend` is the implementor-facing SPI. `RoutingAgentProvider` bridges them — it implements `AgentProvider`, discovers `AgentBackend` beans via CDI `Instance`, and resolves the `model` field via three-step resolution: (1) `ModelRegistry.resolveById` — routes to backend from `descriptor.backendKey()`, rewrites config with the API model ID, (2) direct `backends.get(model)` — model nulled so backend uses its default, (3) fail-fast `IllegalArgumentException`. Callers always inject `AgentProvider`, never `AgentBackend`.

`AgentProvider` has two execution paths:
- `invoke(AgentSessionConfig)` -- single-shot, returns cold `Multi<AgentEvent>`. The `AgentSessionConfig` carries `systemPrompt`, `userPrompt`, `mcpServers`, `timeout`, `correlationId`, and nullable `model` (provider key).
- `openSession(AgentSessionInit)` -- multi-turn `AgentSession` (IDLE/ACTIVE/CLOSED state machine). `AgentSessionInit` carries `systemPrompt`, `mcpServers`, `timeout`, `correlationId`, and nullable `model`.

`AgentBackend` has the same two methods plus `key()` — a string identifying the provider ("claude", "openai", "codex", "gemini", "gemini-cli", "langchain4j"). When `model` is null, the configurable default backend is used. When `model` matches no backend key and no `ModelRegistry` entry, resolution fails fast with `IllegalArgumentException`.

`AgentRuntime` abstracts subprocess lifecycle for CLI-based providers. `SubprocessRuntime` wraps `ProcessBuilder`; future runtimes (Kubernetes, container) would slot in without touching provider code. Only CLI providers (`agent-codex`, `agent-gemini-cli`) inject `AgentRuntime`.

`AgentEvent` is a sealed interface with variants: `TextDelta`, `ThinkingDelta`, `ToolCallDelta`, `ToolCallComplete`, `ToolResult`, `InvocationComplete` (terminal with cost/usage/timing metadata).

`AgentMcpServer` is a sealed interface with three transport variants:
- `Stdio(command, args, env)` -- subprocess MCP server
- `Sse(url, headers)` -- legacy HTTP Server-Sent Events transport
- `Http(url, headers)` -- current streamable HTTP MCP transport (preferred for new servers)

**Session leak detection:** `GatedAgentSession` maintains a registry of open sessions. An `@Scheduled` reaper detects sessions exceeding their timeout without being closed, logs a warning, and performs idempotent cleanup. Sessions implement `AutoCloseable` with idempotent close semantics.

**MCP infrastructure:**
- `casehub_activate` — on-demand per-operation tool registration. Agents discover and activate tools at runtime instead of exposing all tools at startup.
- **Resource subscriptions:** `McpResourceRegistry` SPI for registering subscribable MCP resources. `McpResourceRegistryBridge` tracks subscriptions and fires notifications on resource changes.
- **Dynamic tool schema:** The operation catalog is injected into the `casehub_action` tool definition at runtime, providing contextual tool descriptions.
- `@McpDomain` interfaces discovered directly with `@PlatformQuery`/`@PlatformMutation` annotations. The `graphql-generator` APT generates both `@GraphQLApi` resolvers and `@Path` JAX-RS REST resources from these interfaces. Discovery sources: Jandex indexes from dependency JARs + RoundEnvironment (consumer SPIs in the current compilation unit are found automatically). Use `@RestMethod(HttpMethod.DELETE)` for non-POST mutations, `@PathParam` for path segments. Processor options: `-AdomainFilter` to scope generation per module, `-AgenerateGraphQL=false` to suppress GraphQL output, `-AgenerateRest=false` to suppress REST output. NOTE-level diagnostic logging reports received options, discovered domains (with source: JANDEX or ROUND_ENV), and filter decisions; WARNING emitted when domainFilter matches zero domains.

---

## Configuration

### Identity

| Property | Purpose | Default |
|----------|---------|---------|
| `casehub.tenancy.default-id` | Default tenant ID for single-tenant deployments | (TenancyConstants value) |
| `casehub.platform.scim.token` | Static SCIM auth token | -- |
| `quarkus.oidc-client.scim.*` | SCIM client-credentials auth | -- |
| `casehub.identity.dids."actorId"` | Static actor-to-DID mapping | -- |
| `casehub.identity.credentials."actorId"` | VC JWT file paths | -- |

### Preferences

| Property | Purpose | Default |
|----------|---------|---------|
| `casehub.platform.path.separator` | Path separator character | `/` |
| `casehub.platform.endpoints.files` | YAML endpoint definition files | -- |

### Agent

| Property | Purpose | Default |
|----------|---------|---------|
| `casehub.platform.agent.default-backend` | Default provider key when `model` is null | claude |
| `casehub.platform.agent.claude.binaryPath` | Path to Claude CLI binary | (resolved from PATH) |
| `casehub.platform.agent.claude.defaultTimeout` | Default wall-clock timeout | PT5M |
| `casehub.platform.agent.claude.maxConcurrentSessions` | Concurrent Claude session limit | 4 |
| `casehub.platform.agent.openai.api-key` | OpenAI API key | (from OPENAI_API_KEY env) |
| `casehub.platform.agent.openai.default-model` | Default OpenAI model | gpt-4.1 |
| `casehub.platform.agent.openai.prompt-cache-retention` | Cache retention policy | in_memory |
| `casehub.platform.agent.openai.default-timeout` | Default wall-clock timeout | PT5M |
| `casehub.platform.agent.openai.max-concurrent-sessions` | Concurrent OpenAI session limit | 4 |
| `casehub.platform.agent.codex.binary-path` | Path to Codex CLI binary | codex |
| `casehub.platform.agent.codex.default-timeout` | Default wall-clock timeout | PT5M |
| `casehub.platform.agent.codex.max-concurrent-sessions` | Concurrent Codex session limit | 4 |
| `casehub.platform.agent.gemini.api-key` | Gemini API key | (from env) |
| `casehub.platform.agent.gemini.default-model` | Default Gemini model | gemini-2.5-flash |
| `casehub.platform.agent.gemini.cache-ttl` | Explicit cache TTL | PT1H |
| `casehub.platform.agent.gemini.default-timeout` | Default wall-clock timeout | PT5M |
| `casehub.platform.agent.gemini.max-concurrent-sessions` | Concurrent Gemini session limit | 4 |
| `casehub.platform.agent.gemini-cli.binary-path` | Path to Gemini CLI binary | gemini |
| `casehub.platform.agent.gemini-cli.default-timeout` | Default wall-clock timeout | PT5M |
| `casehub.platform.agent.gemini-cli.max-concurrent-sessions` | Concurrent Gemini CLI session limit | 4 |
| `casehub.platform.agent.langchain4j.closeTimeout` | Session close timeout | PT30S |
| `casehub.platform.agent.langchain4j.sessionMemoryWindowSize` | Conversation memory window | 20 |
| `casehub.platform.agent.langchain4j.max-concurrent-sessions` | Concurrent LangChain4j session limit | 10 |

### Expression

| Property | Purpose | Default |
|----------|---------|---------|
| `casehub.platform.secrets.{name}.{property}` | Secret values accessible as `$secret.{name}.{property}` in JQ | -- |
| `%prod.quarkus.kubernetes-config.enabled` | Enable Kubernetes ConfigMap/Secret integration | false |
| `%prod.quarkus.kubernetes-config.config-maps` | Kubernetes ConfigMaps to read | -- |
| `%prod.quarkus.kubernetes-config.secrets` | Kubernetes Secrets to read | -- |

#### Spring Boot Kubernetes Configuration

For Spring Boot deployments, add `spring-cloud-kubernetes-fabric8-config` to your application's classpath to enable Kubernetes ConfigMap and Secret reading:

| Property | Purpose | Default |
|----------|---------|---------|
| `spring.cloud.kubernetes.config.enabled` | Enable Kubernetes ConfigMap integration | false |
| `spring.cloud.kubernetes.config.sources[0].name` | Kubernetes ConfigMap name to read | -- |
| `spring.cloud.kubernetes.secrets.enabled` | Enable Kubernetes Secret integration | false |
| `spring.cloud.kubernetes.secrets.sources[0].name` | Kubernetes Secret name to read | -- |

ConfigMap and Secret format is identical to the Quarkus examples above. The `ConfigManager` and `SecretManager` SPIs read from Spring's `Environment` — any property source that feeds into `Environment` (including `spring-cloud-kubernetes`) is automatically available.

### Simulation

| Property | Purpose | Default |
|----------|---------|---------|
| `casehub.simulation.<spi>.<method>.strategy` | Strategy key: `sequential`, `key-lookup`, `random`, `recorded-replay` | none (passthrough) |
| `casehub.simulation.<spi>.<method>.capture` | Enable capture mode | false |
| `casehub.simulation.<spi>.<method>.exhaustion-policy` | Sequential exhaustion: `WRAP` or `THROW` | WRAP |

### Streams

| Property | Purpose | Default |
|----------|---------|---------|
| `casehub.streams.webhook.public-url` | Public URL for webhook self-registration | (required) |
| `casehub.streams.poll.interval` | Polling interval | 60s |

### Notifications

| Property | Purpose | Default |
|----------|---------|---------|
| `casehub.notification.digest.max-buffer-size` | Digest buffer size (0 = no eviction) | 0 |
| `casehub.delivery.tracking.inmem.max-size` | In-memory delivery attempt store size | 10000 |
| `casehub.delivery.engagement.enabled` | Enable engagement event recording | false |
| `casehub.delivery.retention.attempt-days` | Delivery attempt retention | -- |
| `casehub.delivery.retention.failed-attempt-days` | Failed attempt retention | -- |
| `casehub.delivery.retention.engagement-days` | Engagement event retention | -- |

### Modular Notification Targets

The notification pipeline supports two target kinds:
- **USER** (TargetType: `USER`, `GROUP`, `EVENT_FIELD`, `ENTITY_WATCHERS`) — full pipeline with preferences, suppression, digest, inbox persistence
- **NON_USER** (TargetType: `AGENT`, `SYSTEM`) — skip suppression, fire-and-forget via `CdiEventDeliverer`

To make a domain event subscribable, implement `SubscribableEvent` (`type()`, `tenancyId()`). The subscription engine matches it automatically. Create subscriptions with `AGENT` or `SYSTEM` targets for operational alerts.

Built-in subscribable events: `CapacityPressureEvent` (`capacity.pressure`), `CertificateExpiryEvent` (`certificate.expiry`).

### Subject Views

| Property | Purpose | Default |
|----------|---------|---------|
| `casehub.view.cache.ttl-seconds` | View cache TTL (0 = disabled) | 0 |

### ACL

| Property | Purpose | Default |
|----------|---------|---------|
| `casehub.acl.retention.expired-purge-cron` | Expired entry purge schedule | daily 03:00 |
| `casehub.acl.retention.audit-purge-cron` | Audit log purge schedule | daily 03:30 |
| `casehub.acl.retention.audit-days` | Audit log retention | 365 |

### Flyway locations (add to consumer's config)

| Module | Flyway location |
|--------|----------------|
| `persistence-jpa` | `classpath:db/platform/migration` |
| `datasource-jpa` | `classpath:db/datasource/migration` |
| `notifications-jpa` | `classpath:db/notification/migration` |
| `notification-settings-jpa` | `classpath:db/notification-settings/migration` |
| `delivery-tracking-jpa` | `classpath:db/delivery-tracking/migration` |
| `digest-jpa` | `classpath:db/digest/migration` |
| `subscriptions-jpa` | `classpath:db/subscription/migration` |
| `acl-jpa` | `classpath:db/acl/migration` |

---

## REST APIs

### Preference Management (`preferences-editor/`)

| Method | Path | Auth | Description |
|--------|------|------|-------------|
| `PUT` | `/preferences` | -- | Set a preference (validates against schema if registered) |
| `DELETE` | `/preferences` | -- | Delete a single preference by namespace/name/subKey |
| `DELETE` | `/preferences/by-namespace` | -- | Delete all preferences in a namespace |
| `GET` | `/preferences` | -- | List all preference records for current tenant |
| `GET` | `/preferences/resolved` | -- | Resolve preferences with full ancestor-chain inheritance |
| `GET` | `/preferences/schema` | -- | List registered schema descriptors (ETag conditional GET) |

### ACL Administration (`acl-admin/`)

| Method | Path | Auth | Description |
|--------|------|------|-------------|
| `POST` | `/acl/grants` | `@RolesAllowed("admin")` | Grant single entry |
| `POST` | `/acl/grants/batch` | `@RolesAllowed("admin")` | Bulk grant |
| `DELETE` | `/acl/grants` | `@RolesAllowed("admin")` | Revoke single |
| `DELETE` | `/acl/grants/batch` | `@RolesAllowed("admin")` | Bulk revoke |
| `DELETE` | `/acl/grants/all` | `@RolesAllowed("admin")` | Revoke all for actor+resource |
| `POST` | `/acl/denies` | `@RolesAllowed("admin")` | Deny single entry |
| `POST` | `/acl/denies/batch` | `@RolesAllowed("admin")` | Bulk deny |
| `DELETE` | `/acl/denies` | `@RolesAllowed("admin")` | Remove single deny |
| `DELETE` | `/acl/denies/batch` | `@RolesAllowed("admin")` | Bulk remove deny |
| `POST` | `/acl/parents` | `@RolesAllowed("admin")` | Register parent-child relationship |
| `GET` | `/acl/check` | self or admin | Check access (returns `{allowed: true/false}`) |
| `GET` | `/acl/accessible` | self or admin | Paginated accessible resources (cursor-based) |

---

## Spring Boot

casehub-platform ships dual-framework support. Every Quarkus CDI module has a Spring Boot auto-configuration counterpart. The same core POJOs, SPIs, and `platform-api` types work in both frameworks — only the wiring layer differs.

### Quick Start

Three starters are available — add only what your app needs:

```xml
<!-- Core — persistence, MCP, callbacks, identity, mock fallbacks -->
<dependency>
    <groupId>io.casehub</groupId>
    <artifactId>casehub-spring-boot-starter</artifactId>
    <version>${casehub.version}</version>
</dependency>

<!-- Agent — add if your app invokes LLMs (Claude, OpenAI, Gemini, etc.) -->
<dependency>
    <groupId>io.casehub</groupId>
    <artifactId>casehub-spring-boot-starter-agent</artifactId>
    <version>${casehub.version}</version>
</dependency>

<!-- Streams — add if your app consumes external events (Kafka, AMQP, Camel, Poll) -->
<dependency>
    <groupId>io.casehub</groupId>
    <artifactId>casehub-spring-boot-starter-streams</artifactId>
    <version>${casehub.version}</version>
</dependency>
```

The agent and streams starters each pull in the core starter transitively. Most apps need only the core starter; add agent or streams when those capabilities are required.

For selective dependencies below the starter level, add individual modules instead (see table below).

### Application setup

```java
@SpringBootApplication
@EntityScan("io.casehub.platform")
public class MyApplication {
    public static void main(String[] args) {
        SpringApplication.run(MyApplication.class, args);
    }
}
```

`@EntityScan("io.casehub.platform")` is required for Hibernate to find JPA entities from platform modules.

### Minimal `application.properties`

```properties
# DataSource — PostgreSQL for production
spring.datasource.url=jdbc:postgresql://localhost:5432/mydb
spring.datasource.username=myuser
spring.datasource.password=mypass

# Flyway — add locations for each JPA module you use
spring.flyway.locations=classpath:db/migration,classpath:db/platform/migration,classpath:db/acl/migration,classpath:db/notification/migration

# Jackson 2 bridge — required until Quarkus 4 aligns on Jackson 3
# (spring-boot-jackson2 is included in the starter)
```

### Spring modules

| Spring Module | Quarkus Counterpart | What it provides |
|---------------|-------------------|------------------|
| `casehub-platform-spring` | `casehub-platform` | Mock fallback beans (`CurrentPrincipal`, `PreferenceProvider`) via `@ConditionalOnMissingBean` |
| `casehub-platform-persistence-spring-jpa` | `casehub-platform-persistence-jpa` | Spring Data JPA `PreferenceStore` + `PreferenceProvider` |
| `casehub-platform-acl-spring-jpa` | `casehub-platform-acl-jpa` | Spring Data JPA `AccessControlProvider` with deny entries and audit |
| `casehub-platform-notifications-spring-jpa` | `casehub-platform-notifications-jpa` | Spring Data JPA `NotificationStore` with retention scheduler |
| `casehub-platform-notification-settings-spring-jpa` | `casehub-platform-notification-settings-jpa` | Spring Data JPA `NotificationPreferenceStore` + `SuppressionStore` |
| `casehub-platform-subscriptions-spring-jpa` | `casehub-platform-subscriptions-jpa` | Spring Data JPA `SubscriptionStore` |
| `casehub-platform-datasource-spring-jpa` | `casehub-platform-datasource-jpa` | Spring Data JPA `DataSourceRegistry` |
| `casehub-platform-digest-spring-jpa` | `casehub-platform-digest-jpa` | Spring Data JPA `DigestBuffer` |
| `casehub-platform-delivery-tracking-spring-jpa` | `casehub-platform-delivery-tracking-jpa` | Spring Data JPA `DeliveryAttemptStore` with `SKIP LOCKED` claims |
| `casehub-platform-view-spring-jpa` | `casehub-platform-view-jpa` | Spring Data JPA `SubjectViewStore` + `ViewMembershipTracker` |
| `casehub-platform-view-spring` | `casehub-platform-view` | `SubjectViewOrchestrator` + `SubjectViewEvaluator` |
| `casehub-platform-identity-spring` | `casehub-platform-identity` | DID resolver composite + credential validation |
| `casehub-platform-expression-spring` | `casehub-platform-expression` | Expression engine registry (JQ, MVEL, JEXL) |
| `casehub-platform-governance-spring` | `casehub-platform-governance` | `PolicyEnforcer` for retry/timeout/circuit-breaker |
| `casehub-platform-callback-spring` | `casehub-platform-callback` | `BeanPostProcessor` that wraps `@CallbackEligible` SPIs |
| `casehub-platform-mcp-spring` | `casehub-platform-mcp` | MCP tool infrastructure |
| `casehub-platform-agent-*-spring` | `casehub-platform-agent-*` | Agent backend auto-configs (Claude, OpenAI, Codex, Gemini, Gemini CLI, LangChain4j, Router, Config, Gate) |
| `casehub-platform-spring-actuator` | `casehub-platform-observability` | Health indicators (model registry, agent backends, delivery channels, SCIM, certificate expiry), metrics BPP (wraps AgentBackend + AccessControlProvider), info contributor |

### Auto-configuration behaviour

Every Spring module uses `@AutoConfiguration` with `@ConditionalOnMissingBean`. To override a platform default, declare your own `@Bean` of the same type — it takes precedence automatically.

```java
@Configuration
public class MyPrincipalConfig {

    @Bean
    public CurrentPrincipal currentPrincipal(SecurityContext securityContext) {
        return new MyOidcPrincipal(securityContext);
    }
}
```

The platform mock (`MockCurrentPrincipal`) backs off because your bean satisfies `@ConditionalOnMissingBean`.

### CDI to Spring mapping

| CDI (Quarkus) | Spring Boot | Notes |
|---------------|-------------|-------|
| `@ApplicationScoped` | `@Bean` in `@AutoConfiguration` | Singleton scope |
| `@DefaultBean` | `@ConditionalOnMissingBean` | Backs off when app provides its own |
| `@Alternative @Priority(N)` | `@Primary` or `@Order(N)` | Priority-based selection |
| `@Inject` | Constructor injection | Both prefer constructor injection |
| `@ConfigMapping(prefix)` | `@ConfigurationProperties(prefix)` | Type-safe config binding |
| `@Observes StartupEvent` | `@EventListener ApplicationStartedEvent` | Startup hooks |
| `@Scheduled(every)` | `@Scheduled(fixedDelay/cron)` | Periodic tasks |
| `Instance<T>` | `ObjectProvider<T>` or `List<T>` | Optional/multi injection |
| CDI `@Decorator` | `BeanPostProcessor` | Transparent wrapping |
| `Event.fire()` | `ApplicationEventPublisher` | CDI events → Spring events |

### Test setup

Add `casehub-platform-spring-testing` (test scope) for mutable `CurrentPrincipal` and SPI stubs.

**Basic test with H2:**

```java
@SpringBootTest
@Import(TestStubConfiguration.class)
class MyTest {

    @Autowired
    private PreferenceProvider preferenceProvider;

    @Test
    void preferencesWork() {
        // ...
    }
}
```

**Test `application.properties`:**

```properties
spring.datasource.url=jdbc:h2:mem:testdb;MODE=PostgreSQL
spring.datasource.driver-class-name=org.h2.Driver
spring.jpa.hibernate.ddl-auto=create-drop
spring.flyway.enabled=false
```

H2 with `MODE=PostgreSQL` approximates PostgreSQL behaviour for integration tests. Disable Flyway and use `ddl-auto=create-drop` so Hibernate generates the schema from entities.

### Spring configuration properties

All `casehub.*` properties have IDE autocomplete metadata (`additional-spring-configuration-metadata.json`). See the [Configuration](#configuration) section above for the full property reference — the same property names apply in both `application.properties` (Spring) and `application.properties`/`application.yml` (Quarkus).

### Streams (Spring Boot)

Spring adapters for the four streaming modules. Each delegates to a framework-neutral core POJO.

| Spring module | Listener | Configuration |
|---------------|----------|---------------|
| `streams-poll-spring` | `@Scheduled` | `casehub.streams.poll.interval` (ms, default 60000) |
| `streams-kafka-spring` | `@KafkaListener` | `casehub.streams.kafka.topic`, `spring.kafka.consumer.*` |
| `streams-amqp-spring` | `@RabbitListener` | `casehub.streams.amqp.queue`, `spring.rabbitmq.*` |
| `streams-camel-spring` | `@EventListener` | Requires `camel-spring-boot-starter` on classpath |

All streams modules use synchronous `ApplicationEventPublisher.publishEvent()` for CloudEvent dispatch — semantically equivalent to Quarkus CDI `fireAsync()` (message acked after all listeners complete). A slow `@EventListener` blocks the consumer thread. Add `@Async` to individual listeners if throughput is a concern.

**Kafka config mapping** (SmallRye → Spring):
- `mp.messaging.incoming.<channel>.topic` → `casehub.streams.kafka.topic`
- `mp.messaging.incoming.<channel>.bootstrap.servers` → `spring.kafka.consumer.bootstrap-servers`

**AMQP config mapping** (SmallRye → Spring):
- `mp.messaging.incoming.<channel>.address` → `casehub.streams.amqp.queue`
- `mp.messaging.incoming.<channel>.host` → `spring.rabbitmq.host`

### REST controller generation (`rest-spring-generator`)

The `rest-spring-generator` Maven plugin generates Spring MVC `@RestController` classes from existing JAX-RS `@Path` resources. Each generated controller delegates to a framework-neutral core POJO — the same POJO that the Quarkus resource uses.

**Prerequisite — core extraction.** Each `@Path` resource must delegate to a single injected POJO. The scanner finds the first `@Inject` field (or the first constructor parameter) and treats its type as the delegate. Resources with no injectable delegate are skipped.

```
MyResource.java                  → thin @Path adapter (one-liner methods)
core/MyCore.java                 → business logic, constructor-injected, typed returns
```

Core POJO rules:
- Constructor injection only — no CDI, no Spring, no JAX-RS imports
- Typed return values (`List<T>`, `Optional<T>`, `T`, `void`) — never `Response`
- `@jakarta.transaction.Transactional` on methods where needed (portable annotation)
- Package: `<resource-package>.core`

**Plugin configuration:**

```xml
<plugin>
    <groupId>io.casehub</groupId>
    <artifactId>casehub-platform-rest-spring-generator</artifactId>
    <version>${version.io.casehub}</version>
    <executions>
        <execution>
            <id>generate-rest</id>
            <goals><goal>generate</goal></goals>
            <configuration>
                <quarkusModules>
                    <quarkusModule>${project.basedir}/../my-module</quarkusModule>
                </quarkusModules>
            </configuration>
        </execution>
        <execution>
            <id>verify-rest-drift</id>
            <goals><goal>verify</goal></goals>
            <phase>verify</phase>
            <configuration>
                <quarkusModules>
                    <quarkusModule>${project.basedir}/../my-module</quarkusModule>
                </quarkusModules>
            </configuration>
        </execution>
    </executions>
</plugin>
```

Point `<quarkusModules>` at each module directory containing `@Path` resources with core delegates. The plugin scans Jandex indexes from those modules' build output.

**Excluding resources:** Resources that use framework-specific types the generator cannot translate (JAX-RS SSE, `@RestForm FileUpload`, JSON-RPC dispatch) can be excluded by fully-qualified class name:

```xml
<configuration>
    <excludeClassNames>
        <excludeClassName>com.example.SseResource</excludeClassName>
    </excludeClassNames>
    <quarkusModules>...</quarkusModules>
</configuration>
```

Add the same `<excludeClassNames>` to both `generate` and `verify` executions.

**What gets generated:**

| JAX-RS | Spring MVC |
|--------|-----------|
| `@Path` resource class | `@RestController` + `@RequestMapping` |
| `@GET`/`@POST`/`@PUT`/`@DELETE`/`@PATCH` | `@GetMapping`/`@PostMapping`/etc. |
| `@PathParam` | `@PathVariable` |
| `@QueryParam` | `@RequestParam` |
| `@HeaderParam` | `@RequestHeader` |
| Body parameter | `@RequestBody` |
| `void` return | `ResponseEntity<Void>` (204 No Content) |
| `Optional<T>` return | `ResponseEntity<T>` (200 or 404) |
| `T` return | `ResponseEntity<T>` (200 OK) |
| `Flow.Publisher<T>` return | `SseEmitter` (virtual-thread subscriber) |
| `ExceptionMapper<T>` | `@ControllerAdvice` + `@ExceptionHandler` |
| `ContainerRequestFilter` | `Filter` with `@Order` |

Naming convention: `FooResource` → `FooController`. If the class name doesn't end in `Resource`, `Controller` is appended.

**Drift detection:** The `verify` goal compares generated output against the current Jandex state. If a resource is added, renamed, or a method signature changes, `mvn verify` fails with a diff — ensuring generated controllers stay in sync with their Quarkus sources.

**SSE endpoints:** Core methods returning `Flow.Publisher<T>` (JDK reactive streams) generate Spring `SseEmitter`-based controllers. The generated code subscribes on a virtual thread, forwards items via `emitter.send()`, and completes on `onComplete`/`onError`. On the Quarkus side, the resource wraps the same publisher with `Multi.createFrom().publisher()`.

### Jackson 2 bridge

Spring Boot 4 defaults to Jackson 3 (`tools.jackson`). casehub core modules use Jackson 2 (`com.fasterxml`). The starter includes `spring-boot-jackson2` to auto-configure a Jackson 2 `ObjectMapper` so both frameworks inject the same type. This bridge will be removed when Quarkus 4 GA aligns on Jackson 3 (~Nov 2026).

### Observability (Actuator)

When `spring-boot-starter-actuator` is on the classpath, the platform automatically contributes:

**Health indicators** (each activates only when the relevant SPI bean exists):
- Model registry — UP when models are registered
- Agent backends — UP when ≥1 backend discovered
- Delivery channels — UP when channels registered
- SCIM endpoint — readiness check, pings the SCIM server
- Certificate expiry — WARNING when certs near expiry, DOWN when expired

**Metrics** (via Micrometer):
- `casehub.platform.agent.invocations` — counter per backend
- `casehub.platform.agent.invocation.duration` — timer per backend
- `casehub.platform.agent.sessions.opened` — counter per backend
- `casehub.platform.acl.can_access` — counter per action
- `casehub.platform.acl.can_access.duration` — timer per action
- `casehub.platform.model.registry.size` — gauge
- `casehub.platform.delivery.channels.registered` — gauge
- `casehub.platform.agent.backends.discovered` — gauge

**Info contributor:** Platform version, discovered agent backends, model registry size.

No additional configuration needed — add `spring-boot-starter-actuator` and metrics/health appear automatically.

### DevTools restart excludes

`platform-spring` includes `META-INF/spring-devtools.properties` that excludes `io.casehub.platform.*` from DevTools restart class scanning. Platform library classes don't change during development — excluding them reduces restart latency.

### Complete `pom.xml` template

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0
         https://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>

    <parent>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-parent</artifactId>
        <version>4.1.1</version>
    </parent>

    <groupId>com.example</groupId>
    <artifactId>my-casehub-app</artifactId>
    <version>1.0-SNAPSHOT</version>

    <properties>
        <casehub.version>0.2-SNAPSHOT</casehub.version>
    </properties>

    <dependencies>
        <!-- CaseHub platform — core starter -->
        <dependency>
            <groupId>io.casehub</groupId>
            <artifactId>casehub-spring-boot-starter</artifactId>
            <version>${casehub.version}</version>
            <type>pom</type>
        </dependency>

        <!-- Add for LLM agent support -->
        <!-- <dependency>
            <groupId>io.casehub</groupId>
            <artifactId>casehub-spring-boot-starter-agent</artifactId>
            <version>${casehub.version}</version>
            <type>pom</type>
        </dependency> -->

        <!-- Spring Boot starters -->
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-web</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-data-jpa</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-actuator</artifactId>
        </dependency>

        <!-- Database -->
        <dependency>
            <groupId>org.postgresql</groupId>
            <artifactId>postgresql</artifactId>
            <scope>runtime</scope>
        </dependency>

        <!-- Test -->
        <dependency>
            <groupId>io.casehub</groupId>
            <artifactId>casehub-platform-spring-testing</artifactId>
            <version>${casehub.version}</version>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-test</artifactId>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>com.h2database</groupId>
            <artifactId>h2</artifactId>
            <scope>test</scope>
        </dependency>
    </dependencies>

    <repositories>
        <repository>
            <id>casehub</id>
            <url>https://maven.pkg.github.com/casehubio/*</url>
        </repository>
    </repositories>
</project>
```

### Starter selection guide

| Need | Starter | Adds |
|------|---------|------|
| Core platform SPIs, JPA persistence, mock fallbacks | `casehub-spring-boot-starter` | persistence, ACL, notifications, subscriptions, callbacks, identity, credentials, OIDC, SCIM, actuator health/metrics |
| AI agent invocation (Claude, OpenAI, Gemini, etc.) | `casehub-spring-boot-starter-agent` | all agent backends + gate rate limiter (pulls in core transitively) |
| External event streams (Kafka, AMQP, webhooks, polling) | `casehub-spring-boot-starter-streams` | Kafka, AMQP, Camel, Poll adapters (pulls in core transitively) |

For fine-grained control, skip starters and add individual modules from the Spring modules table.

### Troubleshooting

**Jackson 2/3 type conflicts:** Spring Boot 4 defaults to Jackson 3. If you see `ClassNotFoundException` for `com.fasterxml.jackson.*` types, ensure `spring-boot-jackson2` is on the classpath — the core starter includes it automatically. If you use a custom parent POM (not `spring-boot-starter-parent`), add it explicitly.

**`@EntityScan` required:** Hibernate doesn't scan JARs for entities by default. Add `@EntityScan("io.casehub.platform")` to your application class — without it, JPA modules fail with "unknown entity" errors.

**Flyway migration locations:** Each JPA module ships its own migrations under `classpath:db/<module>/migration`. Add all locations to `spring.flyway.locations`:
```properties
spring.flyway.locations=classpath:db/migration,classpath:db/platform/migration,classpath:db/acl/migration
```
Missing a location means missing tables — check the Flyway locations table in the Configuration section.

**H2 test mode:** Use `MODE=PostgreSQL` in the JDBC URL for integration tests: `jdbc:h2:mem:testdb;MODE=PostgreSQL`. This handles PostgreSQL-specific syntax (recursive CTEs, JSON operators). Disable Flyway (`spring.flyway.enabled=false`) and use `spring.jpa.hibernate.ddl-auto=create-drop` for H2.

**Health indicators not appearing:** Platform health indicators require `spring-boot-starter-actuator` on the classpath AND the relevant SPI bean to be present. If a health check doesn't appear at `/actuator/health`, verify the backing bean exists (e.g., `MutableModelRegistry` for model registry health).

**Bean ordering with agent-gate:** If you see `AgentProvider` calls not rate-limited, check that `agent-gate-core` is on the classpath. The `MetricsBeanPostProcessor` (order 1900) and `AgentGateBeanPostProcessor` (order 2000) compose — metrics wraps first, then gate wraps the result.

---

## What This Repo Does NOT Do

- **Domain logic.** No case definitions, work items, or business rules. Those live in consumer repos (ledger, work, engine, devtown, etc.).
- **Memory.** `CaseMemoryStore` SPI and all implementations (in-mem, JPA, SQLite, Mem0, Graphiti) live in casehub-neocortex. Platform no longer owns memory abstractions.
- **Preference writes without the editor module.** `PreferenceProvider` is permanently read-only. The `preferences-editor/` module provides the write path via `PreferenceStore`.
- **Security enforcement beyond tenancy.** `CurrentPrincipal` provides identity. `@RolesAllowed` and full RBAC are Quarkus concerns, not platform concerns.
- **Orchestration.** Event routing and subscription matching happen here. Case orchestration, planning, and execution live in casehub-engine.
- **Notification delivery implementations.** The dispatch pipeline routes to `NotificationDeliverer` and `DestinationResolver` SPIs -- concrete channel implementations (email, SMS, push) live in casehub-connectors.
- **Label storage.** `LabelRule` evaluation and `LabelAction` generation happen here. Persisting labels on domain entities is the consumer's responsibility.
