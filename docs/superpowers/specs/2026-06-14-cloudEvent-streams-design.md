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

`cloudevents-json-jackson:4.0.1` is also pinned — same CloudEvents SDK release, needed by `streams-webhook` for `JsonFormat`/`EventFormatProvider` (structured CloudEvents HTTP binding deserialization).

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
 * {@code io.casehub.iot.temperature}. Stream modules that build CloudEvents from
 * raw payloads read this from {@link EndpointDescriptor#properties()} to set the
 * CloudEvent {@code type} field.
 *
 * <p>Applies to: {@link EndpointProtocol#KAFKA}, {@link EndpointProtocol#AMQP},
 * {@link EndpointProtocol#HTTP} ({@code streams-poll} only),
 * {@link EndpointProtocol#CAMEL}.
 *
 * <p><b>Not used by {@code streams-webhook}.</b> Webhook requests are already
 * structured CloudEvents; their {@code type} field is preserved from the incoming
 * event and not overridden by the descriptor.
 */
public static final String STREAM_EVENT_TYPE = "stream-event-type";
```

**`EndpointPropertyKeys.TOPIC` Javadoc update**: Add `{@link EndpointProtocol#AMQP}` to the applies-to list. The constant applies to both KAFKA and AMQP. Remove "only" from the current text.

**`EndpointPropertyKeys.URL` Javadoc update**: Add an explicit exclusion note: "KAFKA and AMQP are excluded — broker connection for both is Quarkus-managed via standard config (e.g. `kafka.bootstrap.servers`, `amqp-host`/`amqp-port`)."

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

**`InMemoryEndpointRegistryEventTest`** (new, `@QuarkusTest`): Verifies that `register()` completes without exception when `Event<EndpointRegistered>` is CDI-injected (the null guard passes, `fireAsync()` is invoked without NPE). It does **NOT** verify CDI async delivery — `@ObservesAsync` is silently not delivered in `@QuarkusTest` (GE-20260513-b15933), so observer invocation is untestable at this level. CDI async delivery is accepted as verified framework behavior; what the test validates is that the CDI wiring is correct and the event bus is called. Both test classes named explicitly in the acceptance criteria.

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

Two distinct construction paths depending on whether the incoming message is raw bytes or already a structured CloudEvent.

**Path 1 — build from scratch** (`streams-kafka`, `streams-amqp`, `streams-poll`, `streams-camel`):

Incoming payload is raw bytes (or a Kafka/AMQP message record). A new CloudEvent is constructed:

| Field | Value |
|-------|-------|
| `type` | `EndpointDescriptor.properties().get(STREAM_EVENT_TYPE)` |
| `source` | logical producer URI, module-specific (e.g. `/platform/streams/kafka/{topic}`) |
| `subject` | `null` — CloudEvents spec allows null for raw payloads; subject extraction from payload fields is P1+ |
| `id` | `UUID.randomUUID().toString()` |
| `time` | message timestamp if available in transport metadata, `OffsetDateTime.now()` otherwise |
| `data` | raw payload bytes |
| `tenancyid` extension | source varies by module — see below |

**Path 2 — preserve and enrich** (`streams-webhook` only):

Incoming payload is already a structured CloudEvent (`application/cloudevents+json`). The existing CloudEvent is preserved and only `tenancyid` is added:

| Field | Value |
|-------|-------|
| `type` | **preserved** from incoming event |
| `id` | **preserved** — consumer idempotency depends on stable (id, source) identity |
| `source` | **preserved** — sender's source URI; losing provenance would break observability |
| `time` | **preserved** |
| `subject` | **preserved** |
| `data` | **preserved** |
| `specversion` | **preserved** |
| all other extensions | **preserved** |
| `tenancyid` extension | `EndpointDescriptor.tenancyId()` — operator-set, overrides any caller-supplied value |

`buildCloudEvent(incomingEvent, descriptor)` for webhook: clone the incoming event and set/replace the `tenancyid` extension with `descriptor.tenancyId()`. The `STREAM_EVENT_TYPE` descriptor property is not used.

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

**Dependencies:** `quarkus-smallrye-reactive-messaging-kafka` only. `cloudevents-json-jackson` is NOT a dependency of `streams-kafka` — P0 always receives raw `byte[]` and builds CloudEvents from scratch; no native CloudEvents Kafka deserialization occurs.

**Static channel constraint:** `streams-kafka` does **NOT** observe `@ObservesAsync EndpointRegistered`. Channels are declared via `@Incoming` annotations, which are static — the set of consumed topics is fixed at build time. KAFKA stream descriptors must be registered before application startup; use `endpoints-config` YAML or ensure desiredstate reconciliation completes before the app processes messages. For runtime-dynamic Kafka topic subscriptions, use `streams-camel` with the Camel Kafka component instead. This constraint defines the boundary between `streams-kafka` (static) and `streams-camel` (dynamic) — see the CAMEL/KAFKA mutual exclusion section.

**P0 single-channel constraint:** One `@Incoming("casehub-kafka-stream")` channel per deployment (channel name configurable via `casehub.streams.kafka.channel`, default `casehub-kafka-stream`). Multiple Kafka topics can feed the same channel via `mp.messaging.incoming.casehub-kafka-stream.topics=topic1,topic2` (SmallRye multi-topic). For multiple independently-configured channels, deploy `streams-camel`.

**Channel→EndpointDescriptor correlation** at `@Observes StartupEvent`: reads `mp.messaging.incoming.${casehub.streams.kafka.channel}.topic` (or `.topics`) via MicroProfile Config, then calls `EndpointRegistry.discover(new EndpointQuery(TenancyConstants.DEFAULT_TENANT_ID, null, KAFKA, Set.of(RECEIVE)))` and matches by `TOPIC` property. `DEFAULT_TENANT_ID` is correct here: `matchesTenancy` returns descriptors registered under either `DEFAULT_TENANT_ID` (the deployment tenancy, as set by desiredstate) or `PLATFORM_TENANT_ID` (platform-global).

**Multi-topic splitting:** For multi-topic channels (`topics=topic1,topic2`), the topics value is split by comma; each element is matched independently against `EndpointDescriptor.properties().get(TOPIC)` in the discovery result. Each topic expects a separate registered descriptor. Topics with no matching descriptor log a warning and produce CloudEvents with `type = "io.casehub.platform.streams.kafka.unregistered"` to make the gap observable. An unsplit string `"topic1,topic2"` would never match any descriptor (descriptors register individual topic names) — splitting is mandatory.

**Message handling (P0 — always raw bytes):** Channel is typed `Message<byte[]>`. SmallRye Reactive Messaging channels are statically typed at build time — a single `@Incoming` channel cannot conditionally receive `Message<CloudEvent>` for CloudEvents-formatted records and `Message<byte[]>` for raw records. Always receive as `Message<byte[]>` and always build a CloudEvent from scratch using the descriptor's `STREAM_EVENT_TYPE` and message bytes as `data`. If the upstream producer sends a serialized CloudEvent, its bytes become the `data` field — the serialized form is preserved, but no attempt is made to parse or inspect it. Native CloudEvents Kafka passthrough (detect CloudEvent encoding, extract and re-fire the existing event) is P1+.

### `streams-amqp/`

**Dependencies:** `quarkus-smallrye-reactive-messaging-amqp` only. Same raw-bytes-only P0 approach as Kafka — no `cloudevents-json-jackson` dependency.

Broadly symmetric with `streams-kafka/`. Uses `quarkus-smallrye-reactive-messaging-amqp`. `EndpointProtocol.AMQP` + `EndpointCapability.RECEIVE` for discovery. `tenancyid` from AMQP message property `X-Tenancy-ID`; fallback to `EndpointDescriptor.tenancyId()`. Same static-channel constraint — does not observe `EndpointRegistered`. Same raw-bytes-always-build-from-scratch message handling as Kafka (P0).

**AMQP multi-address divergence from Kafka:** SmallRye AMQP reactive messaging connector does not support a plural-address config key equivalent to Kafka's `topics=a,b`. Each AMQP channel has exactly one address. The multi-topic comma-split logic specified for `streams-kafka` does not apply to `streams-amqp` — there is no multi-address config key to split. For multi-queue AMQP fan-in, use `streams-camel` with a Camel AMQP component. This is the point where `streams-amqp` diverges from the Kafka symmetric description.

### `streams-webhook/`

**Dependencies:** `quarkus-rest-jackson` (for JSON response body), `cloudevents-json-jackson` (compile — registers `JsonFormat` via ServiceLoader, enabling `EventFormatProvider` to resolve `application/cloudevents+json`).

**Why not JAX-RS auto-binding to `CloudEvent`:** `io.cloudevents.CloudEvent` is an interface; `cloudevents-json-jackson` provides a Jackson `JsonDeserializer`, not a JAX-RS `MessageBodyReader<CloudEvent>`. Quarkus REST's Jackson reader is registered for `application/json`, not `application/cloudevents+json` — even with the Jackson module registered, Quarkus REST cannot auto-bind a `CloudEvent` parameter for the `application/cloudevents+json` content type. Accept `byte[]` and deserialize manually using the CloudEvents SDK format API.

**P0 format scope:** Structured CloudEvents format (`application/cloudevents+json`) only. `@Consumes("application/cloudevents+json")` on the endpoint method causes Quarkus REST to enforce this automatically and return **415 Unsupported Media Type** for any other content type — no manual `Content-Type` inspection. Binary CloudEvents (`ce-*` headers + arbitrary body) deferred to P1+ (requires `cloudevents-http-basic` or manual header extraction).

**REST endpoint:**

```java
@Startup                               // forces eager @PostConstruct at application startup
@ApplicationScoped                     // class-level scope — eventFormat and self-registration shared
@Path("/streams/webhook")
public class WebhookResource {

    @Inject Event<CloudEvent> cloudEventBus;
    @Inject EndpointRegistry endpointRegistry;

    @ConfigProperty(name = "casehub.streams.webhook.public-url")
    String publicUrl;                  // required — Quarkus fails startup if absent (no defaultValue)

    private EventFormat eventFormat;

    @PostConstruct
    void init() {
        // Validate CloudEvents format registration
        eventFormat = EventFormatProvider.getInstance().resolveFormat(JsonFormat.CONTENT_TYPE);
        if (eventFormat == null) throw new IllegalStateException(
            "CloudEvents JSON format not registered — cloudevents-json-jackson missing from classpath");

        // Self-register the physical webhook receiver as a platform-global endpoint.
        // Uses PLATFORM_TENANT_ID so it is visible in all tenant-scoped discover() calls.
        // publicUrl is operator-set config; @ConfigProperty ensures fail-fast if absent.
        endpointRegistry.register(new EndpointDescriptor(
            Path.of("platform", "streams", "webhook"),
            TenancyConstants.PLATFORM_TENANT_ID,
            EndpointType.SERVICE,
            EndpointProtocol.HTTP,
            Map.of(EndpointPropertyKeys.URL, publicUrl),
            null,
            Set.of(EndpointCapability.RECEIVE)));
    }

    @POST
    @Path("/{tenancyId}/{streamId}")
    @Consumes("application/cloudevents+json")
    public Response receive(
            byte[] body,
            @PathParam("tenancyId") String tenancyIdFromPath,
            @PathParam("streamId") String streamId) {

        CloudEvent incoming;
        try {
            incoming = eventFormat.deserialize(body);
        } catch (RuntimeException e) {
            return Response.status(400).entity("Invalid CloudEvent body: " + e.getMessage()).build();
        }

        Optional<EndpointDescriptor> descriptor =
            endpointRegistry.resolve(Path.of("streams", streamId), tenancyIdFromPath);
        if (descriptor.isEmpty()) return Response.status(404).build();

        // Preserve all incoming fields; enrich with operator-set tenancyid
        CloudEvent enriched = CloudEventBuilder.from(incoming)
            .withExtension("tenancyid", descriptor.get().tenancyId())
            .build();
        cloudEventBus.fireAsync(enriched)
            .whenComplete((e, t) -> {
                if (t != null) LOG.warnf(t, "CloudEvent observer failed for stream %s", streamId);
            });
        return Response.accepted().build();
    }
}
```

`CloudEventBuilder.from(incoming)` copies all fields (type, id, source, time, subject, data, specversion, extensions) from the incoming event. `.withExtension("tenancyid", ...)` sets or replaces the tenancyid extension with the operator-authoritative value from the descriptor. Any caller-supplied `tenancyid` extension in the incoming event is overwritten — the descriptor is the authority.

`EventFormatProvider.getInstance()` uses ServiceLoader; `cloudevents-json-jackson` registers `JsonFormat` under `META-INF/services/io.cloudevents.core.format.EventFormat`. The null guard in `init()` fails fast at startup (not at first request) on a misconfigured classpath.

Responses:
- **202 Accepted** on success (`fireAsync()` is fire-and-forget; 200 would imply synchronous completion)
- **400 Bad Request** when the body passes the content-type gate but is not a valid structured CloudEvent (`eventFormat.deserialize()` throws)
- **404 Not Found** when `streamId` resolves to empty `Optional` (unregistered or misspelled — must be observable, not silently discarded)
- **415 Unsupported Media Type** when content type is not `application/cloudevents+json` (Quarkus REST enforces via `@Consumes`)

**Security property:** The `tenancyId` URL path parameter is used only for the registry lookup key — never as the CloudEvent `tenancyid` extension value. An external caller supplying a different `{tenancyId}` in the URL gets `descriptor.tenancyId()` (operator-set at registration) in the event, not the caller-supplied value.

**P0 URL path note:** `PLATFORM_TENANT_ID = "platform"` (the literal string, not a UUID). For standard single-tenant deployments where desiredstate registers endpoints under `DEFAULT_TENANT_ID`, the webhook URL includes that UUID (e.g. `.../webhook/278776f9-e1b0-46fb-9032-8bddebdcf9ce/my-stream`). For platform-global descriptors, the URL includes `platform` (e.g. `.../webhook/platform/my-stream`). Both expose internal registration details to external callers. Per-tenant webhook routing with cleaner URLs is P1+.

**Self-registration:** see `init()` in the code snippet above. `PLATFORM_TENANT_ID` is correct for the physical receiver — `matchesTenancy` returns this descriptor for any tenant query, so any consumer calling `discover()` finds it regardless of their own tenant. `@ConfigProperty` injection provides the fail-fast guarantee: if `casehub.streams.webhook.public-url` is absent from config, Quarkus throws `DeploymentException` at startup before `init()` is ever called.

Two distinct registry entries: the self-registration at `Path.of("platform", "streams", "webhook")` is the physical receiver. Each logical stream source is at `Path.of("streams", streamId)` registered by `casehub-ops`, not this module. Different paths, different semantics — not a conflict.

### `streams-poll/`

**Dependencies:** `quarkus-scheduler` only. No additional HTTP client dependency — use `java.net.http.HttpClient` (built into Java 11+, available on Java 21). The poll module issues HTTP GETs to arbitrary dynamic URLs stored in `EndpointDescriptor.properties().get(URL)` — a different URL per endpoint, discovered at runtime. MicroProfile REST Client (`quarkus-rest-client-jackson`) requires typed interfaces with a fixed base URL declared at configuration time and would require a new client instance per URL per poll cycle — over-engineered for a simple GET. `HttpClient.newHttpClient().send(request, BodyHandlers.ofByteArray())` is synchronous and appropriate for the blocking `@Scheduled` context.

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

**`pollAndFire(descriptor)` implementation note:** `java.net.http.HttpClient.send()` is declared `throws IOException, InterruptedException` (JDK source confirmed). Two distinct failure modes:

1. **Connection-level failures** (`IOException` from `send()` directly): DNS, TCP, timeout.
2. **HTTP error responses** (4xx/5xx): `send()` succeeds and returns `HttpResponse<byte[]>` with `response.statusCode()` set to the error code — no exception is thrown. Without explicit status code checking, the error body bytes would silently be treated as CloudEvent `data` and fired to observers.
3. **Thread interruption** (`InterruptedException` from `send()`): requires special handling to preserve the Quarkus scheduler's shutdown protocol.

The `pollAndFire()` lambda body in `poll()` calls `Consumer<T>.accept()`, which does not declare checked exceptions — `throws InterruptedException` on `pollAndFire()` won't compile. `InterruptedException` must be caught inside `pollAndFire()`, the thread re-interrupted (to preserve the interrupt signal for the scheduler), and re-thrown as `IOException`:

```java
// Class field — connection pool reused across poll intervals; per-call creation discards pool
private final HttpClient httpClient = HttpClient.newHttpClient();

void pollAndFire(EndpointDescriptor descriptor) throws IOException {
    String url = descriptor.properties().get(EndpointPropertyKeys.URL);
    HttpRequest request = HttpRequest.newBuilder().GET().uri(URI.create(url)).build();
    HttpResponse<byte[]> response;
    try {
        response = httpClient.send(request, BodyHandlers.ofByteArray());
    } catch (InterruptedException e) {
        Thread.currentThread().interrupt();                         // preserve interrupt flag
        throw new IOException("Poll interrupted for " + url, e);  // propagates to poll loop
    }
    if (response.statusCode() < 200 || response.statusCode() >= 300) {
        throw new IOException("Poll returned HTTP " + response.statusCode() + " for " + url);
    }
    // 2xx only: use response.body() as CloudEvent data
    CloudEvent event = buildCloudEvent(response.body(), descriptor);
    cloudEventBus.fireAsync(event)
        .whenComplete((e, t) -> {
            if (t != null) LOG.warnf(t, "CloudEvent observer failed for poll endpoint %s", url);
        });
}
```

All three failure paths — connection `IOException`, interrupt re-thrown as `IOException`, non-2xx `IOException` — are caught by the per-endpoint `try/catch` in `poll()`, logged at WARN, and iteration continues.

**Tenancy scope for discovery:** `DEFAULT_TENANT_ID` is correct — `matchesTenancy` returns descriptors under `DEFAULT_TENANT_ID` (desiredstate-registered endpoints) and `PLATFORM_TENANT_ID` (platform-global endpoints). `EndpointQuery` requires non-null `tenancyId`: `Objects.requireNonNull(tenancyId)` confirmed in source. Multi-tenant poll scheduling (discovering per-tenant `HTTP + QUERY` endpoints independently) is P1+.

**Per-endpoint failure handling:** `pollAndFire()` throws `IOException` for both connection failures (thrown by `send()` directly) and non-2xx responses (thrown explicitly after status code check). Both are caught by the poll loop's per-endpoint `try/catch`, logged at WARN with the failing URL, and execution continues to the next endpoint. Allowing exceptions to propagate would abort the entire `@Scheduled` invocation on the first bad endpoint.

**`tenancyid`:** `EndpointDescriptor.tenancyId()`.

**Note:** single global poll interval in P0. Per-endpoint intervals are P1+.

### `streams-camel/`

**Dependencies:** `camel-quarkus-core` + Camel components as needed by the consumer application. This module provides the route-building infrastructure; the consumer adds component deps (e.g. `camel-quarkus-kafka` for dynamic Kafka, `camel-quarkus-amqp` for AMQP via Camel).

**Bean scope — `@ApplicationScoped` required:** The processor bean holds application-lifetime state (`camelStarted`, `routedUris`) and receives CDI observer notifications across multiple threads. Without `@ApplicationScoped`, CDI would use `@Dependent` (the default for beans without a scope annotation), creating a new instance per injection point or event notification — `camelStarted` and `routedUris` would be per-invocation, breaking the startup design entirely. This applies to all five stream processor beans: each holds shared startup state or `@Scheduled`/observer callbacks that require application-scoped identity.

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

`CamelContext.addRoutes(RoutesBuilder)` is declared `throws Exception`. `addRoute()` wraps the call and rethrows as `RuntimeException`:

```java
void addRoute(EndpointDescriptor d) {
    String uri = d.properties().get(EndpointPropertyKeys.URL);
    try {
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
    } catch (Exception e) {
        throw new RuntimeException("Failed to add Camel route for URI: " + uri, e);
    }
}
```

**Exception propagation by call site:**
- In `onStartup` (synchronous `@Observes StartupEvent`): `RuntimeException` propagates out of `forEach`, aborting iteration. Remaining discovered descriptors in the stream are not processed — any CAMEL endpoints after the bad one get no routes. `camelStarted.set(true)` is never reached. Quarkus aborts startup with a clear error. Fail-fast is correct — a bad Camel URI in `endpoints-config` should prevent the application from starting, not silently skip the broken route.
- In `onEndpointRegistered` (async `@ObservesAsync`): `RuntimeException` propagates to the CDI async executor, which wraps it in `CompletionException` and completes the `CompletionStage` returned by `fireAsync()` exceptionally. The `whenComplete` in `InMemoryEndpointRegistry.register()` catches and WARN-logs it.

Routes added to a running Quarkus Camel context via `addRoutes()` start automatically in Quarkus Camel 3.x.

**P0 constraint — URI changes require restart:** `routedUris` tracks URI strings. If a CAMEL endpoint is re-registered (upsert) with the same URI, the idempotency check prevents a duplicate route. If re-registered with a *different* URI (operator changes the Camel endpoint expression), the old route continues running and a second route is added for the new URI — both active simultaneously. Changing the Camel endpoint URI on an already-routed descriptor requires application restart. Route replacement (stop old route, add new) is P1+. Document this constraint in the `streams-camel` pom `<description>` and in the module Javadoc.

**CAMEL vs KAFKA mutual exclusion (deployment constraint, not code):** `streams-camel` observes `EndpointProtocol.CAMEL` endpoints only, never `KAFKA`. `streams-kafka` uses `EndpointProtocol.KAFKA` static channels. Running both for the same Kafka topic from the same consumer group causes silent partial message loss — Kafka partition-splits between two consumer groups. Deployment rule: use exactly one for any given topic. Static topics known at deploy time → `streams-kafka`. Runtime-dynamic topics registered via desiredstate → `streams-camel` with Camel Kafka component.

---

## Testing

### Test strategy for `fireAsync()` in `@QuarkusTest`

GE-20260513-b15933: `@ObservesAsync` CDI events are silently not delivered in `@QuarkusTest`. Each stream module test must NOT rely on CDI observation to verify `CloudEvent` firing.

Required pattern: extract the CloudEvent construction logic into a package-private method on the processor bean, test that method directly, and verify the constructed `CloudEvent` fields without going through `fireAsync()`. This is the only reliably verifiable approach — CDI async delivery cannot be tested at the unit level.

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
- **`InMemoryEndpointRegistryEventTest`** (new, `@QuarkusTest`): injects the CDI-managed `InMemoryEndpointRegistry` and calls `register(endpoint)`. Verifies the call completes without NPE — CDI wiring is correct, the null guard is bypassed (non-null event bus injected by CDI), and `fireAsync()` is invoked. No capture bean or `CountDownLatch` needed — CDI async delivery is untestable in `@QuarkusTest` per GE-20260513-b15933; what is verified is CDI wiring correctness, not delivery.

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
- [ ] `EndpointPropertyKeys.URL` Javadoc updated to add explicit exclusion: "KAFKA and AMQP are excluded — broker connection for both is Quarkus-managed via standard config"
- [ ] `EndpointProtocol.AMQP` enum value added after `KAFKA`, before `MCP`, with `{@link EndpointPropertyKeys#TOPIC}` link form

**endpoints-memory changes**
- [ ] `endpoints-memory/pom.xml` adds `quarkus-maven-plugin` (goals: `generate-code` + `generate-code-tests` only, no `build`) and `quarkus-junit5` (test scope) — required for `@QuarkusTest` in `InMemoryEndpointRegistryEventTest`; `casehub-platform` is NOT needed in test scope (`Event<T>` is a CDI built-in and `InMemoryEndpointRegistry @Alternative @Priority(100)` is the only `EndpointRegistry` bean present)
- [ ] `InMemoryEndpointRegistry` uses constructor injection (`@Inject` constructor + package-private no-arg) with null guard in `register()`
- [ ] `InMemoryEndpointRegistry.register()` fires `EndpointRegistered.fireAsync()` with `whenComplete` WARN logging
- [ ] `NoOpEndpointRegistry.register()` remains a silent no-op — no event fired, no change to that class
- [ ] `InMemoryEndpointRegistryTest` (existing, plain JUnit5) — all 14 tests pass unchanged
- [ ] `InMemoryEndpointRegistryEventTest` (new, `@QuarkusTest`) — injects CDI-managed `InMemoryEndpointRegistry`, calls `register()`, verifies no NPE (CDI wiring correct, null guard bypassed); no capture bean or `CountDownLatch` (CDI async delivery untestable per GE-20260513-b15933)

**New stream modules**
- [ ] All five stream modules added to root `pom.xml` `<modules>` in build order after `endpoints-memory`
- [ ] All five stream modules build and pass tests
- [ ] Each stream module activates by classpath presence
- [ ] Each stream module fires `Event<CloudEvent>.fireAsync()` with all required fields set per the `tenancyid` source table
- [ ] All four `EndpointRegistry.discover()` calls in stream modules (`streams-kafka`, `streams-amqp`, `streams-poll`, `streams-camel`) use `TenancyConstants.DEFAULT_TENANT_ID` (not `PLATFORM_TENANT_ID`, not null)
- [ ] `streams-kafka` channel name configurable via `casehub.streams.kafka.channel` (default `casehub-kafka-stream`)
- [ ] `streams-kafka` splits multi-topic channel values by comma and matches each element independently against `EndpointDescriptor.properties().get(TOPIC)` — unsplit string lookup must not occur (Kafka-only; AMQP has no multi-address equivalent)
- [ ] All five stream processor beans are `@ApplicationScoped`; `streams-webhook` additionally requires `@Startup` (forces eager `@PostConstruct`; other four modules use `@Observes StartupEvent` which achieves the same eager initialization automatically)
- [ ] `streams-kafka` does NOT observe `EndpointRegistered` — documented in Javadoc and pom `<description>`
- [ ] `streams-amqp` does NOT observe `EndpointRegistered` — documented in Javadoc and pom `<description>` (same static-channel constraint as `streams-kafka`)
- [ ] `streams-poll` dependencies: `quarkus-scheduler` only (no additional HTTP dep — uses `java.net.http.HttpClient` from Java 21 stdlib)
- [ ] `streams-poll` declares `private final HttpClient httpClient = HttpClient.newHttpClient()` as a class field (not per-call — per-call discards connection pool on every GET)
- [ ] `streams-poll` `pollAndFire()` wraps `httpClient.send()` in a `try/catch (InterruptedException e)` that calls `Thread.currentThread().interrupt()` then rethrows as `IOException` — `HttpClient.send()` is declared `throws IOException, InterruptedException` (JDK confirmed); `Consumer.accept()` does not declare checked exceptions so `InterruptedException` cannot propagate; re-interrupting preserves the Quarkus scheduler shutdown protocol
- [ ] `streams-poll` `pollAndFire()` explicitly checks `response.statusCode()` and throws `IOException` on non-2xx — `HttpClient.send()` does NOT throw for 4xx/5xx; without this check the error body bytes would silently become CloudEvent data
- [ ] `streams-poll` per-endpoint `try/catch (Exception e)` catches all three failure paths (connection `IOException`, interrupt re-thrown as `IOException`, non-2xx `IOException`), logs at WARN with URL, continues to next endpoint
- [ ] `streams-camel` uses "discover at startup + idempotent post-startup handler" design (no buffering, no synchronized block)
- [ ] `streams-camel` `addRoute()` wraps `CamelContext.addRoutes()` in `try/catch(Exception e)` and rethrows as `RuntimeException`; in `onStartup` this aborts startup and skips remaining descriptors in the forEach (fail-fast; bad URI prevents startup); in `onEndpointRegistered` it propagates through CDI async executor to the `fireAsync().whenComplete` WARN logger
- [ ] `streams-camel` URI-change P0 constraint documented in Javadoc and pom `<description>`
- [ ] `streams-camel` startup-window gap documented in Javadoc
- [ ] `streams-webhook` dependencies: `quarkus-rest-jackson` + `cloudevents-json-jackson` (compile); P0 = structured format only (`application/cloudevents+json`); binary format deferred to P1+
- [ ] `streams-webhook` JAX-RS resource class is `@Startup @ApplicationScoped` (`@Startup` forces `@PostConstruct` at application startup, not on first HTTP request; without it `eventFormat` is null and the self-registration does not occur until the first request)
- [ ] `streams-webhook` accepts `byte[]`, deserializes via `EventFormatProvider`; `@Consumes("application/cloudevents+json")` enforced; no JAX-RS auto-binding to `CloudEvent` type
- [ ] `streams-webhook` resolves `EventFormat` at `@PostConstruct`; throws `IllegalStateException` if null (fail-fast on classpath misconfiguration)
- [ ] `streams-webhook` preserves incoming CloudEvent fields (type, id, source, time, subject, data, specversion, extensions) and sets/replaces only `tenancyid` from descriptor; does NOT override type with `STREAM_EVENT_TYPE`
- [ ] `streams-webhook` uses `CloudEventBuilder.from(incoming).withExtension("tenancyid", descriptor.tenancyId()).build()`
- [ ] `streams-webhook` returns: 202 Accepted (success); 400 Bad Request (body is not valid structured CloudEvent); 404 Not Found (`streamId` not in registry); 415 Unsupported Media Type (wrong `Content-Type`, via `@Consumes`)
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
| Multi-tenant stream discovery — all four stream `discover()` calls use `DEFAULT_TENANT_ID` in P0 (`streams-kafka`, `streams-amqp`, `streams-poll`, `streams-camel`); in a multi-tenant deployment each tenant's endpoints land under their own tenancyId, which is never returned | P1+ — requires either `EndpointRegistry.discoverAll(...)` without tenant filter, or an injected tenant list that drives one `discover()` call per tenant |
| Native CloudEvents Kafka passthrough (`streams-kafka`/`streams-amqp`) — detect CloudEvents encoding in incoming record and re-fire the existing event rather than wrapping bytes as data | P1+ — requires custom `KafkaRecordConsumer` with header inspection or two separate `@Incoming` channels; P0 always receives raw `byte[]` and builds from scratch |
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
