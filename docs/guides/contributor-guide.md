# casehub-platform -- Contributor Guide

> Internal architecture, module structure, and extension points for platform developers.

**Repo:** [`casehubio/platform`](https://github.com/casehubio/platform)

---

## Module Structure

### The Three-Layer Model

```
platform-api/               <- Tier 1: zero dependencies -- pure Java interfaces and records
platform/                   <- Tier 3: Quarkus @DefaultBean mocks, @ConfigProperty
testing/                    <- companion: @Alternative @Priority(200) test fixtures (CDI API only)
```

`platform-api/` must never import Quarkus, CDI, JPA, or any casehubio artifact. This constraint is what makes the SPIs useful to every module in the stack.

**Exception:** `platform-api/` does import the `@CloudEventType` CDI qualifier annotation and `io.cloudevents:cloudevents-api` -- these are pure annotations with no runtime dependency on CDI containers.

### Full Module Listing

| Module | Artifact | CDI | Purpose |
|--------|----------|-----|---------|
| `platform-api/` | `casehub-platform-api` | (none) | Pure Java SPIs -- zero deps |
| `agent-api/` | `casehub-platform-agent-api` | (none) | `AgentProvider` + `AgentBackend` SPIs, `AgentRuntime` + `AgentProcess`, `AgentEvent` sealed interface, `AgentMcpServer` -- Mutiny only, no Quarkus |
| `platform/` | `casehub-platform` | `@DefaultBean` | Quarkus mocks + no-ops; `DataSourceRouter`; `CloudEventTypeDispatcher` |
| `testing/` | `casehub-platform-testing` | `@Alternative @Priority(200)` | `FixedCurrentPrincipal`, `InMemoryGroupMembershipProvider` |
| `config/` | `casehub-platform-config` | `@ApplicationScoped` | Scope-aware YAML + SmallRye Config |
| `oidc/` | `casehub-platform-oidc` | `@Alternative @Priority(100) @RequestScoped` | OIDC-backed `CurrentPrincipal` |
| `expression/` | `casehub-platform-expression` | `@DefaultBean` / `@ApplicationScoped` | Quarkus CDI wiring -- `MockConfigManager`, `MockSecretManager` (delegate to core via `SmallRyePropertySource`), `JQEvaluator` (delegates to `JQEvaluatorCore`), `ExpressionBeans` (@Produces engines). Optional `quarkus-kubernetes-config` |
| `persistence-jpa/` | `casehub-platform-persistence-jpa` | `@ApplicationScoped` | JPA `PreferenceProvider` + `PreferenceStore` -- Flyway, scope-aware hierarchy |
| `persistence-mongodb/` | `casehub-platform-persistence-mongodb` | `@Alternative @Priority(1)` | MongoDB `PreferenceProvider` + `PreferenceStore` -- beats JPA when co-deployed |
| `datasource-alpha/` | `casehub-platform-datasource-alpha` | (library) | Rete alpha network -- `AlphaDataSource`, TypeNode, FilterNode, FanOutProcessor |
| `datasource-inmem/` | `casehub-platform-datasource-inmem` | `@Alternative @Priority(100)` | In-memory `DataSourceRegistry` -- ConcurrentHashMap, self-pruning |
| `datasource-jpa/` | `casehub-platform-datasource-jpa` | `@ApplicationScoped` | JPA `DataSourceRegistry` -- startup reconciliation, `@Transactional` |
| `identity/` | `casehub-platform-identity` | `@ApplicationScoped` | DID resolution (did:key, did:web, SCIM), actor-to-DID mapping, VC validation |
| `acl-inmem/` | `casehub-platform-acl-inmem` | `@Alternative @Priority(10)` | In-memory ACL -- ConcurrentHashMap, group-based grants, parent-child hierarchy, deny entries, wildcard grants |
| `acl-jpa/` | `casehub-platform-acl-jpa` | `@ApplicationScoped` | JPA ACL -- Hibernate ORM, audit logging, deny entries, recursive CTE hierarchy, tenant-filtered queries, retention purge |
| `acl-admin/` | `casehub-platform-acl-admin` | `@ApplicationScoped` | ACL admin -- `AclService implements AclApi`, generated REST via `@McpDomain("acl")`, `@RolesAllowed("admin")` |
| `governance/` | `casehub-platform-governance` | `@ApplicationScoped` | `DefaultPolicyEnforcer` -- retry/timeout/backoff on virtual thread executor |
| `credentials-quarkus/` | `casehub-platform-credentials-quarkus` | `@Alternative @Priority(1)` | Bridge `CredentialResolver` to Quarkus `CredentialsProvider` |
| `scim/` | `casehub-platform-scim` | `@ApplicationScoped` | SCIM 2.0 `GroupMembershipProvider` |
| `agent-runtime/` | `casehub-platform-agent-runtime` | `@ApplicationScoped` | `SubprocessRuntime` -- local process execution for CLI agent providers |
| `agent-claude/` | `casehub-platform-agent-claude` | `@ApplicationScoped` | AgentBackend "claude" -- Claude CLI subprocess via `claude-code-sdk` |
| `agent-openai/` | `casehub-platform-agent-openai` | `@ApplicationScoped` | AgentBackend "openai" -- native OpenAI Java SDK (v4.50.0), `prompt_cache_key` support |
| `agent-codex/` | `casehub-platform-agent-codex` | `@ApplicationScoped` | AgentBackend "codex" -- Codex CLI via `AgentRuntime` |
| `agent-gemini/` | `casehub-platform-agent-gemini` | `@ApplicationScoped` | AgentBackend "gemini" -- native Google GenAI SDK (v1.65.0), explicit caching |
| `agent-gemini-cli/` | `casehub-platform-agent-gemini-cli` | `@ApplicationScoped` | AgentBackend "gemini-cli" -- Gemini CLI via `AgentRuntime` |
| `agent-langchain4j/` | `casehub-platform-agent-langchain4j` | `@ApplicationScoped` | AgentBackend "langchain4j" -- bidirectional LangChain4j interop |
| `agent-config-core/` | `casehub-platform-agent-config-core` | (none) | Manifest types, `ManifestLoader`, `ManifestProcessor`, `ManifestCredentialResolver`, `CredentialRef` sealed interface, `ManifestResult`, `LocalModelReconciler`. Pure Java + Jackson |
| `agent-config/` | `casehub-platform-agent-config` | `@Startup @Priority(50)` | `AgentConfigBeans` -- discovers `agent-config.yaml`, runs loader+processor, produces `ManifestResult @Singleton` |
| `agent-router/` | `casehub-platform-agent-router` | `@ApplicationScoped` | `RoutingAgentProvider` -- four-step model resolution (alias → tier → registry → key → fail-fast) with preferVendor tiebreaking; consumes `ManifestResult`; `NoOpModelRegistry @DefaultBean` fallback |
| `agent-gate/` | `casehub-platform-agent-gate` | `@Decorator @Priority(APPLICATION)` | Token bucket + concurrency gate rate limiter -- wraps RoutingAgentProvider |
| `endpoints-memory/` | `casehub-platform-endpoints-memory` | `@Alternative @Priority(100)` | In-memory `EndpointRegistry` -- volatile, Tier 4 CDI |
| `registry-inmem/` | `casehub-platform-registry-inmem` | `@Alternative @Priority(50)` | In-memory `RegistryService` -- volatile, heartbeat/TTL, relationships, watch |
| `registry-jpa/` | `casehub-platform-registry-jpa` | `@Alternative @Priority(100)` | JPA `RegistryService` -- dual-core POJO, Flyway, heartbeat scheduler |
| `endpoints-config/` | `casehub-platform-endpoints-config` | `@Startup @ApplicationScoped` | YAML endpoint populator -- `${VAR}` interpolation, multi-file |
| `notifications/` | `casehub-platform-notifications` | `@ApplicationScoped` | REST + SSE -- list, mark-read, dismiss, unread-count, preferences, suppression |
| `notifications-inmem/` | `casehub-platform-notifications-inmem` | `@Alternative @Priority(100)` | In-memory `NotificationStore` -- bounded eviction, cursor pagination |
| `notifications-jpa/` | `casehub-platform-notifications-jpa` | `@ApplicationScoped` | JPA `NotificationStore` -- Hibernate ORM, keyset pagination, retention scheduler |
| `notification-dispatch/` | `casehub-platform-notification-dispatch` | `@ApplicationScoped` | Three-path delivery: digest/suppress/immediate; `DigestFlushScheduler`; `DeliveryRetryProcessor`; `DestinationScope` per-tenant dedup |
| `notification-settings-inmem/` | `casehub-platform-notification-settings-inmem` | `@Alternative @Priority(100)` | In-memory preference/suppression store |
| `notification-settings-jpa/` | `casehub-platform-notification-settings-jpa` | `@ApplicationScoped` | JPA preference/suppression store -- JSON TEXT columns, retention scheduler |
| `delivery-channel-inmem/` | `casehub-platform-delivery-channel-inmem` | `@ApplicationScoped` | Channel-to-deliverer registry -- **production implementation** (channels are static) |
| `delivery-tracking-inmem/` | `casehub-platform-delivery-tracking-inmem` | `@Alternative @Priority(100)` | In-memory `DeliveryAttemptStore` |
| `delivery-tracking-jpa/` | `casehub-platform-delivery-tracking-jpa` | `@ApplicationScoped` | JPA `DeliveryAttemptStore` -- `SKIP LOCKED` claims, retention purge, tenant-scoped queries |
| `digest-inmem/` | `casehub-platform-digest-inmem` | `@Alternative @Priority(100)` | In-memory `DigestBuffer` |
| `digest-jpa/` | `casehub-platform-digest-jpa` | `@ApplicationScoped` | JPA `DigestBuffer` -- drain via SELECT+DELETE in transaction |
| `subscriptions/` | `casehub-platform-subscriptions` | `@ApplicationScoped` | Subscription matching engine + REST -- alpha network wiring, expression compilation |
| `subscriptions-inmem/` | `casehub-platform-subscriptions-inmem` | `@Alternative @Priority(100)` | In-memory `SubscriptionStore` -- scope-aware, CDI events |
| `subscriptions-jpa/` | `casehub-platform-subscriptions-jpa` | `@ApplicationScoped` | JPA `SubscriptionStore` -- OR-disjunction scope queries, JSON TEXT columns |
| `streams-kafka/` | `casehub-platform-streams-kafka` | `@Startup` | Kafka connector -- static `@Incoming`, CloudEvent builder |
| `streams-amqp/` | `casehub-platform-streams-amqp` | `@Startup` | AMQP connector -- single address per channel |
| `streams-webhook/` | `casehub-platform-streams-webhook` | `@Startup` | Webhook receiver -- structured CloudEvents HTTP binding, authenticated |
| `streams-poll/` | `casehub-platform-streams-poll` | `@Startup` | HTTP GET poller -- `@Scheduled`, per-endpoint failure isolation |
| `streams-camel/` | `casehub-platform-streams-camel` | `@ApplicationScoped` | Camel dynamic routes -- the only connector with runtime route addition |
| `preferences-editor/` | `casehub-platform-preferences-editor` | `@ApplicationScoped` | REST API for preference writes + schema discovery + validation; `InMemoryPreferenceSchemaRegistry`; `PreferenceValidator` |
| `platform-view/` | `casehub-platform-view` | `@ApplicationScoped` | `SubjectViewEvaluator` + `SubjectViewOrchestrator` -- label-path view evaluation with caching |
| `platform-view-inmem/` | `casehub-platform-view-inmem` | `@Alternative @Priority(100)` | In-memory view store + membership tracker + `InMemorySubjectViewQuerySupport` abstract helper |
| `platform-view-jpa/` | `casehub-platform-view-jpa` | `@ApplicationScoped` | JPA view store -- `JpaLabelPatternQuerySupport` for domain consumers, `LabelPatternPredicates` for SQL LIKE |
| `yaml-core/` | `casehub-platform-yaml-core` | (none) | Pure Java YAML primitives -- `VariableResolver`, `ForEachExpander`, `Truthiness`, `CsvParser`, `ModuleBridge<T>`, `TypedExpandedModule<T>`. Zero deps |
| `yaml-jackson/` | `casehub-platform-yaml-jackson` | (none) | Jackson mixins for yaml-core types -- `YamlCoreJacksonModule`, dynamic section capture, case-insensitive enums. Depends on yaml-core + jackson-databind |
| `ts-core/` | `casehub-platform-ts-core` | (none) | TypeScript execution SPI -- `TsExecutor` interface, `NodeTsExecutor` (Node.js subprocess). Zero deps |
| `platform-core/` | `casehub-platform-core` | (none) | Framework-neutral POJOs with constructor injection -- no CDI, no Spring imports |
| `platform-spring/` | `casehub-platform-spring` | Spring `@AutoConfiguration` | Spring Boot auto-configuration -- `@Bean @ConditionalOnMissingBean` equivalents of Quarkus `@DefaultBean` |
| `spring-testing/` | `casehub-platform-spring-testing` | (none) | Spring test support -- test fixtures for Spring-based consumers |
| `platform-view-core/` | `casehub-platform-view-core` | (none) | Framework-neutral view evaluation POJOs |
| `platform-view-spring/` | `casehub-platform-view-spring` | Spring `@AutoConfiguration` | Spring auto-config for subject views |
| `expression-core/` | `casehub-platform-expression-core` | (none) | Framework-neutral expression engine POJOs -- `PropertySource` (config abstraction), `ConfigManagerCore`, `SecretManagerCore`, `JQEvaluatorCore`, `PropertyMapBuilder`, expression engines (MVEL, JQ, JEXL) |
| `expression-spring/` | `casehub-platform-expression-spring` | Spring `@AutoConfiguration` | Spring auto-config for expression engines, `ConfigManager`, `SecretManager`, `JQEvaluatorCore` -- `EnvironmentPropertySource` wraps Spring `Environment`. Included in `spring-boot-starter` |
| `governance-core/` | `casehub-platform-governance-core` | (none) | Framework-neutral policy enforcer POJOs |
| `identity-core/` | `casehub-platform-identity-core` | (none) | Framework-neutral identity resolution POJOs |
| `mcp/` | `casehub-platform-mcp` | `@ApplicationScoped` | MCP hierarchical model -- `GraphQLModelScanner` (auto-discovers domains from `@GraphQLApi`, class-based `@McpDomain`, or interface `@McpDomain`; also discovers `DomainReportProvider` beans), `DynamicToolRegistrar` (McpCapabilityException interception → McpOperationResult success response; auto-registers `<domain>_report` tools from DomainReportProvider), `LandscapeReportService @McpDomain("landscape")` (parallel fan-out aggregator), `McpResourceRegistryBridge`, `DomainResourceRegistrar` |
| `mcp-core/` | `casehub-platform-mcp-core` | (none) | Framework-neutral MCP POJOs + `PlatformLandscape` (aggregated domain report) |
| `graphql/` | `casehub-platform-graphql` | (none) | GraphQL foundation -- `@CustomScalar("JSON")`, `PageInput`/`PageInfo`/`PageResult<T>` pagination, `GraphQLError` (RFC 7807) |
| `graphql-client/` | `casehub-graphql-client` | (none) | Typed CaseHub GraphQL client -- `@GraphQLClientApi` per domain (`CaseClient`, `WorkItemClient`, `LedgerClient`, `QhorusClient`) |
| `generator-common/` | `casehub-platform-generator-common` | (none) | Shared generator infrastructure -- `McpDomainJandexScanner` (scans `@McpDomain` on interfaces and classes), `DomainScanResult`, `AbstractGeneratorMojo`, `AbstractVerifyMojo`, `JandexTypeConverter` |
| `graphql-generator/` | `casehub-platform-graphql-generator` | (APT) | Annotation processor -- scans `@McpDomain` + `@PlatformQuery`/`@PlatformMutation` on interfaces or classes, generates `@GraphQLApi` resolvers + `@Path` JAX-RS REST resources. APT scope warning for classes without CDI scope |
| `graphql-spring-generator/` | `casehub-platform-graphql-spring-generator` | (Maven plugin) | Spring generator -- scans `@McpDomain` via Jandex, generates Spring `@Controller` + `@RestController` per domain |
| `rest-spring-generator/` | `casehub-platform-rest-spring-generator` | (Maven plugin) | Spring REST generator -- scans `@Path` resources via Jandex, generates Spring MVC `@RestController` classes |
| `mcp-spring-generator/` | `casehub-platform-mcp-spring-generator` | (Maven plugin) | Spring MCP generator -- scans Quarkus `@Tool` methods, generates Spring AI `@Tool` equivalents |
| `callback-generator/` | `casehub-platform-callback-generator` | (APT) | Annotation processor -- scans `@CallbackEligible` interfaces, generates `@Decorator` classes routing to `CallbackInvoker` |
| `callback-api/` | `casehub-platform-callback-api` | (none) | Callback registration SPI -- `CallbackRegistry`, `CallbackRegistration`, CDI events. Pure Java |
| `callback/` | `casehub-platform-callback` | `@ApplicationScoped` | `CallbackInvoker` -- HTTP POST with retry via `PolicyEnforcer`, `LeaseReaper @Scheduled`, REST/MCP via `@McpDomain("callbacks")` |
| `callback-inmem/` | `casehub-platform-callback-inmem` | `@Alternative @Priority(100)` | In-memory `CallbackRegistry` -- ConcurrentHashMap, upsert, lease expiry |
| `callback-client/` | `casehub-platform-callback-client` | `@Startup` | Client-side callback auto-registration -- discovers local `@CallbackEligible` SPIs, registers with server, heartbeat renewal |
| `callback-client-core/` | `casehub-platform-callback-client-core` | (none) | Framework-neutral `CallbackDispatcher` -- routes invocations to local SPI beans |
| `callback-core/` | `casehub-platform-callback-core` | (none) | Framework-neutral callback invoker POJOs |
| `callback-spring/` | `casehub-platform-callback-spring` | Spring `@AutoConfiguration` | Spring callback infrastructure -- `CallbackDecoratorBeanPostProcessor` wraps `@CallbackEligible` beans |
| `agent-ollama/` | `casehub-platform-agent-ollama` | `@ApplicationScoped` | AgentBackend "ollama" -- local model invocation via Ollama's OpenAI-compatible API |
| `agent-runtime-core/` | `casehub-platform-agent-runtime-core` | (none) | Framework-neutral agent runtime POJOs |
| `agent-claude-core/` | `casehub-platform-agent-claude-core` | (none) | Framework-neutral Claude agent POJOs |
| `agent-openai-core/` | `casehub-platform-agent-openai-core` | (none) | Framework-neutral OpenAI agent POJOs |
| `agent-codex-core/` | `casehub-platform-agent-codex-core` | (none) | Framework-neutral Codex agent POJOs |
| `agent-gemini-core/` | `casehub-platform-agent-gemini-core` | (none) | Framework-neutral Gemini agent POJOs |
| `agent-gemini-cli-core/` | `casehub-platform-agent-gemini-cli-core` | (none) | Framework-neutral Gemini CLI agent POJOs |
| `agent-router-core/` | `casehub-platform-agent-router-core` | (none) | Framework-neutral agent router POJOs |
| `agent-gate-core/` | `casehub-platform-agent-gate-core` | (none) | Framework-neutral agent gate POJOs |
| `agent-langchain4j-core/` | `casehub-platform-agent-langchain4j-core` | (none) | Framework-neutral LangChain4j agent POJOs |
| `acl-worker/` | `casehub-platform-acl-worker` | `@Provider` | Worker credential filter -- token lookup, tenancy validation, `FailClosedWorkerScopeExtractor @DefaultBean` |
| `schema-generator/` | `casehub-platform-schema-generator` | (none) | JSON Schema generation -- victools/jsonschema-generator (Draft 2020-12), `SealedHierarchyModule`, `ShorthandModule` |
| `drift-detection/` | `casehub-platform-drift-detection` | (Maven Enforcer) | Drift detection custom rule -- detects hand-written types in codegen-managed packages |
| `platform-pdf/` | `casehub-platform-pdf` | `@ApplicationScoped` | HTML-to-PDF with PDF/A-2b conformance -- OpenHTMLtoPDF + PDFBox 3.0.3. Displaces `NoOpPdfGenerator @DefaultBean` |
| `platform-signing/` | `casehub-platform-signing` | `@ApplicationScoped` | EU DSS 6.2 document signing -- PAdES PDF + CAdES detached. Displaces `NoOpDocumentSigningService @DefaultBean` |
| `yaml-codegen/` | `casehub-platform-yaml-codegen` | (Maven plugin) | YAML codegen -- generates Java records/POJOs from JSON Schema via jsonschema2pojo-core |
| `llm-config/` | `casehub-platform-llm-config` | `@ApplicationScoped` | LLM config wizard -- vendor discovery, credential validation, model listing |
| `llm-config-core/` | `casehub-platform-llm-config-core` | (none) | Framework-neutral LLM config types -- `LlmConfigApi` SPI, request/result records |
| `notifications-core/` | `casehub-platform-notifications-core` | (none) | Framework-neutral notification POJOs |
| `notifications-inmem-core/` | `casehub-platform-notifications-inmem-core` | (none) | Framework-neutral in-memory notification store POJOs |
| `notification-settings-inmem-core/` | `casehub-platform-notification-settings-inmem-core` | (none) | Framework-neutral notification settings POJOs |
| `notification-dispatch-core/` | `casehub-platform-notification-dispatch-core` | (none) | Framework-neutral notification dispatch POJOs |
| `delivery-tracking-inmem-core/` | `casehub-platform-delivery-tracking-inmem-core` | (none) | Framework-neutral delivery tracking POJOs |
| `digest-inmem-core/` | `casehub-platform-digest-inmem-core` | (none) | Framework-neutral digest buffer POJOs |
| `delivery-channel-inmem-core/` | `casehub-platform-delivery-channel-inmem-core` | (none) | Framework-neutral delivery channel POJOs |
| `datasource-inmem-core/` | `casehub-platform-datasource-inmem-core` | (none) | Framework-neutral datasource registry POJOs |
| `endpoints-memory-core/` | `casehub-platform-endpoints-memory-core` | (none) | Framework-neutral endpoint registry POJOs |
| `registry-inmem-core/` | `casehub-platform-registry-inmem-core` | (none) | Framework-neutral InMemoryRegistryService + HeartbeatScheduler POJOs |
| `registry-jpa-common/` | `casehub-platform-registry-jpa-common` | (none) | JPA entities, Flyway migrations, framework-neutral JpaRegistryService POJO |
| `endpoints-config-core/` | `casehub-platform-endpoints-config-core` | (none) | Framework-neutral endpoint config POJOs |
| `acl-inmem-core/` | `casehub-platform-acl-inmem-core` | (none) | Framework-neutral in-memory ACL POJOs |
| `callback-inmem-core/` | `casehub-platform-callback-inmem-core` | (none) | Framework-neutral callback in-memory POJOs |
| `config-core/` | `casehub-platform-config-core` | (none) | Framework-neutral config POJOs |
| `preferences-editor-core/` | `casehub-platform-preferences-editor-core` | (none) | Framework-neutral preferences editor POJOs |
| `subscriptions-inmem-core/` | `casehub-platform-subscriptions-inmem-core` | (none) | Framework-neutral subscriptions POJOs |
| `subscriptions-core/` | `casehub-platform-subscriptions-core` | (none) | Framework-neutral subscriptions POJOs |
| `streams-webhook-core/` | `casehub-platform-streams-webhook-core` | (none) | Framework-neutral webhook receiver POJOs |

**Removed from build:** `memory-inmem/`, `memory-jpa/`, `memory-sqlite/`, `memory-mem0/`, `memory-graphiti/` -- memory backends migrated to casehub-neocortex (neocortex#56). Directories remain on disk.

---

## Internal Architecture

### @DefaultBean Displacement Pattern

Every SPI in `platform-api/` gets a `@DefaultBean` implementation in `platform/`. When a real implementation (e.g. `casehub-platform-oidc`) is on the classpath, CDI displaces the mock automatically. No exclusion config needed.

Two patterns exist:

| Pattern | Used by | Behaviour |
|---------|---------|-----------|
| **Configurable mock** | `PreferenceProvider`, `CurrentPrincipal`, `GroupMembershipProvider` | Returns `@ConfigProperty` values -- tests set specific returns |
| **Silent no-op** | `CaseMemoryStore`, `AgentProvider`, `AccessControlProvider`, `ExpressionEngineRegistry`, `PreferenceStore`, `PreferenceSchemaRegistry`, `CredentialResolver`, `DataSourceRegistry`, `EndpointRegistry`, `RegistryService`, `MarshallerRegistry`, `NotificationStore`, `SubscriptionStore`, `SuppressionStore`, `NotificationPreferenceStore`, `DeliveryAttemptStore`, `DigestBuffer`, `DeliveryChannelRegistry`, `SubjectViewStore`, `ViewMembershipTracker`, `CrossTenantSubjectViewStore`, `DIDResolver`, `ActorDIDProvider`, `EventTypeRegistry`, `EntityWatcherProvider`, `StrategyResolver`, `DisplayTermResolver`, `PdfGenerator`, `DocumentSigningService`, `DocumentVerificationService`, `McpResourceRegistry`, `ModelRegistry` (`NoOpModelRegistry`), `CallbackRegistry`, `LlmCredentialStore`, `SessionIsolator`, `WorkerCredentialStore`, `AgentCredentialValidator`, `WorkerAuthorizationPolicy` (`AutoApproveWorkerAuthorizationPolicy`) | Returns empty/void -- system works without the capability |

### CDI Priority Ladder

All in-memory/JPA module pairs follow the same convention:

| CDI annotation | Meaning |
|----------------|---------|
| `@DefaultBean` | Yields to anything -- mock/no-op |
| `@Alternative @Priority(1)` | Low-priority real implementation (e.g. MongoDB preference backend, LangChain4j agent) |
| `@Alternative @Priority(10)` | Standard real implementation (e.g. InMemory ACL, Claude agent) |
| `@Alternative @Priority(100)` | In-memory / test doubles (e.g. InMemory notification store) |
| `@Alternative @Priority(200)` | Test fixtures (e.g. FixedCurrentPrincipal) |
| `@ApplicationScoped` (no Alternative) | Production JPA implementations |

### Alpha Network (Rete Pattern)

`AlphaDataSource<T>` implements the Rete algorithm's alpha network:

```
add(object)
  |---> directSubscribers (FanOutProcessor) -- all objects, no filter
  +---> typeNodes (ConcurrentHashMap<Object, TypeNode>)
          +---> TypeNode: checks objectType.matches()
                |---> noFilterSubscribers (FanOutProcessor) -- type match only
                +---> filterNodes (List<FilterNode>)
                        +---> FilterNode: checks predicate.test()
                              +---> fanOut (FanOutProcessor) -- type + filter match
```

Node sharing by `getTypeKey()` (type nodes) and `FilterExpression` matching (filter nodes). Self-pruning: empty TypeNodes removed when last subscriber unsubscribes. Error isolation: exceptions are WARN-logged, never propagate to other subscribers.

### Self-Pruning Deregistration Lifecycle

1. `registry.deregister()` calls `source.markForRemoval(cleanupCallback)`
2. If `shareCount == 0`, cleanup fires immediately
3. Otherwise the DataSource enters "pending removal" -- continues accepting `add()` and subscriptions
4. `DataSourceDeregistered` fires via `fireAsync()` -- observers call `handle.unsubscribe()`
5. Each `unsubscribe()` decrements `shareCount` -- last subscriber triggers cleanup
6. Cleanup uses `sources.remove(key, source)` (identity-based) -- prevents corruption if a replacement was registered during drain
7. Re-registration during drain creates a new `AlphaDataSource`

### CloudEvent Routing

**DataSourceRouter** (in `platform/`): CDI observer for `@ObservesAsync CloudEvent`. Startup behaviour: queues events received before `@Observes StartupEvent`, then replays. Runtime: extracts `tenancyid` extension, matches against wired DataSources (tenant-specific or platform-global), checks `acceptedEventTypes` pre-filter, calls `DataSource.add()`.

Convergent design: `onDataSourceRegistered()` resolves current state from registry and compares against wired set using identity comparison. Stale entries replaced, already-wired entries skipped. `onDataSourceDeregistered()` uses identity comparison to prevent corruption if a replacement was registered.

**CloudEventTypeDispatcher** (in `platform/`): Routes unqualified `@ObservesAsync CloudEvent` to type-specific observers via `@CloudEventType("type.string")` qualifier. Extracts `event.getType()` and re-fires with the qualifier literal. Observer failures logged but never propagate.

### DID Resolution -- Composite Pattern

`CompositeDIDResolver` iterates all `@DIDMethod`-qualified resolvers by `@Priority`, returning the first non-empty result.

| Resolver | `@Priority` | Method | Notes |
|----------|-------------|--------|-------|
| `KeyDIDResolver` | 100 | `did:key:` | Ed25519 + P-256 + secp256k1 (manual ASN.1 SPKI -- JDK 15+ removed secp256k1 JCA provider) |
| `WebDIDResolver` | 100 | `did:web:` | HTTPS GET with SSRF protection (rejects RFC 1918, loopback, link-local) |
| `ScimDIDResolver` | 1000 | (any) | Synthetic DID documents from SCIM2 x509Certificates |

Same composite pattern for `ActorDIDProvider` -- `ConfiguredActorDIDProvider` (@Priority 100) + `ScimActorDIDProvider` (@Priority 200).

### Notification Data Flow

1. Domain modules produce `SubscribableEvent` into the notification DataSource (`casehub/platform/notifications`)
2. `SubscriptionEngine` evaluates against alpha network, fires `SubscriptionMatched`
3. `NotificationDispatcher` resolves targets, applies template, checks suppression, routes to channels
4. `DestinationResolver` resolves per-user or per-tenant delivery destinations
5. `NotificationStore` persists, fires `NotificationCreated`
6. REST + SSE endpoints expose to clients
7. `DeliveryChannelRegistry` maps channels to `NotificationDeliverer` implementations
8. Delivery tracked by `DeliveryAttemptStore`; digests buffered by `DigestBuffer`
9. Engagement events (`EngagementType`: OPENED/CLICKED/DISMISSED/REPLIED/CONVERTED) recorded via `EngagementCallbackHandler`

**Quiet hours integration:** `QuietHoursAction.BUFFER_FOR_DIGEST` buffers suppressed notifications instead of dropping them. They are flushed to digest on the next scheduled flush.

**Modular target kinds:** `TargetResolver.resolve()` returns `Set<ResolvedTarget>` (record: targetId + `TargetKind`). USER targets (USER, GROUP, EVENT_FIELD, ENTITY_WATCHERS) get the full pipeline. NON_USER targets (AGENT, SYSTEM) skip suppression/preferences and route via `ChannelRouter.routeNonUser()` to `CdiEventDeliverer` (fire-and-forget CDI async event). To add a new subscribable event: implement `SubscribableEvent` on the event record (provide `type()` and `tenancyId()`). The data source pipeline matches it automatically against active subscriptions. `SubscriptionEngine.publish(Object)` provides programmatic event injection.

### Expression Engines

| Engine | Type Key | Backend | Context Type | Compilation | Notes |
|--------|----------|---------|-------------|-------------|-------|
| `JQExpressionEngine` | `"jq"` | jackson-jq 1.6 | `JsonNode` or `Map` (auto-adapted via `MapAdaptedJQExpression`) | Eager | Boolean/List/Scalar result dispatch at compile time. `$config` and `$secret` scope injection via `JQEvaluator`. |
| `MvelExpressionEngine` | `"mvel"` | MVEL3 3.0.0-SNAPSHOT | `Map` (direct) or POJO (auto-adapted via BeanInfo introspection) | Lazy (double-checked locking on first eval) | Block expressions detected by `;` presence. Transpiler -- no meaningful syntactic-only validation. |
| `JexlExpressionEngine` | `"jexl"` | Commons JEXL 3.4.0 | `Map<String, Object>` via MapContext | Eager | Strict=false, silent=false. Bound variables merged at eval time. |

`DefaultExpressionEngineRegistry` discovers all `ExpressionEngine` CDI beans at `@PostConstruct` and populates a `ConcurrentHashMap<String, ExpressionEngine>`. No SPI file needed -- CDI auto-discovery.

`LambdaExpression<C, R>` wraps `Function<C, R>` -- implements both `ExpressionEvaluator` and `CompiledExpression`. Type key `"lambda"`. Intentionally outside the registry flow -- no engine exists for it.

**ConfigManager** (`ConfigManagerCore` in expression-core): Framework-neutral implementation using `PropertySource` abstraction. `configMap(name)` sweeps properties with `{name}.` prefix, builds nested maps via `PropertyMapBuilder`. Quarkus: `MockConfigManager @DefaultBean` wraps with `SmallRyePropertySource` + optional `quarkus-kubernetes-config`. Spring: `ExpressionSpringAutoConfiguration` wraps with `EnvironmentPropertySource` + optional `spring-cloud-kubernetes-fabric8-config`.

**SecretManager** (`SecretManagerCore` in expression-core): Framework-neutral implementation using `PropertySource`. Reads secrets from properties with prefix `casehub.platform.secrets.{secretName}.{property}`. Same dual-framework pattern as ConfigManager.

### Agent Configuration Manifest

The manifest system drives `AgentProvider` configuration declaratively. The pipeline:

```
agent-config.yaml files → ManifestLoader → ManifestProcessor → existing SPIs
                                                                  ├─ LlmCredentialStore
                                                                  ├─ MutableModelRegistry
                                                                  ├─ RoutingAgentProvider (aliases + defaults)
                                                                  └─ LocalModelReconciler (Ollama pull)
```

**Startup ordering:**

| Priority | Bean | Responsibility |
|----------|------|----------------|
| 50 | `AgentConfigBeans` | Load manifest, store credentials, prepare aliases + defaults |
| 75 | `BackendInstanceCoordinator` | Discover credentials in store → create backend instances |
| default | `ModelRegistryRefresher` | Refresh all model sources (seed, cloud, configured) |

**ManifestProcessor pipeline (in agent-config-core):**

1. **Providers → Credentials:** Resolve `env:`/`file:`/`ref:` references via `ManifestCredentialResolver`, validate against `VendorInfo.requiredFields()` (injected as `Map<String, List<String>>` by Quarkus layer), store in `LlmCredentialStore` as `"cloud-{vendorKey}"` (matches `BackendInstanceFactory` pattern)
2. **Models → Registry:** Register via `MutableModelRegistry.replaceSource("manifest", 8, models)`. Priority 8 sits above cloud sources (5) but below per-tenant runtime config (10)
3. **Local models → Reconciliation:** Delegate to `LocalModelReconciler` functional interface (Quarkus layer provides implementation via `OllamaModelSource` + `LlmConfigService.pullModel()`)
4. **Aliases + Defaults → ManifestResult:** Convert `AliasDeclaration` → `ModelQuery`, produce `ManifestResult` record consumed by `RouterBeans`

**Credential reference resolution chain:**
- `env:VAR` → `System.getenv()` (dev, CI)
- `file:/path` → file contents (k8s mounted secrets)
- `ref:credential-ref` → `CredentialResolver` SPI → `credentials-quarkus` bridge → Quarkus `CredentialsProvider` → Vault/AWS/GCP

**Adding a new vendor:** Implement `VendorClient` in `llm-config/`. The interface requires `vendorKey()`, `backendKey()`, `displayName()`, `authMethod()`, `requiredFields()`, and `listModels(credentials)`. The manifest processor picks up required fields automatically via CDI discovery in `AgentConfigBeans`.

**model-selection.schema.json:** Published as a Maven artifact resource in `agent-config-core`. Defines a union type (string | ModelConstraints object). Eidos and org descriptors `$ref` this schema for task-level model requirements. The schema uses open `type: string` for capabilities (not a closed enum) to allow new capabilities without schema updates.

### Agent Infrastructure

`AgentProvider` SPI with two execution paths:
- `invoke(AgentSessionConfig)` -- single-shot, per-invocation semaphore. Returns cold `Multi<AgentEvent>`. `AgentSessionConfig` carries `systemPrompt`, `userPrompt`, `mcpServers`, `timeout`, `correlationId`, nullable `model` (String), and nullable `modelQuery` (ModelQuery for direct constraint dispatch). Use `withModel(String)` or `withModel(ModelQuery)`.
- `openSession(AgentSessionInit)` -- multi-turn `AgentSession` (IDLE/ACTIVE/CLOSED state machine), semaphore held for session lifetime. Same `withModel` overloads.

CDI tier for `AgentProvider`:
- Tier 0: `NoOpAgentProvider @DefaultBean` (platform/) -- fallback
- Tier 1: `ChatModelAgentProvider @Alternative @Priority(1)` (agent-langchain4j/) -- any LangChain4j ChatModel
- Tier 10: `ClaudeAgentProvider @Alternative @Priority(10)` (agent-claude/) -- native Claude CLI
- Decorator: `GatedAgentProvider @Decorator @Priority(APPLICATION)` (agent-gate/) -- token bucket + concurrency gate, wraps any provider

`AgentEvent` sealed interface variants: `TextDelta`, `ThinkingDelta`, `ToolCallDelta`, `ToolCallComplete`, `ToolResult`, `InvocationComplete` (terminal with cost/usage/timing metadata).

`AgentMcpServer` sealed interface with three transport variants: `Stdio(command, args, env)`, `Sse(url, headers)`, `Http(url, headers)`.

`agent-claude/` wraps the Claude Code CLI via the Spring AI Community `claude-code-sdk` 1.0.0. `ClaudeAgentClient` manages subprocess lifecycle, semaphore gating, wall-clock timeout, and message-to-AgentEvent mapping via `MessageEventMapper`. `ClaudeAgentSession` implements serial multi-turn with IDLE/ACTIVE/CLOSED state machine. First `query()` calls `connect()`; subsequent calls use `query()` on the same session.

`agent-langchain4j/` provides bidirectional interop:
- `ChatModelAgentProvider` wraps any LangChain4j `ChatModel` as `AgentProvider`. Discovers `@Default ChatModel` beans, filters out `AgentProviderChatModel` to avoid circular injection. Supports streaming via `StreamingChatModel` detection.
- `AgentProviderChatModel` / `AgentSessionChatModel` wrap `AgentProvider` as LangChain4j `ChatModel`. Blocks on Mutiny pipeline, collects text deltas, maps to `AiMessage`.
- `AgentEventBridge` converts LangChain4j streaming responses into `Multi<AgentEvent>`.

### Access Control Architecture

**InMemoryAccessControlProvider** (`@Alternative @Priority(10)`): Three `ConcurrentHashMap`s -- grants, denies, parents. `GrantKey = (actorId, ResourceId, action, tenancyId)`. All SPI methods use `ResourceId` (structured `type:id` value type) instead of raw strings.

**JpaAccessControlProvider** (`@ApplicationScoped`): Hibernate ORM entities. All mutations are `@Transactional` with audit logging to `AclAuditLogEntity`. Upsert semantics on grants/denies.

Both implementations share the same resolution algorithm:

1. Build candidate set: actor ID + `"group:<groupName>"` for each group (via `GroupMembershipProvider.groupsOf()`)
2. For each candidate, check resolution order: instance deny -> instance grant -> wildcard (`<type>:*`) deny -> wildcard grant
3. Walk parent chain (depth 20 limit) if no instance/wildcard match
4. Deny wins at each specificity level

**JPA-specific features:**
- Recursive CTE for `accessibleResourcesIncludingInherited()` -- single SQL query traverses `resource_parent` table
- Cursor-based pagination on `accessibleResources(AclQuery)` -- `ORDER BY e.resourceId` + `e.resourceId > :cursor`, fetches `limit + 1` rows
- Scheduled retention purge via `AclRetentionPurge`:
  - `purgeExpiredEntries()` -- cron `${casehub.acl.retention.expired-purge-cron:0 0 3 * * ?}` -- deletes expired ACL entries
  - `purgeAuditLog()` -- cron `${casehub.acl.retention.audit-purge-cron:0 30 3 * * ?}` -- deletes audit log entries older than `casehub.acl.retention.audit-days` (default 365)

**JPA tables:**
- `acl_entry` -- unique constraint `(actor_id, resource_id, action, tenancy_id, entry_type)`. Index on `(actor_id, resource_id)`, `(resource_id)`, `(tenancy_id)`, `(entry_type)`.
- `acl_audit_log` -- indexes on `(resource_id)`, `(actor_id)`, `(performed_by)`, `(performed_at)`, `(tenancy_id)`.
- `resource_parent` -- composite PK `(child_resource_id, tenancy_id)`. Index on `(parent_resource_id)`.

**ACL Admin REST API** (`@Path("/acl") @RunOnVirtualThread`): Full CRUD for grants and denies (single + batch), parent registration, access check (self or admin), paginated accessible resources. All mutation endpoints require `@RolesAllowed("admin")`.

### Preference Management Architecture

**Read path:** `PreferenceProvider.resolve(SettingsScope)` applies full ancestor-chain inheritance. Walks `Path.parent()` to build scope list (root -> intermediate -> target), queries all rows, sorts by depth, merges child-overrides-parent.

**Write path:** `PreferenceStore` SPI with JPA and MongoDB implementations. All mutations are tenant-scoped and fire `PreferenceChanged` CDI event async.

**Schema system:**
- `PreferenceSchemaRegistry` -- register/resolve/discover schema descriptors. `InMemoryPreferenceSchemaRegistry` (`@ApplicationScoped` in preferences-editor) backed by `ConcurrentHashMap` + `AtomicLong` version counter.
- `PreferenceSchemaDescriptor` -- carries type, constraints, enum options. Builder infers type from `PreferenceKey.defaultValue()` class.
- `PreferenceValidator` -- validates values against schema constraints at write time. Returns `List<String>` violations. Supports integer, number, boolean, duration, string (with minLength/maxLength/pattern), and enum validation.
- Schema versioning via `version()` monotonic counter -- `PreferenceSchemaResource` returns ETag based on version, supports 304 Not Modified via `request.evaluatePreconditions()`.
- `PlatformPreferenceRegistrar` (`@ApplicationScoped` in platform/) -- canonical `@Observes StartupEvent` registrar. Registers 6 retention `PreferenceSchemaDescriptor` entries from `PlatformPreferenceKeys`. Domain modules follow the same pattern with their own keys class + registrar bean.

### Subject View Architecture

**Evaluation flow:**
1. `SubjectViewOrchestrator.evaluateAndTrack()` fetches before-state from `ViewMembershipTracker`
2. Loads views from `SubjectViewStore` (with optional TTL cache)
3. `SubjectViewEvaluator.evaluateMembership()` matches subject label paths against view label patterns
4. `SubjectViewEvaluator.computeEvents()` diffs before/after: ADDED (new membership), REMOVED (lost membership), CHANGED (still member, name or scope changed)
5. `ViewMembershipTracker.updateMembership()` persists new state

**Scope-aware evaluation:** Views with a `scope` field are filtered to match only subjects whose scope equals or is a descendant of the view's scope. Views with null scope match all subjects.

**Batch operations:** `evaluateAndTrackBatch()` fetches all before-state in one bulk `getLastKnownMembership(Set<UUID>)` call, then iterates subjects. Scope-aware variant accepts `Function<UUID, Path> scopeResolver`.

**View deletion:** `deleteView()` proactively cleans up: finds all current members via `getSubjectsByView()`, generates REMOVED events for each, deletes the view, invalidates cache, removes all membership tracking entries.

**JPA query support:** `JpaLabelPatternQuerySupport<E, L>` is an abstract base class for domain-specific label-pattern queries. Takes JPA metamodel attributes in constructor. `LabelPatternPredicates` translates label patterns to SQL LIKE predicates: `/**` -> `LIKE prefix/%`, `/*` -> `LIKE prefix/% AND NOT LIKE prefix/%/%`, exact -> `=`. Escapes `\`, `%`, `_` in pattern prefixes.

### Label Infrastructure

`LabelRule` is a self-evaluating record -- no separate evaluator class. Static `evaluate(rules, context)` filters rules where `condition.eval(context)` returns `Boolean.TRUE`, flatMaps their `LabelAction` lists. Event-scoped variant `evaluate(rules, context, event)` additionally filters by `triggerEvents` (empty set matches all events).

`LabelAction` is a sealed interface with `Add(label)` and `Remove(label)` variants. Both enforce non-null, non-blank labels.

The `condition` field is `CompiledExpression<Map<String, Object>, Boolean>` -- any expression engine (JQ, MVEL, JEXL) can compile the condition. JQ expressions work through `MapAdaptedJQExpression` which auto-converts `Map` to `JsonNode`.

### Document Signing Architecture

Two separate signing concerns coexist:
- **Ledger signing** (`SigningProvider` in `platform-api/.../signing/`) — raw sign/verify for agent attestation (Ed25519 keypairs). Used by casehub-ledger.
- **Document sealing** (`DocumentSigningService` in `platform-api/.../signing/document/`) — PAdES/CAdES for compliance reports. Used by casehub-qhorus compliance-report.

These are deliberately separate SPIs — document signing requires X.509 certificate chains and timestamping, which raw `SigningProvider` cannot provide.

`platform-signing/` implements document sealing via EU DSS 6.2:
- `document/` — core signing and verification:
  - `KeyStoreManager @ApplicationScoped` — wraps `Pkcs12SignatureToken`, `@Inject` from `DssSigningConfig`. Shared by `DssDocumentSigningService`, `CertificateExpiryScheduler`, and `TenantKeyStoreResolver`
  - `DssDocumentSigningService` — 3-step DSS flow: `getDataToSign()` → `token.sign()` → `signDocument()`. Stateless per call
  - `DssDocumentVerificationService` — `SignedDocumentValidator.fromDocument()` with optional `TrustedListManager` as trusted cert source
  - `TrustedListManager @ApplicationScoped` — EU LOTL loading via `dss-tsl-validation` (`TLValidationJob.onlineRefresh()`). File-cached 24h. Disabled when `casehub.signing.trusted-list-url` absent
- `lifecycle/` — certificate lifecycle management:
  - `CertificateExpiryMonitor` — scans keystore certificates, fires `CertificateExpiryEvent` CDI event when within threshold. Clock-injected for deterministic tests
  - `CertificateExpiryScheduler` — `@Scheduled(every = "6h")` driver
  - `KeyStoreRotationService` — atomic `AtomicReference<KeyStoreManager>` swap. Failed rotations (wrong password, missing file) keep the old manager — no signing downtime
- `tenant/` — multi-tenant keystore support:
  - `TenantKeyStoreResolver` — maps tenancyId to per-tenant PKCS#12 via `TenantKeyStoreConfig`. ConcurrentHashMap cache (one `KeyStoreManager` per tenant, created on first resolve). Unknown tenants fall back to the default `KeyStoreManager`

**PDFBox version constraint:** `platform-pdf` uses PDFBox 3.0.3 (via OpenHTMLtoPDF 1.1.37). `platform-signing` uses PDFBox 3.0.4 (via DSS 6.2). Both 3.0.x — binary compatible. Maven resolves to 3.0.4. If either dependency bumps PDFBox major version, alignment must be verified.

### Capacity Signal + Redistribution

Cross-domain actor overload detection and automated work redistribution. The SPI lives in `platform-api` (`io.casehub.platform.api.capacity`); implementations in `platform/` (`io.casehub.platform.capacity`).

**Signal flow:** Domain repos implement `CapacitySignalSource` to expose their load metric as pressure 0.0–1.0. `AggregatingActorCapacityView` collects all sources via `@Any Instance<CapacitySignalSource>` and takes max-pressure across signal types. `CapacityPressureMonitor` sweeps every 60s, fires `CapacityPressureEvent` (CDI async) per overloaded actor. Domain executors observe the event, build `RedistributionContext` with their own domain state, and call `RedistributionPolicy.evaluate()` for a decision: Compress, Redistribute, Hold, or Escalate.

**Key types:** `CapacitySignal` (record: actorId, signalType, pressure, observedAt, metadata), `CapacitySignalSource` (SPI: observe + observeOverloaded), `ActorCapacity` (record: aggregated view), `ActorCapacityView` (SPI: single + fleet query), `RedistributionPolicy` (SPI: evaluate → decision), `RedistributionDecision` (sealed: Redistribute/Compress/Hold/Escalate), `CapacityPressureEvent` (CDI event).

**Implementations:** `AggregatingActorCapacityView @DefaultBean` (max-pressure, error-isolated), `DefaultRedistributionPolicy @DefaultBean` (configurable thresholds: compress 0.7, redistribute 0.85, immediate 0.95, inactivity escalation 5m), `CapacityPressureMonitor @ApplicationScoped` (@Scheduled sweep), `CapacityActorStateContributor @ApplicationScoped` (bridges capacity into the ActorState dashboard via `ActorStateAccumulator.capacity()`).

**Signals are tenant-agnostic.** An agent's context window or session slots are physical resources shared across tenants. Pressure represents aggregate physical state. Tenant scoping happens at the domain executor level during redistribution.

---

## Dependencies

### Depends On

- `casehub-parent` (BOM only) -- no casehubio runtime dependencies
- Quarkus 3.32.2 (in implementation modules only, never in `platform-api/`)
- jackson-jq 1.6.0 (expression module)
- MVEL3 3.0.0-SNAPSHOT from JBoss Nexus snapshots (expression module)
- Commons JEXL 3.4.0 (expression module)
- LangChain4j 1.14.1 (agent-langchain4j module)
- Spring AI Community `claude-code-sdk` 1.0.0 (agent-claude module)
- `quarkus-kubernetes-config` (optional, expression module -- for K8s ConfigMap/Secret integration)

### Depended On By

Every casehub module depends on `platform-api`. Known consumers:
- casehub-ledger, casehub-work, casehub-qhorus, casehub-engine
- claudony, devtown, aml, clinical, life
- casehub-neocortex (memory backend implementations)
- casehub-connectors (notification deliverers, destination resolvers)
- casehub-desiredstate (uses `DoublePreference`, `IntPreference`)
- casehub-ras (uses `StringExpressionEvaluator`)

---

## Simulation Framework

The simulation framework provides SPI-level testing infrastructure — a complete replacement for Mockito when testing SPI interactions. It intercepts SPI method calls via CDI `@Decorator`, resolves responses from a corpus via configurable strategies, and records all interactions in an `InvocationJournal` for verification.

### Two Simulation Paths

**Path A — Generated decorator (most SPIs):** `@SimulationEligible` annotation on an SPI interface triggers `SimulationDecoratorProcessor` (Jandex-based APT) to generate a `@Decorator @Priority(APPLICATION + 200)` class. The decorator checks `SimulationRuntime.strategyFor(qualifiedName)` — if a strategy is configured and can resolve the input, it returns the strategy's result; otherwise it passes through to the delegate. The decorator also records every call to `InvocationJournal` (with `tenancyId` from `CurrentPrincipal`) and optionally captures input/output to the corpus for replay.

**Path B — Backend routing (AgentProvider):** Simulation is a registered backend key (`"simulated"`) that `RoutingAgentProvider` dispatches to via model resolution. `SimulatedAgentBackend` resolves from `SimulationStrategy` directly. Used because AgentProvider routing is key-based, not decorator-based.

### Key Internal Types (simulation-core)

| Type | Role |
|------|------|
| `SimulationRuntime` | Central coordinator — strategy factory (`createStrategy`), strategy cache (`ConcurrentHashMap`), `KeyExtractor`/`SimilarityScorer` registration, overlay stack management, capture dispatch, profile activation via `ProfileSource`. Constructor-injected POJO, no CDI. |
| `SimulationOverlay` | Opaque per-scenario handle. Contains: `SimulationConfig`, `SimulationCorpus`, `InvocationJournal`, strategy cache. Created by `pushOverlay()`/`pushProfile()`, discarded on `popOverlay()`. |
| `InvocationJournal` | Synchronized `ArrayList` of `JournalEntry` records. Thread-safe. Per-overlay. Methods: `entries()`, `entriesFor(qn)`, `countFor(qn)`. |
| `JournalEntry` | Immutable record: `qualifiedName`, `tenancyId`, `input`, `output`, `timestamp`, `simulated`. |
| `SimulationVerifier` | Stateful verification on `InvocationJournal`. Tracks verified methods for `noUnverifiedCalls()`. `method(qn)` returns `MethodVerification`. `inOrder(qn...)` asserts call sequence. |
| `MethodVerification` | Fluent filter-then-assert: `forTenant(id)`, `matching(Predicate<JournalEntry>)`, `wasCalled()`/`wasCalled(n)`/`wasNeverCalled()`/`wasCalledAtLeast(n)`/`wasCalledAtMost(n)`, `allSimulated()`/`noneSimulated()`. Mockito-quality error messages with actual call listings. |

### Strategy Implementations (simulation-core)

| Strategy | Resolution | Key requirement |
|----------|-----------|-----------------|
| `SequentialStrategy` | Round-robin through corpus entries | None |
| `KeyLookupStrategy` | Exact key match | `KeyExtractor` registered |
| `RandomStrategy` | Random selection from corpus | None |
| `RecordedReplayStrategy` | Replay in recorded order, key-matched | `KeyExtractor` registered |
| `NearestMatchStrategy` | O(n) corpus scan, best score above threshold | `SimilarityScorer` registered |

### Code Generator (simulation-generator)

`SimulationDecoratorProcessor` is a Jandex-based annotation processor. It scans `@SimulationEligible` interfaces (via annotation or `META-INF/simulation-eligible.txt` listing file) and generates three outputs per SPI. For capability-based SPIs (those with `@SimulationEligible(capabilities = {"methodName"})`) it additionally generates recursive wrapper inner classes, a `supports()` override, and dotted QN constants — see Capability-Based SPIs below. Flat SPIs (empty `capabilities`, the default) generate:

1. **Decorator class** (e.g., `SimulatedAccessControlProvider`) — `@Decorator @Priority(APPLICATION + 200)`. Injects `delegate` + `SimulationRuntime` + `CurrentPrincipal`. Intercepts ALL interface methods (abstract and default). For each method: check strategy → resolve or delegate → record to journal (with tenancyId, null fallback for missing tenancy) → optionally capture.

2. **QN constants class** (e.g., `AccessControlProviderQN`) — one `public static final String` per method (e.g., `CANACCESS = "access-control-provider.canAccess"`). Compile-time safety for qualified names — typos cause compilation errors, not silent runtime mismatches.

3. **Parameter registry** (`META-INF/simulation-parameters.properties`) — maps each qualified method name to its parameter names and positions (e.g., `access-control-provider.canAccess=actorId:0,resourceId:1,action:2`). Consumed by `ParameterRegistry` at runtime so `DeclarativeExtractorFactory` can resolve bare parameter names as key-extractor specs.

A separate `RestClientSimulationProcessor` in `rest-client-simulation-generator/` handles `@RegisterRestClient` interfaces with `@RestClient`-qualified delegates and `RestInvocation` input types.

### Configuration (simulation-config-core)

| Type | Role |
|------|------|
| `YamlSimulationConfig` | Unified YAML parser — reads `simulation.yaml`, provides per-method strategy config, inline corpus entries, external corpus-files refs, named profiles. Implements `SimulationConfig` + `ProfileSource`. Convention-discovered from classpath root. Delegates corpus file loading to `CompositeCorpusLoader`. |
| `CorpusLoader` | SPI for format-independent corpus loading. `supports(path)` checks extension, `load(InputStream, defaultTenancyId)` returns `Map<String, List<InvocationRecord>>`. |
| `YamlCorpusLoader` | `.yaml`/`.yml` corpus files — extracted from `YamlSimulationConfig`. Also used by pages `ScenarioOrchestrator`. |
| `JsonCorpusLoader` | `.json` corpus files — same structure as YAML, parsed with `ObjectMapper` (no YAMLFactory). |
| `CsvCorpusLoader` | `.csv` corpus files — reserved columns `_qualified_name`, `_key`, `_tenancy_id`; remaining columns become output map. |
| `CompositeCorpusLoader` | Aggregates `CorpusLoader` implementations, dispatches by file extension. CDI-produced by `SimulationConfigBeans`. |
| `CorpusEntryParser` | Shared conversion utility: raw entry maps → `InvocationRecord` list with tenancy-id fallback. |
| `StreamResolver` | Classpath (`classpath:`) and filesystem path resolution for corpus files. |
| `ParameterRegistry` | Loads `META-INF/simulation-parameters.properties` — maps qualified method names to parameter name/position pairs. APT-generated, classpath-aggregated. |
| `DeclarativeExtractorFactory` | Config string → `KeyExtractor`. Prefixed specs: `identity`, `field:name`, `composite:x,y`, `rest-client`. Bare names resolve via `ParameterRegistry` (single-arg → identity, multi-arg → positional). |
| `DeclarativeScorerFactory` | Config string → `RecordFieldScorer` (`fields:name:EXACT:1.0,age:NUMERIC_RANGE:0.5`) |
| `TemporalProfileConfig` | YAML temporal profile schema — 4 mutually exclusive sources: `events:`, `events-file:`, `from-corpus:`, `sequence:`. |
| `TemporalProfileRegistry` | Resolved profile lookup by name. `resolve(name)` → `TemporalProfile<Map>`. Typed overload: `resolve(name, Class<T>, ObjectMapper)` → `TemporalProfile<T>` via `map()`. |
| `DurationParser` | Parses duration strings: `"5s"`, `"2m"`, `"500ms"`, bare millis. |

### Temporal Simulation (simulation-core)

| Type | Role |
|------|------|
| `TimedEntry<E>` | Event + relative delay + optional label (journal verification) + optional qualifiedName (per-entry override for concat attribution). `map(Function<E,R>)` transforms payload preserving metadata. |
| `TimedSequence<E>` | Generic timed sequences with relative delays. `withMultiplier(10.0)` compresses a 30-min case to 3 min. `fromRecorded(List<InvocationRecord>)` derives timing from captured data. `map(Function<E,R>)` transforms all entry payloads. |
| `TemporalProfile<E>` | Named sequence + qualifiedName + tenancyId + loop + speed. Wraps `TimedSequence` with lifecycle metadata for the driver. `map(Function<E,R>)` converts payload type — e.g. `profile.map(map -> mapper.convertValue(map, DomainEvent.class))`. |
| `TemporalSimulationDriver<E>` | Lifecycle controller: IDLE → RUNNING ↔ PAUSED → STOPPED/COMPLETED. Virtual-thread sleep loop, journal integration via `SimulationRuntime.recordJournal()`, per-event error isolation, 3-level speed composition: `effectiveSpeed = profile.speed × globalMultiplier` with per-driver override via `setSpeed()`/`resetSpeed()`. |
| `TemporalEventSink<E>` | Delivery callback: `deliver(qualifiedName, label, event)`. Provides context for CloudEvent construction. |
| `TemporalDriverFactory<E>` | Factory for creating driver instances. Drivers are lightweight; `stop()` is terminal. |

### Event Simulation (event-simulation-core + event-simulation)

| Type | Role |
|------|------|
| `SimulatedEventEmitter` | `tick()`-based emitter, fires via `Consumer<CloudEvent>` callback. CDI wiring provides `Event<CloudEvent>.fireAsync()` for full pipeline fidelity. |
| `EventSequenceRunner` | One-shot virtual-thread executor for timed sequences. Per-source error isolation. |
| `TemporalDriverService` | `@McpDomain("temporal-drivers")` — remote control API for `TemporalSimulationDriver`. Start/stop/pause/resume/setSpeed/resetDriverSpeed/setGlobalSpeed/globalSpeed/status/list. Named profile references (from `TemporalProfileRegistry`) and inline profile definitions. COMPLETED/STOPPED drivers auto-replaced on start; RUNNING/PAUSED conflict errors. GraphQL + REST generated by APT. |

### Schema-Driven Data Generation (schema-generator)

`SchemaDataGenerator` produces random instances from JSON Schema (Draft 2020-12). Supports: string/integer/number/boolean/object/array types, enum selection, const values, Jakarta Validation constraints (min/max, minLength/maxLength, pattern), format (uuid, date-time), `$ref`/`$defs` resolution with depth guard (20). Seeded `Random` for reproducible output. Typed overload: `generate(schema, count, Class<T>, ObjectMapper)` → `List<T>`.

### Testing Utilities (simulation-testing)

Per-SPI corpus descriptor classes provide typed `CorpusSeed` factory methods, domain fixture factories, and default key extractors:

| Descriptor | SPI | Module |
|-----------|-----|--------|
| `AclCorpus` | `AccessControlProvider` | simulation-testing |
| `ModelCorpus` | `ModelRegistry` | simulation-testing |
| `NotificationCorpus` | `NotificationStore` | simulation-testing |
| `PreferenceCorpus` | `PreferenceProvider` | simulation-testing |
| `CredentialCorpus` | `CredentialResolver` | simulation-testing |
| `AgentCorpus` | `AgentProvider` | agent-simulation-core |

Additional utilities:
- `LlmCorpusPopulator` — takes `Function<String, String>` (framework-agnostic), uses `PlatformSchemaGenerator` for JSON Schema prompts, hybrid few-shot from existing seed entries
- `RandomCorpusPopulator` — thin `CorpusSeed` integration over `SchemaDataGenerator` for schema-driven random corpus population

### Module Dependency Graph

```
simulation-api ──→ simulation-core ──→ simulation-inmem
       │                  │
       │                  ├──→ simulation-testing (test utilities)
       │                  │
       │                  ├──→ TimedEntry, TimedSequence, TemporalProfile,
       │                  │    TemporalSimulationDriver (temporal simulation)
       │                  │
       ├──→ simulation-config-core ──→ simulation-config (Quarkus beans + TemporalProfileRegistry)
       │         │
       │         └──→ TemporalProfileConfig, DurationParser, TemporalProfileRegistry
       │
       ├──→ simulation-generator (APT)
       │         ├──→ platform-simulation-core (11 platform-api SPIs)
       │         ├──→ memory-simulation-core (CaseMemoryStore)
       │         └──→ rest-client-simulation-generator (@RegisterRestClient)
       │
       └──→ event-simulation-core ──→ event-simulation (Quarkus @Scheduled + TemporalDriverFactory)

agent-api ──→ agent-simulation-core (SimulatedAgentBackend + AgentCorpus)

schema-generator (SchemaDataGenerator) ──→ simulation-testing (RandomCorpusPopulator)
```

---

## Current State

All modules listed above are shipped and active in the build.

Memory backend modules (`memory-inmem/`, `memory-jpa/`, `memory-sqlite/`, `memory-mem0/`, `memory-graphiti/`) were migrated to casehub-neocortex (neocortex#56). Directories remain on disk but are no longer in `<modules>`.

### Anti-Patterns

- Do not define parallel path, scope, preference, or principal types. `platform-api` owns these.
- Do not use `@ConfigMapping` for case-type business rules. Use `PreferenceProvider`.
- Do not call `SecurityIdentity` from `platform-api/`. Zero deps means zero Quarkus imports.
- Do not make `CurrentPrincipal` `@ApplicationScoped` in a real deployment. Real implementations must be `@RequestScoped`.
- Do not inject `Principal` directly in Quarkus. Use `SecurityIdentity` or `CurrentPrincipal`.
- Do not pass `AccessControlProvider` methods without tenant context. Implementations derive `tenancyId` from `CurrentPrincipal` internally -- the SPI deliberately omits it to keep the interface clean.
- Do not use raw `JsonQuery` for JQ evaluation. Use `JQEvaluator` (expression module) -- it handles `$secret` and `$config` scope injection.
- Do not add `Labellable` or `LabelRuleEvaluator` interfaces. Label evaluation is a static method on `LabelRule`. Entities participate in labelling by carrying `Set<String>` label paths, not by implementing a marker interface.

---

## Design Documents

- `ARC42STORIES.MD` -- primary architecture record (layer taxonomy, building block view, glossary)
- ADRs in `docs/adr/`: 0001 (Path API), 0002 (PreferenceKey contract), 0003 (null-returning get)
- Garden protocols:
  - `casehub/garden: docs/protocols/casehub/typed-preference-keys.md` -- `PreferenceKey<T>` contract
  - `casehub/garden: docs/protocols/casehub/platform-spi-contract.md` -- implementation rules for SPIs
  - `casehub/garden: docs/protocols/universal/module-tier-structure.md` -- Tier 1/2/3 rules
  - `casehub/garden: docs/protocols/universal/persistence-backend-cdi-priority.md` -- CDI priority ladder
