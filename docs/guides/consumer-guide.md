# casehub-platform -- Consumer Guide

> Zero-dependency SPIs and types shared across all casehub modules -- the foundation layer every app builds on.

**Repo:** [`casehubio/platform`](https://github.com/casehubio/platform)
**Tier:** Foundation (first in build order, zero casehubio dependencies)

---

## Purpose

casehub-platform defines the domain abstractions that every casehub module shares: identity, preferences, paths, memory, data sources, endpoints, notifications, subscriptions, expressions, access control, credentials, governance, labels, subject views, and agent infrastructure. These are pure Java SPIs with zero external dependencies in `platform-api/`. Quarkus-specific implementations live in companion modules that activate by classpath presence via CDI `@DefaultBean` displacement.

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
| `casehub-platform-streams-kafka` | Kafka event stream connector -- static `@Incoming`, CloudEvent builder |
| `casehub-platform-streams-amqp` | AMQP event stream connector -- single address per channel |
| `casehub-platform-streams-webhook` | Webhook event stream connector -- structured CloudEvents HTTP binding |
| `casehub-platform-streams-poll` | Polling event stream connector -- `@Scheduled`, per-endpoint failure isolation |
| `casehub-platform-streams-camel` | Apache Camel event stream connector (runtime-dynamic routes) |

### Agent infrastructure

Callers inject `AgentProvider` — the `RoutingAgentProvider` dispatches to `AgentBackend` implementations by the `model` key on `AgentSessionConfig`. Add one or more backend modules to the classpath; the router discovers them automatically.

| Artifact | What it provides |
|----------|------------------|
| `casehub-platform-agent-api` | `AgentProvider` + `AgentBackend` SPIs; `AgentRuntime` + `AgentProcess` (subprocess abstraction); `AgentEvent` sealed interface; `AgentMcpServer` (Stdio/Sse/Http); Mutiny only, no Quarkus |
| `casehub-platform-agent-runtime` | `SubprocessRuntime` -- local process execution for CLI agent providers |
| `casehub-platform-agent-router` | `RoutingAgentProvider` -- dispatches to `AgentBackend` implementations by `model` key. Config: `casehub.platform.agent.default-backend` |
| `casehub-platform-agent-claude` | AgentBackend "claude" -- Claude CLI subprocess via `claude-code-sdk` |
| `casehub-platform-agent-openai` | AgentBackend "openai" -- native OpenAI Java SDK with `prompt_cache_key` support |
| `casehub-platform-agent-codex` | AgentBackend "codex" -- Codex CLI via `AgentRuntime` |
| `casehub-platform-agent-gemini` | AgentBackend "gemini" -- native Google GenAI SDK with explicit caching |
| `casehub-platform-agent-gemini-cli` | AgentBackend "gemini-cli" -- Gemini CLI via `AgentRuntime` |
| `casehub-platform-agent-langchain4j` | AgentBackend "langchain4j" -- catch-all fallback; bidirectional LangChain4j interop |
| `casehub-platform-agent-gate` | CDI `@Decorator` rate limiter -- wraps `RoutingAgentProvider` transparently |

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

**Two-SPI design:** `AgentProvider` is the caller-facing SPI. `AgentBackend` is the implementor-facing SPI. `RoutingAgentProvider` bridges them — it implements `AgentProvider`, discovers `AgentBackend` beans via CDI `Instance`, and dispatches by the `model` field on config records. Callers always inject `AgentProvider`, never `AgentBackend`.

`AgentProvider` has two execution paths:
- `invoke(AgentSessionConfig)` -- single-shot, returns cold `Multi<AgentEvent>`. The `AgentSessionConfig` carries `systemPrompt`, `userPrompt`, `mcpServers`, `timeout`, `correlationId`, and nullable `model` (provider key).
- `openSession(AgentSessionInit)` -- multi-turn `AgentSession` (IDLE/ACTIVE/CLOSED state machine). `AgentSessionInit` carries `systemPrompt`, `mcpServers`, `timeout`, `correlationId`, and nullable `model`.

`AgentBackend` has the same two methods plus `key()` — a string identifying the provider ("claude", "openai", "codex", "gemini", "gemini-cli", "langchain4j"). When `model` is null, the configurable default backend is used. When `model` matches no native key, the "langchain4j" backend acts as a catch-all fallback.

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
- `@McpDomain` interfaces discovered directly with `@PlatformQuery`/`@PlatformMutation` annotations.

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

## What This Repo Does NOT Do

- **Domain logic.** No case definitions, work items, or business rules. Those live in consumer repos (ledger, work, engine, devtown, etc.).
- **Memory.** `CaseMemoryStore` SPI and all implementations (in-mem, JPA, SQLite, Mem0, Graphiti) live in casehub-neocortex. Platform no longer owns memory abstractions.
- **Preference writes without the editor module.** `PreferenceProvider` is permanently read-only. The `preferences-editor/` module provides the write path via `PreferenceStore`.
- **Security enforcement beyond tenancy.** `CurrentPrincipal` provides identity. `@RolesAllowed` and full RBAC are Quarkus concerns, not platform concerns.
- **Orchestration.** Event routing and subscription matching happen here. Case orchestration, planning, and execution live in casehub-engine.
- **Notification delivery implementations.** The dispatch pipeline routes to `NotificationDeliverer` and `DestinationResolver` SPIs -- concrete channel implementations (email, SMS, push) live in casehub-connectors.
- **Label storage.** `LabelRule` evaluation and `LabelAction` generation happen here. Persisting labels on domain entities is the consumer's responsibility.
