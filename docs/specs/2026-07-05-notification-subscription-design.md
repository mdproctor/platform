# Notification Subscription Management — #142 + #149

**Date:** 2026-07-05
**Issues:** casehubio/platform#142 (subscription management), casehubio/platform#149 (notification store minor findings)
**Epic:** casehubio/platform#147 (Phase 2)

---

## Overview

Notification subscription management — users specify event types and constraints, the engine matches incoming domain events and creates notifications. The subscription SPI exposes a clean user-facing model (event type + constraints + delivery). DataSources, alpha networks, and filter compilation are implementation — invisible to the API.

Also includes five minor findings from the #135 code review (#149).

---

## Design Decisions

### Filters inline in subscriptions

A subscription IS its filter + delivery config. They share a lifecycle. Separating them into independent entities creates artificial complexity (referential integrity, cascade updates, orphans) with no architectural benefit. The Drools precedent applies: rules own their conditions, the alpha network shares nodes at runtime as an optimization, nobody stores conditions as separate entities. See brainstorming discussion for full first-principles analysis.

### POJO-level filtering, not CloudEvent

Users subscribe to domain lifecycle events — WorkItem status changes, case completions, SLA breaches. These originate as typed POJOs (`WorkItemLifecycleEvent`, `CaseLifecycleEvent`) with typed fields (`priority()`, `assigneeId()`, `status()`). The existing CloudEvent pathway serializes these POJOs to JSON and wraps them in a transport envelope — infrastructure for external consumers (Kafka, AMQP, webhook). The subscription engine is an internal consumer and should work with the live POJO, not a serialized transport artifact.

Event chain from origin:
```
WorkItemService.complete() → emitter.emit(WorkItemLifecycleEvent)
  → CDI fireAsync(WorkItemLifecycleEvent)    ← POJO is live here
    ├→ WorkCloudEventAdapter                 ← serializes for external transport
    └→ WorkNotificationBridge                ← pushes POJO into notification DataSource (new)
```

### Structured constraints

Field/operator/value triples with an enum of operators. Explicit, validatable, serialisable, easy to compile into MVEL predicates on POJOs. Expression-based filters (raw MVEL strings) can be added later without breaking the SPI — the constraint model extends, it doesn't need replacing.

### Single notification DataSource

One platform-global DataSource for all notification-eligible events, registered with `PLATFORM_TENANT_ID`. Domain modules push POJOs into it. The alpha network's TypeNode discriminates by event type string (via `EventTypeObjectType`), not by Java class — a single POJO class can produce multiple event types. All subscription evaluation shares the same root DataSource. Adding a new event source = a ~5-line bridge in the domain module.

---

## Section 1: Subscription Data Model (platform-api)

All types in `io.casehub.platform.api.subscription`. Pure Java, zero dependencies — Tier 1.

### Core Records

```java
public record Subscription(
    String id,                          // UUIDv7
    String userId,                      // subscriber
    String tenancyId,
    String name,                        // user-facing label: "My urgent work items"
    String eventType,                   // event type: "io.casehub.work.workitem.completed"
    List<Constraint> constraints,       // field conditions (implicit AND)
    NotificationTemplate template,      // how to build the notification
    boolean enabled,
    Instant createdAt,
    Instant updatedAt
) {}
```

- **`eventType`** — single event type string. Exact match. Each subscription targets one event kind — constraints and template fields are type-specific, so multi-type subscriptions are incoherent (a constraint on `workItemId` fails silently on non-WorkItem POJOs). Glob/prefix matching can be added later without breaking the SPI since matching logic is in the engine, not the record.
- **`constraints`** — implicit AND. All must pass. Sufficient for notification use cases. OR/grouping deferred — the structured model extends naturally.

```java
public record Constraint(
    String field,           // POJO property path: "status", "priority", "assigneeId"
    ConstraintOp op,        // comparison operator
    String value            // comparison value, stringified
) {}

public enum ConstraintOp {
    EQ, NEQ, GT, LT, GTE, LTE, IN, STARTS_WITH, CONTAINS
}
```

- **`value` is String** — the engine coerces to the POJO field's actual type at evaluation time (MVEL handles this). `IN` takes comma-separated values. `$me` is a placeholder the engine resolves to the subscription's `userId` at compile time.

### Notification Template

When a subscription matches, the engine creates a `NotificationInput`. The template specifies how:

```java
public record NotificationTemplate(
    String titlePattern,              // "WorkItem {status}: {detail}"
    String bodyPattern,               // nullable — "{resolution}"
    NotificationSeverity severity,    // INFO, WARNING, URGENT
    String category,                  // notification category: "work-item.completed"
    String actionUrlPattern,          // nullable — "/workitems/{workItemId}"
    String entityType,                // static per template: "work-item"
    String entityIdField,             // POJO field name: "workItemId"
    String actorIdField               // POJO field name: "actor"
) {}
```

- `{fieldName}` placeholders resolve against the POJO at match time. Simple string interpolation — the engine reads the field via `MethodHandle` (same property access mechanism as `EventTypeObjectType.extractEventType`), calls `toString()`, substitutes. Null or missing fields → placeholder removed. MVEL is NOT used for property access — only for user-defined constraint predicate compilation.
- `entityType` is a static string. `entityIdField` and `actorIdField` are POJO property names the engine reads at match time via `MethodHandle`.
- `eventId` for `NotificationSource` is auto-generated (UUIDv7) by the engine — not in the template.

### Input / Update / Query Records

```java
public record SubscriptionInput(
    String userId,
    String tenancyId,
    String name,
    String eventType,
    List<Constraint> constraints,
    NotificationTemplate template,
    boolean enabled
) {}

public record SubscriptionUpdate(
    String name,                        // nullable = don't change
    String eventType,                   // nullable = don't change
    List<Constraint> constraints,       // nullable = don't change
    NotificationTemplate template,      // nullable = don't change
    Boolean enabled                     // nullable = don't change
) {}

public record SubscriptionQuery(
    String userId,
    String tenancyId,
    Boolean enabled,                    // nullable = all
    String cursor,                      // nullable = start
    int limit
) {}

public record SubscriptionPage(
    List<Subscription> subscriptions,
    String nextCursor                   // null = no more pages
) {}
```

### Store SPI

Dual blocking + reactive, native implementations (per platform protocol `spi-reactive-blocking-io`):

```java
public interface SubscriptionStore {
    Subscription store(SubscriptionInput input);
    Optional<Subscription> findById(String id, String userId, String tenancyId);
    SubscriptionPage find(SubscriptionQuery query);
    Optional<Subscription> update(String id, String userId, String tenancyId, SubscriptionUpdate update);
    boolean delete(String id, String userId, String tenancyId);
    Stream<Subscription> findAllEnabled();             // engine bootstrap — paged, all tenants
}

public interface ReactiveSubscriptionStore {
    Uni<Subscription> store(SubscriptionInput input);
    Uni<Optional<Subscription>> findById(String id, String userId, String tenancyId);
    Uni<SubscriptionPage> find(SubscriptionQuery query);
    Uni<Optional<Subscription>> update(String id, String userId, String tenancyId, SubscriptionUpdate update);
    Uni<Boolean> delete(String id, String userId, String tenancyId);
    Multi<Subscription> findAllEnabled();              // engine bootstrap — paged, all tenants
}
```

User ownership enforced at the SPI boundary — `userId` and `tenancyId` are required on all read/write operations.

### CDI Events

```java
public record SubscriptionCreated(Subscription subscription) {}
public record SubscriptionUpdated(Subscription subscription, Subscription previous) {}
public record SubscriptionDeleted(Subscription subscription) {}
```

Store implementations MUST fire these on mutation. No-op @DefaultBean MUST NOT fire events.

### Constants

```java
public final class SubscriptionConstants {
    public static final Path NOTIFICATION_DATASOURCE_PATH =
        Path.of("casehub", "platform", "notifications");

    private SubscriptionConstants() {}
}
```

Domain modules use this to resolve the notification DataSource and push POJOs.

---

## Section 2: Subscription Engine

`@ApplicationScoped` in the `subscriptions/` module. Bridges the user-facing subscription model to runtime event matching and notification delivery via the DataSource alpha network.

### Lifecycle

**Startup:**
1. Register the notification DataSource at `NOTIFICATION_DATASOURCE_PATH` (platform-global `AlphaDataSource<Object>`, registered with `PLATFORM_TENANT_ID`)
2. Load all enabled subscriptions via paged iteration over `SubscriptionStore.findAllEnabled()`
3. For each subscription, compile to a `FilterExpression` and wire as a subscriber on the notification DataSource (see Subscription Wiring)

**Runtime:**
- Domain bridges push POJOs into the notification DataSource via `dataSource.add(event)`
- The alpha network evaluates type discrimination → filter chain → fan-out to matching subscribers
- Each matching subscriber creates `NotificationInput` → `NotificationStore.store()`

**Dynamic wiring** (all mutations use `handles.compute(subscriptionId, ...)` for per-key atomicity):
- `@ObservesAsync SubscriptionCreated` → `compute`: compile and wire new subscriber, return new handle
- `@ObservesAsync SubscriptionUpdated` → `compute`: unwire existing handle, compile and wire new, return new handle
- `@ObservesAsync SubscriptionDeleted` → `compute`: unwire existing handle, return null (removes entry)

`@ObservesAsync` observers can run concurrently on CDI's managed executor. Without `compute()`, a rapid update-then-delete sequence creates a race: the update thread puts a new handle after the delete thread removes the entry, leaving a ghost subscription wired in the alpha network indefinitely. `ConcurrentHashMap.compute()` serializes operations per key, preventing this.

### Subscription Wiring

For each enabled subscription, the engine:

1. Creates an `EventTypeObjectType(subscription.eventType())` — a custom `ObjectType<Object>` where `matches(pojo)` extracts the event type string from the POJO (via `type()` method convention) and compares to the subscription's event type. `getTypeKey()` returns the event type string, so subscriptions for the same event type share a TypeNode in the alpha network.

2. Compiles constraints into a `FilterExpression<Object>`:
   - **Tenant isolation** (unconditional, first in AND chain): `pojo.tenancyId == subscription.tenancyId()` — ensures events from other tenants never match. Not a user-configurable constraint — the engine always injects this. The tenant ID is included in the `FilterExpression.expression()` string (see Constraint Compilation) so that the alpha network creates separate FilterNodes per tenant — without this, `TypeNode.filtersMatch()` would merge subscriptions from different tenants with identical user constraints into a single FilterNode, causing both cross-tenant leaks and silent data loss.
   - **User constraints**: each `Constraint(field, op, value)` compiles to an MVEL expression
   - **`$me` resolution**: `$me` replaced with `subscription.userId()` at compile time
   - All checks AND'd into a single `FilterExpression("mvel", "tenant=<tenancyId>:<mvelExpression>", compiledPredicate)`
   - `FilterExpression` enables alpha network node sharing within the same tenant — identical tenant + constraint expressions share evaluation. Cross-tenant sharing is prevented by the tenant prefix in the expression string.

3. Creates a `DataProcessor<Object>` that resolves the template against the POJO and stores the notification (see Template Resolution)

4. Calls `notificationDataSource.subscribe(objectType, filterExpression, processor)` — the alpha network handles type discrimination, filter evaluation, and fan-out

5. Tracks the returned `SubscriptionHandle` via `handles.compute(subscriptionId, (id, existing) -> newHandle)` — atomic per subscription ID, prevents race conditions between concurrent `@ObservesAsync` mutations (see Dynamic wiring)

### MVEL3 Mock Phase

MVEL3 is not yet published to Maven Central (see DataSource alpha network spec §4). The initial implementation ships a mock evaluator where all filter evaluations return `true`. During the mock phase:

- **Type discrimination still works** — `EventTypeObjectType.matches()` uses `MethodHandle`, not MVEL
- **Tenant isolation still works** — the tenancy check is compiled as native Java, not MVEL
- **Template resolution still works** — `{field}` substitution, `entityIdField`, and `actorIdField` all use `MethodHandle` for property access, not MVEL. Notifications are produced correctly during the mock phase.
- **User constraints are not evaluated** — every subscription matching the event type and tenant fires regardless of constraint values. MVEL is used ONLY for compiling user-defined `Constraint` predicates — this is the single component that is mocked.
- **Testing impact** — contract tests verify type matching, template resolution, and notification creation end-to-end. Constraint evaluation tests should be `@Disabled` with a note referencing the MVEL3 dependency

When MVEL3 is published, constraints compile to real predicates. No SPI or consumer changes required.

### Constraint Compilation

At wiring time, each subscription's constraints compile to a single `FilterExpression<Object>`:

1. **Tenant isolation** — unconditional `pojo.tenancyId == subscription.tenancyId()`, compiled as native Java (not MVEL), not user-removable
2. **Field constraints** — each `Constraint(field, op, value)` compiles to an MVEL expression string
3. **`$me` resolution** — `$me` replaced with the subscription's `userId` at compile time, baked into the predicate
4. **Expression string construction** — `"tenant=" + tenancyId + ":" + mvelExpression`. The tenant ID prefix is critical: the alpha network's `TypeNode.filtersMatch()` compares `FilterExpression.type()` + `FilterExpression.expression()` to decide node sharing. Without the tenant prefix, subscriptions from different tenants with identical user constraints would share a single `FilterNode` — and `FilterNode` evaluates ONE predicate for ALL subscribers under it. This means: (a) cross-tenant leak when the shared predicate passes for tenant-A's event and fans out to tenant-B's processor, and (b) silent data loss when it fails and suppresses tenant-B's legitimate match.
5. **Combined** — all checks AND'd into one `FilterExpression("mvel", "tenant=<tenancyId>:<mvelExpression>", compiledPredicate)`

MVEL is used exclusively for compiling user-defined constraint predicates — no compile-time dependency on domain event classes. All other POJO property access (type extraction, template field resolution, entity/actor ID extraction, tenancy check) uses `MethodHandle` and is MVEL-independent. Node sharing is correct within the same tenant: subscriptions with the same tenant ID and same user constraint expression share a FilterNode (the optimization the alpha network provides). Cross-tenant subscriptions always get separate FilterNodes.

### Template Resolution

On match, the engine builds `NotificationInput`:

| NotificationInput field | Source |
|---|---|
| `userId` | `subscription.userId()` |
| `tenancyId` | `subscription.tenancyId()` |
| `title` | `template.titlePattern()` with `{field}` substitution from POJO |
| `body` | `template.bodyPattern()` with `{field}` substitution from POJO |
| `category` | `template.category()` |
| `severity` | `template.severity()` |
| `actionUrl` | `template.actionUrlPattern()` with `{field}` substitution from POJO |
| `source.eventId` | auto-generated UUIDv7 |
| `source.entityType` | `template.entityType()` |
| `source.entityId` | POJO field named by `template.entityIdField()` |
| `source.actorId` | POJO field named by `template.actorIdField()` |

**Null field handling for structural fields:** `entityIdField` and `actorIdField` resolve POJO fields via `MethodHandle` for `NotificationSource` construction (which requires all four fields non-null). If resolution returns null for either:
- The engine skips notification creation for this match and logs WARN with subscription ID, event type, and field name
- Fail-safe — a notification without source coordinates is meaningless for audit correlation
- `{field}` placeholders in title/body/actionUrl use existing behavior: null or missing → placeholder removed

### Event Type Discrimination

The engine defines `EventTypeObjectType` — a custom `ObjectType<Object>` in the `subscriptions/` module:

```java
public final class EventTypeObjectType implements ObjectType<Object> {
    private final String eventType;
    private static final MethodType TYPE_METHOD = MethodType.methodType(String.class);

    @Override public boolean matches(Object object) {
        String pojoType = extractEventType(object);  // Java reflection: object.type()
        return eventType.equals(pojoType);
    }

    @Override public Object getTypeKey() {
        return eventType;  // alpha network shares TypeNode for same event type
    }

    private static String extractEventType(Object object) {
        // MethodHandle lookup — NOT MVEL. Independent of MVEL3 availability.
        // Returns null if the POJO has no type() method (fail-safe: no match).
    }
}
```

`extractEventType` uses Java `MethodHandle` lookup (not MVEL) to call the POJO's `type()` method. This is deliberate — type discrimination must work independently of MVEL3 availability, including during the mock phase. MVEL is used only for user-defined constraint compilation, not for the fixed `type()` convention.

The `type()` method convention is enforced at the bridge — each domain bridge pushes a POJO that exposes `type()`. POJOs without a `type()` method return null from `extractEventType`, causing `matches()` to return false (fail-safe). This convention is documented but not compile-time enforced — adding an interface or annotation is deferred (#153).

---

## Section 3: Module Structure

### New Modules

| Module | Artifact | Purpose |
|---|---|---|
| `subscriptions-inmem/` | `casehub-platform-subscriptions-inmem` | @Alternative @Priority(100) volatile InMemorySubscriptionStore + InMemoryReactiveSubscriptionStore. ConcurrentHashMap. No quarkus:build goal. Do NOT combine with subscriptions-jpa in production |
| `subscriptions-jpa/` | `casehub-platform-subscriptions-jpa` | @ApplicationScoped JPA SubscriptionStore + ReactiveSubscriptionStore. PostgreSQL, Flyway at `classpath:db/subscription/migration`. Reactive native (Hibernate Reactive Panache) + blocking via Vert.x context await. No quarkus:build goal |
| `subscriptions/` | `casehub-platform-subscriptions` | SubscriptionEngine (@ApplicationScoped) + REST API (@Path("/subscriptions")). Depends on platform-api + MVEL for constraint compilation. No quarkus:build goal |

### Changes to Existing Modules

| Module | Change |
|---|---|
| `platform-api/` | Add `io.casehub.platform.api.subscription` package — all SPI types from Section 1 |
| `platform/` | Add `NoOpSubscriptionStore` + `NoOpReactiveSubscriptionStore` @DefaultBean |

### CDI Priority Ladder

| CDI resolution | Bean |
|---|---|
| `@DefaultBean` | `NoOpSubscriptionStore` (platform/) — silent no-op |
| `@Alternative @Priority(100)` | `InMemorySubscriptionStore` (subscriptions-inmem/) — volatile, test + ephemeral |
| `@ApplicationScoped` | `JpaSubscriptionStore` (subscriptions-jpa/) — production |

### Flyway

Module-scoped path: `classpath:db/subscription/migration`. V1: `V1__subscription.sql`.

### Domain Bridge Pattern

Each domain module that produces subscribable events adds a CDI observer that pushes the existing POJO into the notification DataSource:

```java
@ApplicationScoped
public class WorkNotificationBridge {
    @Inject DataSourceRegistry registry;

    void onLifecycle(@ObservesAsync WorkItemLifecycleEvent event) {
        registry.resolveSource(NOTIFICATION_DATASOURCE_PATH, PLATFORM_TENANT_ID)
            .ifPresent(ds -> ((DataSource<Object>) ds).add(event));
    }
}
```

Bridge is a no-op when the subscription module isn't on classpath — `resolveSource()` returns `Optional.empty()`. The `(DataSource<Object>) ds` cast is unchecked (`resolveSource()` returns `DataSource<?>`) — this is a known limitation of the `DataSourceRegistry` API (no typed resolution). Safe in practice because the notification DataSource is registered as `AlphaDataSource<Object>`.

**Domain modules requiring bridges (filed as follow-up issues):**

| Module | Event type | POJO |
|---|---|---|
| casehub-work | `io.casehub.work.workitem.*` | `WorkItemLifecycleEvent` |
| casehub-work | `io.casehub.work.group.*` | `WorkItemGroupLifecycleEvent` |
| casehub-engine | `io.casehub.engine.case.*` | Case lifecycle events |
| casehub-iot | `io.casehub.iot.state_change.*` | `StateChangeEvent` |

### REST API

`@Path("/subscriptions")` in the `subscriptions/` module. `CurrentPrincipal`-enforced user isolation.

| Method | Path | Operation |
|---|---|---|
| `POST` | `/subscriptions` | Create |
| `GET` | `/subscriptions` | List (paginated, filterable by enabled) |
| `GET` | `/subscriptions/{id}` | Get by id |
| `PATCH` | `/subscriptions/{id}` | Update (partial) |
| `DELETE` | `/subscriptions/{id}` | Delete |
| `PATCH` | `/subscriptions/{id}/enable` | Enable |
| `PATCH` | `/subscriptions/{id}/disable` | Disable |

---

## Section 4: #149 — Notification Store Minor Findings

Five discrete fixes from the #135 code review.

### 1. UUIDv7 sequence counter wraparound

**Problem:** `UUIDv7.java` line 75: `state.sequence = (state.sequence + 1) & 0xFFF`. At 4096 same-millisecond UUIDs on the same thread, the 12-bit sequence wraps to 0, breaking monotonicity silently.

**Fix:** When sequence wraps, advance the timestamp by 1ms (RFC 9562 §6.2 recommended approach):

```java
if (timestampMs <= state.lastTimestamp) {
    timestampMs = state.lastTimestamp;    // never go backwards
    state.sequence = (state.sequence + 1) & 0xFFF;
    if (state.sequence == 0) {           // wrapped
        timestampMs++;
        state.lastTimestamp = timestampMs;
    }
} else {
    state.lastTimestamp = timestampMs;
    state.sequence = 0;
}
```

The `<=` check (not `==`) prevents clock regression — after sequence wraparound advances the timestamp to T+1, the next call within the same real millisecond sees `timestampMs=T < state.lastTimestamp=T+1` and correctly stays at T+1 instead of regressing to T. Also handles NTP step-back and VM migration per RFC 9562 §6.2. Add Javadoc documenting both overflow and clock regression behavior.

### 2. Thread.sleep(10) in contract test

**Problem:** `NotificationStoreContractTest.find_ordersNewestFirst` uses `Thread.sleep(10)` between two stores. UUIDv7's sequence counter already guarantees monotonic ordering within the same millisecond.

**Fix:** Remove `Thread.sleep(10)` and `throws InterruptedException`. The `(createdAt DESC, id DESC)` ordering uses the UUID as tiebreaker, and UUIDv7 ensures notif2's UUID sorts after notif1's.

### 3. SSE stale emitter sweep

**Problem:** `NotificationPushService` cleans up closed emitters lazily — only when the next event tries to send. Stale emitters accumulate indefinitely between events for low-traffic users.

**Fix:** Add a `@Scheduled(every = "60s")` sweep that removes emitters where `isClosed()` returns true. Same `removeEmitter()` logic already exists.

### 4. Cursor encoding — no change

In-memory uses `:` separator, JPA uses `|`. Correct by design — cursors are opaque and implementation-owned. Close as "by design."

### 5. Spec test config section stale

**Problem:** The spec references H2 URLs for JPA test configuration. Actual implementation uses PostgreSQL DevServices (H2 reactive emulation fails with Hibernate Reactive Panache on `Instant` fields — GE-20260705-2aa4c8).

**Fix:** Update `docs/superpowers/specs/2026-07-05-notification-store-design.md` to replace H2 references with PostgreSQL DevServices configuration. Doc-only change.

---

## Documentation Updates

Implementation of this spec requires updates to:
- **ARC42STORIES.MD** — new chapter for the notification subscription subsystem (event matching via alpha network, subscription wiring lifecycle, domain bridge pattern)
- **PLATFORM.md** — capability ownership table: notification subscription management → `subscriptions/` module
- **APPLICATIONS.md** — if subscription management surfaces as an application-level capability

---

## Deferred

Each deferred item is filed as a GitHub issue under epic #147:

- **System subscriptions** — admin-defined subscriptions scoping to roles/groups/all users. Adds `scope` (USER | SYSTEM) and `targetType` fields. Deferred — the model supports adding these without breaking the SPI.
- **Expression-based filters** — raw MVEL/JQ expressions as an alternative to structured constraints. Adds a `SubscriptionFilter` variant alongside `List<Constraint>`.
- **Event type glob matching** — `io.casehub.work.workitem.*` prefix patterns. Engine-side change only — the SPI already stores strings.
- **External delivery** — connector fan-out (Slack, email) alongside in-app. Depends on #143 (user channel preferences).
- **DataSource deregistration lifecycle** — #138. Subscription cleanup when DataSources are deregistered.
- **Event type compile-time contract** — interface or annotation for the `type()` method convention on subscribable POJOs.
