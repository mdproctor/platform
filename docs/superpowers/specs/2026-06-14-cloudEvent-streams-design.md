# CloudEvent Foundation and Platform Stream Modules

**Date:** 2026-06-14 (revised 2026-06-18)
**Issue:** casehubio/platform#98
**Epic:** casehubio/parent#277
**Architectural context:** `docs/superpowers/specs/2026-06-13-p0-layering-decisions-design.md` in casehubio/parent (Decisions 1 and 2)

---

## What this implements

1. `io.cloudevents.CloudEvent` as the platform's typed CDI event type — added as a `compile` dependency to `casehub-platform-api`.
2. `EndpointRegistered` CDI event record fired by non-no-op `EndpointRegistry` implementations when an endpoint is stored.
3. `STREAM_EVENT_TYPE` property key on `EndpointPropertyKeys`.
4. `AMQP` added to `EndpointProtocol` enum.
5. Five classpath-activated stream submodules under `casehub-platform`, each firing `Event<CloudEvent>.fireAsync()`.

`StreamContext` SPI is **deferred to P1.8**. The async-tenancy propagation mechanism is unresolved. In P0, all `@ObservesAsync CloudEvent` handlers that need tenancy extract it directly from `event.getExtension("tenancyid")`. Introducing a `@DefaultBean @ApplicationScoped` that always returns `DEFAULT_TENANT_ID` would silently corrupt multi-tenant deployments for any caller who injects it over the direct extraction approach. Define the SPI at P1.8 alongside the working propagation mechanism.

---

## CloudEvent SDK version management

`cloudevents-core:4.0.1` and `cloudevents-api:4.0.1` are added to `casehub-platform-parent`'s `<dependencyManagement>` as direct entries (not BOM imports). This overrides the Quarkus 3.32.2 BOM's `cloudevents-api:3.0.0` — confirmed necessary: the Quarkus 3.32.2 BOM manages only `cloudevents-api:3.0.0`, and `cloudevents-core:4.0.1` depends on `cloudevents-api:4.0.1`. Maven's direct-entry precedence over BOM imports handles the override correctly; position within `<dependencyManagement>` does not matter.

`cloudevents-json-jackson:4.0.1` is also pinned — same CloudEvents SDK release, needed by `streams-kafka` for Kafka CloudEvents deserialization.

Once `casehub-iot`, `casehub-qhorus`, or `casehub-connectors` implement their adapters, version management moves to `casehub-parent` BOM (parent#276) and the platform-parent entries are removed.

---

## Changes to `casehub-platform-api`

### Package: `io.casehub.platform.api.endpoints`

**`EndpointRegistered` record** (new):

```java
public record EndpointRegistered(EndpointDescriptor descriptor) {}
```

CDI event type. Fired by `InMemoryEndpointRegistry.register()` via `Event<EndpointRegistered>.fireAsync()` (with exception logging — see Changes to `endpoints-memory` below) after every successful `store.put()`. `NoOpEndpointRegistry.register()` is a silent no-op and must NOT fire this event — firing it would trigger Camel route creation for phantom endpoints. The `EndpointRegistry` interface Javadoc states this as a required obligation for all non-no-op implementations.

**`EndpointPropertyKeys.STREAM_EVENT_TYPE`** (new constant):

```java
/**
 * Logical CloudEvent {@code type} for a stream source — reverse-DNS, e.g.
 * {@code io.casehub.iot.temperature}. All stream modules read this from
 * {@link EndpointDescriptor#properties()} to set the CloudEvent {@code type} field.
 *
 * <p>Applies to: all stream endpoint protocols
 * ({@link EndpointProtocol#KAFKA}, {@link EndpointProtocol#AMQP},
 * {@link EndpointProtocol#HTTP}, {@link EndpointProtocol#CAMEL}).
 */
public static final String STREAM_EVENT_TYPE = "stream-event-type";
```

**`EndpointPropertyKeys.TOPIC` Javadoc update**: Add `{@link EndpointProtocol#AMQP}` to the applies-to list. The constant applies to both KAFKA and AMQP. Remove "only" from the current text.

**`EndpointProtocol.AMQP`** (new enum value, inserted after `KAFKA`, before `MCP`):

```java
/**
 * AMQP message broker transport. Use {@link EndpointPropertyKeys#TOPIC} for queue or
 * topic name. {@link EndpointPropertyKeys#URL} does not apply — broker connection
 * is Quarkus-managed via standard config (e.g. {@code amqp-host}, {@code amqp-port}).
 */
AMQP,
```

### `cloudevents-core` compile dependency

```xml
<dependency>
    <groupId>io.cloudevents</groupId>
    <artifactId>cloudevents-core</artifactId>
    <scope>compile</scope>
</dependency>
```

`io.cloudevents.CloudEvent` is visible to all consumers of `casehub-platform-api` transitively. No wrapper type.

---

## Changes to `casehub-platform` (default/mock module)

No changes needed for stream-related defaults. `NoOpStreamContext` is not introduced (StreamContext deferred to P1.8).

The existing `NoOpEndpointRegistry @DefaultBean` is unchanged — its `register()` remains a silent no-op and must not fire `EndpointRegistered`.

---

## Changes to `casehub-platform-endpoints-memory`

### Constructor injection on `InMemoryEndpointRegistry`

`InMemoryEndpointRegistry` currently has no constructor — the `ConcurrentHashMap` is field-initialised and all 14 unit tests construct it directly via `new InMemoryEndpointRegistry()`. Adding CDI field injection for `Event<EndpointRegistered>` would leave the field null in those tests and NPE on every `register()` call.

Use constructor injection with a package-private no-arg constructor for the CDI proxy and unit test path:

```java
private final Event<EndpointRegistered> endpointRegisteredEvent;

@Inject
public InMemoryEndpointRegistry(Event<EndpointRegistered> endpointRegisteredEvent) {
    this.endpointRegisteredEvent = endpointRegisteredEvent;
}

// Used by: CDI proxy subclass (synthetic bytecode) + unit tests (same package)
InMemoryEndpointRegistry() {
    this.endpointRegisteredEvent = null;
}
```

Quarkus ARC generates the proxy subclass using a synthetic no-arg constructor — it does not call the package-private constructor. The no-arg constructor exists for unit tests (`new InMemoryEndpointRegistry()` in `@BeforeEach`) and is inaccessible outside the package.

### `register()` with event firing and exception logging

```java
@Override
public void register(EndpointDescriptor endpoint) {
    store.put(new RegistryKey(endpoint.path().value(), endpoint.tenancyId()), endpoint);
    if (endpointRegisteredEvent != null) {
        endpointRegisteredEvent.fireAsync(new EndpointRegistered(endpoint))
            .whenComplete((e, t) -> {
                if (t != null) {
                    LOG.warnf(t, "EndpointRegistered observer failed for path %s", endpoint.path());
                }
            });
    }
}
```

`fireAsync()` returns `CompletionStage<Event<EndpointRegistered>>` (GE-20260517-f31786). Observer exceptions complete the stage exceptionally and are otherwise silently swallowed. The `whenComplete` logs at WARN — the registry operation itself has already succeeded (the `store.put()` completed before firing the event).

### Test strategy for `InMemoryEndpointRegistry`

**`InMemoryEndpointRegistryTest`** (existing, plain JUnit5): All 14 tests use `new InMemoryEndpointRegistry()`. The null guard in `register()` means these tests pass unchanged — no event is fired in the no-CDI context, which is correct.

**`InMemoryEndpointRegistryEventTest`** (new, `@QuarkusTest`): Verifies that `EndpointRegistered` fires on each `register()` call. Per GE-20260513-b15933, `@ObservesAsync` is silently not delivered in `@QuarkusTest`. Use an `@ApplicationScoped` capture bean with a `CountDownLatch` whose `capture(EndpointRegistered e)` method is called directly by the test (not observed asynchronously). Both test classes must be named explicitly in the acceptance criteria.

---

## New submodules

### Folder naming and Maven coordinates

| Folder | Artifact ID |
|--------|-------------|
| `streams-kafka/` | `casehub-platform-streams-kafka` |
| `streams-amqp/` | `casehub-platform-streams-amqp` |
| `streams-webhook/` | `casehub-platform-streams-webhook` |
| `streams-poll/` | `casehub-platform-streams-poll` |
| `streams-camel/` | `casehub-platform-streams-camel` |

All five are added to the root `pom.xml` `<modules>` list in build order, after `endpoints-memory` (which they depend on for tests).

### Common pom pattern

All five are **Jandex library modules** — Quarkus plugin goals `generate-code` and `generate-code-tests` only; no `build` goal. Jandex plugin required for CDI bean discovery when consumed as JAR (PP-20260508-6d1f5c). Pattern follows `scim/`.

Common compile deps:
- `casehub-platform-api` (compile)
- `casehub-platform` (runtime — provides `NoOpEndpointRegistry @DefaultBean` during augmentation)
- `casehub-platform-endpoints-memory` (test — provides `InMemoryEndpointRegistry` for test isolation)

### Common CloudEvent construction

All stream modules produce `CloudEvent` with:

| Field | Value |
|-------|-------|
| `type` | `EndpointDescriptor.properties().get(STREAM_EVENT_TYPE)` |
| `source` | logical producer URI, module-specific (e.g. `/platform/streams/kafka/{topic}`) |
| `subject` | `null` for raw payloads (CloudEvents spec allows null); preserved if incoming message is already a CloudEvent (detected by `application/cloudevents+json` content type or binary CloudEvents marker) |
| `id` | `UUID.randomUUID().toString()` |
| `time` | message/frame timestamp if available, `OffsetDateTime.now()` otherwise |
| `data` | raw payload bytes or structured payload |
| `tenancyid` extension | source varies by module — see below |

`tenancyid` source by module:

| Module | `tenancyid` source | Rationale |
|--------|-------------------|-----------|
| `streams-kafka` | Kafka header `X-Tenancy-ID`; fallback to `EndpointDescriptor.tenancyId()` | Internal producer; header is operator-controlled |
| `streams-amqp` | AMQP message property `X-Tenancy-ID`; fallback to `EndpointDescriptor.tenancyId()` | Internal producer; property is operator-controlled |
| `streams-webhook` | `EndpointDescriptor.tenancyId()` (set by operator at registration) | External caller must not self-claim tenant; URL path param is lookup key only |
| `streams-poll` | `EndpointDescriptor.tenancyId()` | Operator-configured polling target |
| `streams-camel` | `EndpointDescriptor.tenancyId()` | Camel route is operator-defined |

---

## Module-by-module design

### `streams-kafka/`

**Dependencies:** `quarkus-smallrye-reactive-messaging-kafka`, `cloudevents-json-jackson` (for native CloudEvents deserialization from Kafka when messages are in CloudEvent format).

**Static channel constraint:** `streams-kafka` does **NOT** observe `@ObservesAsync EndpointRegistered`. Channels are declared via `@Incoming` annotations, which are static — the set of consumed topics is fixed at build time. KAFKA stream descriptors must be registered before application startup; use `endpoints-config` YAML or ensure desiredstate reconciliation completes before the app processes messages. For runtime-dynamic Kafka topic subscriptions, use `streams-camel` with the Camel Kafka component instead. This constraint defines the boundary between `streams-kafka` (static) and `streams-camel` (dynamic) — see the CAMEL/KAFKA mutual exclusion section.

**P0 single-channel constraint:** One `@Incoming("casehub-kafka-stream")` channel per deployment (channel name configurable via `casehub.streams.kafka.channel`, default `casehub-kafka-stream`). Multiple Kafka topics can feed the same channel via `mp.messaging.incoming.casehub-kafka-stream.topics=topic1,topic2` (SmallRye multi-topic). For multiple independently-configured channels, deploy `streams-camel`.

**Channel→EndpointDescriptor correlation** at `@Observes StartupEvent`: reads `mp.messaging.incoming.${casehub.streams.kafka.channel}.topic` (or `.topics`) via MicroProfile Config, then calls `EndpointRegistry.discover(new EndpointQuery(TenancyConstants.DEFAULT_TENANT_ID, null, KAFKA, Set.of(RECEIVE)))` and matches by `TOPIC` property. `DEFAULT_TENANT_ID` is correct here: `matchesTenancy` returns descriptors registered under either `DEFAULT_TENANT_ID` (the deployment tenancy, as set by desiredstate) or `PLATFORM_TENANT_ID` (platform-global). If no matching descriptor is found for a topic, the processor logs a warning and fires a CloudEvent with `type = "io.casehub.platform.streams.kafka.unregistered"` to make the gap observable.

**Message handling:**
- Native CloudEvent (SmallRye CloudEvents Kafka deserialization): add `tenancyid` extension from Kafka header `X-Tenancy-ID` if absent, fire as-is.
- Raw bytes/String: build a CloudEvent using the descriptor's `STREAM_EVENT_TYPE` and message body as `data`.

### `streams-amqp/`

Symmetric with `streams-kafka/`. Uses `quarkus-smallrye-reactive-messaging-amqp`. `EndpointProtocol.AMQP` + `EndpointCapability.RECEIVE` for discovery. `tenancyid` from AMQP message property `X-Tenancy-ID`; fallback to `EndpointDescriptor.tenancyId()`. Same static-channel constraint — does not observe `EndpointRegistered`.

### `streams-webhook/`

**Dependencies:** `quarkus-rest-jackson`, `cloudevents-json-jackson` (compile — CloudEvents structured format deserialization; `io.cloudevents.CloudEvent` is an interface with no Jackson annotations, so Jackson alone cannot deserialize `application/cloudevents+json` payloads without the CloudEvents Jackson module).

**P0 format scope:** Structured CloudEvents format (`application/cloudevents+json`) only. Binary CloudEvents format (`ce-*` headers + arbitrary body) is deferred to P1+ (requires `cloudevents-http-basic` or manual header extraction). Return 400 Bad Request if `Content-Type` is not `application/cloudevents+json`.

**REST endpoint:** `@POST /streams/webhook/{tenancyId}/{streamId}` — responds **202 Accepted** (fire-and-forget; `fireAsync()` does not block on observer completion; returning 200 would incorrectly imply synchronous processing).

- `{streamId}` → `EndpointRegistry.resolve(Path.of("streams", streamId), tenancyIdFromPath)` where `tenancyIdFromPath` is the `{tenancyId}` URL path parameter used as the **registry lookup key**. If `Optional` is empty (unregistered or misspelled `streamId`), return **404 Not Found** — the misconfiguration must be observable, not silently discarded. The returned `descriptor.tenancyId()` — set by the operator at registration time — becomes the CloudEvent `tenancyid` extension value. The URL parameter is the routing key; the descriptor is the tenant authority.
- **Security property:** The `tenancyId` URL path parameter is used only for the registry lookup — never as the CloudEvent `tenancyid` extension value. An external caller supplying a different `{tenancyId}` in the URL gets the descriptor's registered tenancyId in the event, not the caller-supplied value.
- **P0 URL path note:** `PLATFORM_TENANT_ID = "platform"` (the literal string, not a UUID). For standard single-tenant deployments where desiredstate registers endpoints under `DEFAULT_TENANT_ID`, the webhook URL includes that UUID (e.g. `.../webhook/278776f9-e1b0-46fb-9032-8bddebdcf9ce/my-stream`). For platform-global descriptors, the URL includes the string `platform` (e.g. `.../webhook/platform/my-stream`). Both expose internal registration details to external callers. Per-tenant webhook routing with cleaner URLs is P1+.

**Self-registration at `@Observes StartupEvent`:**
```
tenancyId = TenancyConstants.PLATFORM_TENANT_ID   (platform-wide service, visible to all tenants)
Path.of("platform", "streams", "webhook")
EndpointType.SERVICE
EndpointProtocol.HTTP
EndpointCapability.RECEIVE
URL = casehub.streams.webhook.public-url config (required, no default — fail fast if absent)
```

`PLATFORM_TENANT_ID` for the self-registration is correct: `matchesTenancy` returns this descriptor for any tenant query, so any consumer calling `discover()` for its own tenant finds the physical receiver endpoint. Two distinct registry entries: the self-registration at `Path.of("platform", "streams", "webhook")` is the physical receiver. Each logical stream source is at `Path.of("streams", streamId)` registered by `casehub-ops`, not this module. Different paths, different semantics — not a conflict.

### `streams-poll/`

**Dependencies:** `quarkus-rest-client-jackson`, `quarkus-scheduler`.

**Poll loop:**

```java
@Scheduled(every = "${casehub.streams.poll.interval:60s}")
void poll() {
    endpointRegistry.discover(
        new EndpointQuery(TenancyConstants.DEFAULT_TENANT_ID, null, HTTP, Set.of(QUERY))
    ).forEach(descriptor -> {
        try {
            pollAndFire(descriptor);
        } catch (Exception e) {
            LOG.warnf(e, "Poll failed for endpoint %s — continuing to next endpoint",
                descriptor.properties().get(EndpointPropertyKeys.URL));
        }
    });
}
```

Per endpoint: HTTP GET to `EndpointPropertyKeys.URL`; map response body to CloudEvent `data`; set `STREAM_EVENT_TYPE` from descriptor; fire `Event<CloudEvent>.fireAsync()`.

**Tenancy scope for discovery:** `DEFAULT_TENANT_ID` is correct — `matchesTenancy` returns descriptors under `DEFAULT_TENANT_ID` (desiredstate-registered endpoints) and `PLATFORM_TENANT_ID` (platform-global endpoints). `EndpointQuery` requires non-null `tenancyId`: `Objects.requireNonNull(tenancyId)` confirmed in source. Multi-tenant poll scheduling (discovering per-tenant `HTTP + QUERY` endpoints independently) is P1+.

**Per-endpoint failure handling:** Exceptions from HTTP GET (4xx, 5xx, connection timeout) are caught per-endpoint, logged at WARN with the failing URL, and execution continues to the next endpoint. Allowing exceptions to propagate would abort the entire `@Scheduled` invocation on the first bad endpoint, silencing all subsequent endpoints until the next interval.

**`tenancyid`:** `EndpointDescriptor.tenancyId()`.

**Note:** single global poll interval in P0. Per-endpoint intervals are P1+.

### `streams-camel/`

**Dependencies:** `camel-quarkus-core` + Camel components as needed by the consumer application. This module provides the route-building infrastructure; the consumer adds component deps (e.g. `camel-quarkus-kafka` for dynamic Kafka, `camel-quarkus-amqp` for AMQP via Camel).

**Design: discover at startup + idempotent post-startup handler**

The startup handler reads the registry directly (no buffering), covering all pre-startup CAMEL endpoint registrations. The async observer handles post-startup runtime registrations. Idempotency is enforced via a `ConcurrentHashMap`-backed URI set.

```java
private final AtomicBoolean camelStarted = new AtomicBoolean(false);
private final Set<String> routedUris = ConcurrentHashMap.newKeySet();

void onStartup(@Observes StartupEvent ev) {
    // Covers all CAMEL endpoints registered before startup (endpoints-config, desiredstate, etc.)
    // @Startup @ApplicationScoped beans fully execute @PostConstruct before StartupEvent fires,
    // so discover() sees the complete pre-startup registry state.
    endpointRegistry.discover(
        new EndpointQuery(TenancyConstants.DEFAULT_TENANT_ID, null, CAMEL, Set.of(RECEIVE))
    ).forEach(d -> {
        String uri = d.properties().get(EndpointPropertyKeys.URL);
        if (routedUris.add(uri)) addRoute(d);   // atomic — concurrent calls safe
    });
    camelStarted.set(true);
}

void onEndpointRegistered(@ObservesAsync EndpointRegistered event) {
    EndpointDescriptor d = event.descriptor();
    if (d.protocol() != EndpointProtocol.CAMEL) return;
    if (!camelStarted.get()) return;
    // Pre-startup EndpointRegistered events (delivered late by CDI async executor)
    // are discarded here — onStartup's discover() already covered them via the store.
    String uri = d.properties().get(EndpointPropertyKeys.URL);
    if (routedUris.add(uri)) addRoute(d);   // idempotent: skip if already routed
}
```

**Why this is race-free:** `@Startup @ApplicationScoped` beans (including `endpoints-config`) fully complete their `@PostConstruct` before any `@Observes StartupEvent` handler fires. All pre-startup `register()` calls are in the store when `onStartup` calls `discover()`. Any `@ObservesAsync EndpointRegistered` events queued from those calls and delivered after `camelStarted.set(true)` are blocked by the `routedUris` idempotency check. The `ConcurrentHashMap.newKeySet().add()` is atomic — concurrent calls for the same URI result in exactly one `addRoute()`.

**Known startup-window gap (P0 documented limitation):** An endpoint registered in the narrow window *after* `onStartup`'s `discover()` completes but *before* `camelStarted.set(true)` would be discarded by the `!camelStarted.get()` guard without being picked up by `discover()`. This window is effectively zero in production (no desiredstate reconciliation starts before the application is ready), but must be documented in the module's Javadoc.

**Route construction per descriptor:**

```java
void addRoute(EndpointDescriptor d) {
    String uri = d.properties().get(EndpointPropertyKeys.URL);
    camelContext.addRoutes(new RouteBuilder() {
        public void configure() {
            from(uri).process(exchange -> {
                CloudEvent ce = buildCloudEvent(exchange, d);
                cloudEventBus.fireAsync(ce)
                    .whenComplete((e, t) -> {
                        if (t != null) LOG.warnf(t, "CloudEvent observer failed for route %s", uri);
                    });
            });
        }
    });
}
```

Routes added to a running Quarkus Camel context via `addRoutes()` start automatically in Quarkus Camel 3.x.

**P0 constraint — URI changes require restart:** `routedUris` tracks URI strings. If a CAMEL endpoint is re-registered (upsert) with the same URI, the idempotency check prevents a duplicate route. If re-registered with a *different* URI (operator changes the Camel endpoint expression), the old route continues running and a second route is added for the new URI — both active simultaneously. Changing the Camel endpoint URI on an already-routed descriptor requires application restart. Route replacement (stop old route, add new) is P1+. Document this constraint in the `streams-camel` pom `<description>` and in the module Javadoc.

**CAMEL vs KAFKA mutual exclusion (deployment constraint, not code):** `streams-camel` observes `EndpointProtocol.CAMEL` endpoints only, never `KAFKA`. `streams-kafka` uses `EndpointProtocol.KAFKA` static channels. Running both for the same Kafka topic from the same consumer group causes silent partial message loss — Kafka partition-splits between two consumer groups. Deployment rule: use exactly one for any given topic. Static topics known at deploy time → `streams-kafka`. Runtime-dynamic topics registered via desiredstate → `streams-camel` with Camel Kafka component.

---

## Testing

### Test strategy for `fireAsync()` in `@QuarkusTest`

GE-20260513-b15933: `@ObservesAsync` CDI events are silently not delivered in `@QuarkusTest`. Each stream module test must NOT rely on CDI observation to verify `CloudEvent` firing.

Required pattern: extract the CloudEvent construction logic into a package-private method on the processor bean, test that method directly, verify the constructed `CloudEvent` fields without going through `fireAsync()`. For integration-level verification that `fireAsync()` is invoked: use an `@ApplicationScoped` capture bean with a `CountDownLatch`; the test calls the capture bean's method directly (not via CDI observation).

### Per-module test approach

| Module | Message simulation |
|--------|-------------------|
| `streams-kafka` | SmallRye `@InMemoryConnector` (test scope via `smallrye-reactive-messaging-testing`) |
| `streams-amqp` | SmallRye `@InMemoryConnector` — same pattern |
| `streams-webhook` | Quarkus REST Assured — `POST /streams/webhook/{tenancyId}/{streamId}`, verify 202 Accepted |
| `streams-poll` | WireMock raw — `org.wiremock:wiremock:3.13.0` (not quarkiverse extension; see L7 gotcha: quarkiverse WireMock 1.4.1 breaks on Quarkus 3.32.x due to removed `GlobalDevServicesConfig$Enabled` class) |
| `streams-camel` | `camel-quarkus-mock` or `direct:` endpoint URI in test config |

### `InMemoryEndpointRegistry` test classes

- **`InMemoryEndpointRegistryTest`** (existing, plain JUnit5, no CDI): all 14 tests unchanged — null event bus means no events fired, correct for unit test context.
- **`InMemoryEndpointRegistryEventTest`** (new, `@QuarkusTest`): verifies `EndpointRegistered` fires on `register()`. Uses `@ApplicationScoped` capture bean with `CountDownLatch`; test calls capture bean directly.

---

## Acceptance criteria

**platform-api and parent pom**
- [ ] `casehub-platform-parent` pom.xml `<dependencyManagement>` has direct entries for `cloudevents-core:4.0.1`, `cloudevents-api:4.0.1`, `cloudevents-json-jackson:4.0.1`
- [ ] `casehub-platform-api` pom.xml has `cloudevents-core` compile dep (no version — managed by parent)
- [ ] `io.cloudevents.CloudEvent` visible to all consumers of `casehub-platform-api` transitively
- [ ] `EndpointRegistered` record in `io.casehub.platform.api.endpoints`
- [ ] `EndpointRegistry` interface Javadoc states the `EndpointRegistered` firing obligation for non-no-op implementations
- [ ] `EndpointPropertyKeys.STREAM_EVENT_TYPE` constant added with full Javadoc
- [ ] `EndpointPropertyKeys.TOPIC` Javadoc updated to include `EndpointProtocol#AMQP` in the applies-to list (remove "only")
- [ ] `EndpointProtocol.AMQP` enum value added after `KAFKA`, before `MCP`, with `{@link EndpointPropertyKeys#TOPIC}` link form

**endpoints-memory changes**
- [ ] `endpoints-memory/pom.xml` adds `quarkus-maven-plugin` (goals: `generate-code` + `generate-code-tests` only, no `build`) and `quarkus-junit5` (test scope) — required for `@QuarkusTest` in `InMemoryEndpointRegistryEventTest`; `casehub-platform` is NOT needed in test scope (`Event<T>` is a CDI built-in and `InMemoryEndpointRegistry @Alternative @Priority(100)` is the only `EndpointRegistry` bean present)
- [ ] `InMemoryEndpointRegistry` uses constructor injection (`@Inject` constructor + package-private no-arg) with null guard in `register()`
- [ ] `InMemoryEndpointRegistry.register()` fires `EndpointRegistered.fireAsync()` with `whenComplete` WARN logging
- [ ] `NoOpEndpointRegistry.register()` remains a silent no-op — no event fired, no change to that class
- [ ] `InMemoryEndpointRegistryTest` (existing, plain JUnit5) — all 14 tests pass unchanged
- [ ] `InMemoryEndpointRegistryEventTest` (new, `@QuarkusTest`) — verifies `EndpointRegistered` fires on `register()` via `@ApplicationScoped` capture bean called directly by test

**New stream modules**
- [ ] All five stream modules added to root `pom.xml` `<modules>` in build order after `endpoints-memory`
- [ ] All five stream modules build and pass tests
- [ ] Each stream module activates by classpath presence
- [ ] Each stream module fires `Event<CloudEvent>.fireAsync()` with all required fields set per the `tenancyid` source table
- [ ] All three `EndpointRegistry.discover()` calls in stream modules (`streams-kafka`, `streams-poll`, `streams-camel`) use `TenancyConstants.DEFAULT_TENANT_ID` (not `PLATFORM_TENANT_ID`, not null)
- [ ] `streams-kafka` channel name configurable via `casehub.streams.kafka.channel` (default `casehub-kafka-stream`)
- [ ] `streams-kafka` does NOT observe `EndpointRegistered` — documented in Javadoc and pom `<description>`
- [ ] `streams-amqp` does NOT observe `EndpointRegistered` — documented in Javadoc and pom `<description>` (same static-channel constraint as `streams-kafka`)
- [ ] `streams-poll` catches per-endpoint HTTP exceptions, logs at WARN with URL, continues to next endpoint
- [ ] `streams-camel` uses "discover at startup + idempotent post-startup handler" design (no buffering, no synchronized block)
- [ ] `streams-camel` URI-change P0 constraint documented in Javadoc and pom `<description>`
- [ ] `streams-camel` startup-window gap documented in Javadoc
- [ ] `streams-webhook` dependencies: `quarkus-rest-jackson` + `cloudevents-json-jackson` (compile); P0 = structured format only (`application/cloudevents+json`); binary format deferred to P1+
- [ ] `streams-webhook` returns 202 Accepted on success; 404 Not Found when `streamId` resolves to empty `Optional`; 400 Bad Request when `Content-Type` is not `application/cloudevents+json`
- [ ] `streams-webhook` self-registration uses `TenancyConstants.PLATFORM_TENANT_ID` as `tenancyId` (platform-wide receiver, visible to all tenant queries)
- [ ] `streams-webhook` P0 URL path note (PLATFORM_TENANT_ID = "platform", DEFAULT_TENANT_ID = UUID) documented in Javadoc
- [ ] `streams-webhook` config `casehub.streams.webhook.public-url` required (fail-fast at startup if absent)
- [ ] CAMEL/KAFKA mutual exclusion documented in `streams-camel` and `streams-kafka` pom `<description>`

**Documentation**
- [ ] CLAUDE.md module table updated for all five `streams-*/` modules and `EndpointRegistered`/`STREAM_EVENT_TYPE` in the endpoints package description
- [ ] ARC42STORIES.MD §4 layer taxonomy updated with L10: Stream Ingestion (`streams-kafka/`, `streams-amqp/`, `streams-webhook/`, `streams-poll/`, `streams-camel/`)
- [ ] ARC42STORIES.MD §5 building block view updated with: (a) L10 container entries for all five stream modules, (b) missing L4 `endpoints-config` container entry (present in §4 taxonomy but absent from §5 diagram)

---

## Deferred (captured as issues)

| Concern | Issue |
|---------|-------|
| `StreamContext` SPI — async tenancy propagation in processing chains that don't hold a `CloudEvent` reference | P1.8 — define SPI alongside the working propagation mechanism (Mutiny context or CDI scope backed by request-local storage); no standalone no-op SPI in P0 |
| Multi-tenant stream discovery — all three stream `discover()` calls use `DEFAULT_TENANT_ID` in P0; in a multi-tenant deployment each tenant's endpoints land under their own tenancyId, which is never returned | P1+ — requires either `EndpointRegistry.discoverAll(...)` without tenant filter, or an injected tenant list that drives one `discover()` call per tenant |
| Per-endpoint poll intervals | P1+ in poll module |
| CloudEvent `subject` extraction from raw payload fields | P1+ |
| Stream source credential lookup (`credentialRef` on EndpointDescriptor) | P1+ |
| `streams-camel` route replacement on URI change | P1+ |
| Per-tenant poll scheduling for tenant-scoped `HTTP + QUERY` endpoints | P1+ |
| Webhook per-tenant routing with cleaner URLs (without sentinel UUID in path) | P1+ |
| `cloudevents-core` to casehub-parent BOM | parent#276 |
| `StateChangeEvent → CloudEvent` adapter | iot#19 |
| `MessageReceivedEvent → CloudEvent` adapter | qhorus#279 |
| `InboundMessage → CloudEvent` adapter | connectors#20 |
