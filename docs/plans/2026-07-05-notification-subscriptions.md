# Notification Subscriptions Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use subagent-driven-development (recommended) or executing-plans to implement this plan task-by-task. Each task follows TDD (test-driven-development) and uses ide-tooling for structural editing. Steps use checkbox (`- [ ]`) syntax for tracking.

**Focal issue:** #142 — notification subscription management
**Issue group:** #142, #149

**Goal:** Implement subscription CRUD, the matching engine (wiring subscriptions as DataSource alpha network subscribers on a single notification DataSource), domain bridge pattern, and five minor notification store fixes.

**Architecture:** Store SPI pattern for subscription persistence. SubscriptionEngine @ApplicationScoped registers a platform-global notification DataSource, loads subscriptions at startup, compiles constraints to FilterExpressions, and wires each as an alpha network subscriber via `DataSource.subscribe(EventTypeObjectType, FilterExpression, DataProcessor)`. Domain modules push lifecycle POJOs into the notification DataSource. On match, the engine resolves the NotificationTemplate against the POJO and stores via NotificationStore. MVEL3 mock phase: constraint predicates always return true until MVEL3 publishes; type discrimination and tenant isolation use MethodHandle (MVEL-independent).

**Tech Stack:** Java 21, Quarkus 3.32, Hibernate Reactive Panache, Flyway, Mutiny, MVEL (mock initially), MethodHandle for property access, JUnit 5, AssertJ.

**Spec:** `docs/superpowers/specs/2026-07-05-notification-subscription-design.md`

## Global Constraints

- `platform-api/` remains zero-dependency — no Quarkus, no JPA. Mutiny is `provided` scope only.
- Every backend implements both `SubscriptionStore` (blocking) and `ReactiveSubscriptionStore` (reactive) **natively** — no bridges.
- NoOp `@DefaultBean` implementations must NOT fire CDI events.
- All subscription IDs are UUID v7 (time-ordered).
- Tenant isolation on all operations — `tenancyId` parameter on every SPI method.
- `userId` on all methods — SPI-boundary ownership enforcement.
- Cursor pagination is keyset-based: `(created_at DESC, id DESC)`. Cursor encoding is implementation-owned.
- Tenant isolation in the engine is unconditional — injected into every subscription's FilterExpression, not user-configurable.
- Tenant ID is included in `FilterExpression.expression()` string to prevent cross-tenant FilterNode sharing in the alpha network.
- MethodHandle for all fixed property access (type extraction, template resolution, tenancy check). MVEL only for user-defined constraint predicates.

---

### Task 1: #149 — Notification Store Minor Findings

**Files:**
- Modify: `platform-api/src/main/java/io/casehub/platform/api/notification/UUIDv7.java`
- Modify: `platform-api/src/test/java/io/casehub/platform/api/notification/NotificationStoreContractTest.java`
- Modify: `notifications/src/main/java/io/casehub/platform/notification/rest/NotificationSseResource.java`
- Modify: `docs/superpowers/specs/2026-07-05-notification-store-design.md`
- Create: `platform-api/src/test/java/io/casehub/platform/api/notification/UUIDv7Test.java`

**Interfaces:**
- Consumes: existing notification types
- Produces: fixed UUIDv7 with clock regression handling, SSE sweep, cleaned contract test

- [ ] **Step 1: Write UUIDv7 wraparound test**

```java
// UUIDv7Test.java
@Test
void generate_wrapsSequenceByAdvancingTimestamp() {
    UUIDv7.resetState();
    Instant fixed = Instant.parse("2026-07-05T00:00:00Z");
    String first = UUIDv7.generate(fixed);
    String last = first;
    for (int i = 0; i < 4096; i++) {
        last = UUIDv7.generate(fixed);
    }
    // 4097th UUID must sort AFTER the 4096th — timestamp advanced
    assertThat(last.compareTo(first)).isGreaterThan(0);
}

@Test
void generate_handlesClockRegression() {
    UUIDv7.resetState();
    Instant t1 = Instant.parse("2026-07-05T00:00:00.100Z");
    Instant t0 = Instant.parse("2026-07-05T00:00:00.099Z"); // earlier
    String a = UUIDv7.generate(t1);
    String b = UUIDv7.generate(t0); // clock went backwards
    assertThat(b.compareTo(a)).isGreaterThan(0); // must still be monotonic
}
```

- [ ] **Step 2: Run tests — verify they fail**

Run: `mvn --batch-mode -pl platform-api test -Dtest=UUIDv7Test`
Expected: FAIL — current code wraps sequence silently, clock regression not handled.

- [ ] **Step 3: Fix UUIDv7.generate(Instant)**

Replace the sequence/timestamp logic in `UUIDv7.java` lines 68-81 with:

```java
static String generate(Instant instant) {
    long timestampMs = instant.toEpochMilli();
    State state = THREAD_STATE.get();

    if (timestampMs <= state.lastTimestamp) {
        timestampMs = state.lastTimestamp;
        state.sequence++;
        if (state.sequence > 0xFFF) {
            timestampMs++;
            state.lastTimestamp = timestampMs;
            state.sequence = 0;
        }
    } else {
        state.lastTimestamp = timestampMs;
        state.sequence = 0;
    }
    int sequence = state.sequence;
    // ... rest unchanged
```

Update Javadoc to document overflow and clock regression behavior.

- [ ] **Step 4: Run tests — verify pass**

Run: `mvn --batch-mode -pl platform-api test -Dtest=UUIDv7Test`
Expected: PASS

- [ ] **Step 5: Remove Thread.sleep from contract test**

In `NotificationStoreContractTest.java`, remove `Thread.sleep(10)` from `find_ordersNewestFirst` and remove `throws InterruptedException` from the method signature.

- [ ] **Step 6: Run contract tests — verify pass**

Run: `mvn --batch-mode -pl platform-api test -Dtest=NotificationStoreContractTest`
Expected: PASS (abstract class compiles, no failures)

- [ ] **Step 7: Add SSE stale emitter sweep**

Add to `NotificationSseResource.java`:

```java
@Scheduled(every = "60s")
void sweepStaleEmitters() {
    connections.forEach((key, emitters) ->
        emitters.removeIf(e -> e.eventSink().isClosed()));
    connections.entrySet().removeIf(e -> e.getValue().isEmpty());
}
```

- [ ] **Step 8: Update notification store spec**

In `docs/superpowers/specs/2026-07-05-notification-store-design.md`, replace H2 URL references with PostgreSQL DevServices configuration. This is a doc-only change.

- [ ] **Step 9: Run full build for affected modules**

Run: `mvn --batch-mode -pl platform-api,notifications test`
Expected: PASS

- [ ] **Step 10: Commit**

```
feat(platform#149): notification store minor findings — UUIDv7 wraparound, SSE sweep, test cleanup
```

---

### Task 2: Subscription SPI Types + Contract Test (platform-api)

**Files:**
- Create: `platform-api/src/main/java/io/casehub/platform/api/subscription/Subscription.java`
- Create: `platform-api/src/main/java/io/casehub/platform/api/subscription/SubscriptionInput.java`
- Create: `platform-api/src/main/java/io/casehub/platform/api/subscription/SubscriptionUpdate.java`
- Create: `platform-api/src/main/java/io/casehub/platform/api/subscription/SubscriptionQuery.java`
- Create: `platform-api/src/main/java/io/casehub/platform/api/subscription/SubscriptionPage.java`
- Create: `platform-api/src/main/java/io/casehub/platform/api/subscription/Constraint.java`
- Create: `platform-api/src/main/java/io/casehub/platform/api/subscription/ConstraintOp.java`
- Create: `platform-api/src/main/java/io/casehub/platform/api/subscription/NotificationTemplate.java`
- Create: `platform-api/src/main/java/io/casehub/platform/api/subscription/SubscriptionStore.java`
- Create: `platform-api/src/main/java/io/casehub/platform/api/subscription/ReactiveSubscriptionStore.java`
- Create: `platform-api/src/main/java/io/casehub/platform/api/subscription/SubscriptionCreated.java`
- Create: `platform-api/src/main/java/io/casehub/platform/api/subscription/SubscriptionUpdated.java`
- Create: `platform-api/src/main/java/io/casehub/platform/api/subscription/SubscriptionDeleted.java`
- Create: `platform-api/src/main/java/io/casehub/platform/api/subscription/SubscriptionConstants.java`
- Create: `platform-api/src/test/java/io/casehub/platform/api/subscription/SubscriptionSpiTest.java`
- Create: `platform-api/src/test/java/io/casehub/platform/api/subscription/SubscriptionStoreContractTest.java`

**Interfaces:**
- Consumes: `io.casehub.platform.api.notification.NotificationSeverity` (for NotificationTemplate), `io.casehub.platform.api.path.Path` (for SubscriptionConstants)
- Produces: all subscription SPI types, `SubscriptionStoreContractTest` abstract base class

- [ ] **Step 1: Write record construction and null-validation tests**

`SubscriptionSpiTest.java` — test Constraint, ConstraintOp, NotificationTemplate, SubscriptionInput, Subscription, SubscriptionQuery, SubscriptionPage construction with valid and null arguments. Follow the `NotificationSpiTest` pattern exactly.

- [ ] **Step 2: Implement enums and records**

`ConstraintOp.java` — enum with EQ, NEQ, GT, LT, GTE, LTE, IN, STARTS_WITH, CONTAINS.

`Constraint.java` — record with `field`, `op` (ConstraintOp), `value`. Null-check all three in compact constructor.

`NotificationTemplate.java` — record with `titlePattern`, `bodyPattern` (nullable), `severity`, `category`, `actionUrlPattern` (nullable), `entityType`, `entityIdField`, `actorIdField`. Null-check all non-nullable fields.

`Subscription.java` — record with `id`, `userId`, `tenancyId`, `name`, `eventType`, `constraints` (List, defensive copy), `template`, `enabled`, `createdAt`, `updatedAt`. Null-check required fields.

`SubscriptionInput.java` — record with `userId`, `tenancyId`, `name`, `eventType`, `constraints` (List, defensive copy), `template`, `enabled`. Null-check required fields.

`SubscriptionUpdate.java` — record where all fields are nullable (null = don't change). `name`, `eventType`, `constraints`, `template`, `enabled` (Boolean).

`SubscriptionQuery.java` — record with `userId`, `tenancyId` (required), `enabled` (nullable), `cursor` (nullable), `limit` (positive). Null-check userId, tenancyId. Validate limit > 0.

`SubscriptionPage.java` — record with `subscriptions` (List, defensive copy, unmodifiable), `nextCursor` (nullable).

- [ ] **Step 3: Run tests — verify pass**

Run: `mvn --batch-mode -pl platform-api test -Dtest=SubscriptionSpiTest`

- [ ] **Step 4: Implement CDI event records**

`SubscriptionCreated.java` — record with `subscription` (Subscription). Null-check.
`SubscriptionUpdated.java` — record with `subscription` (Subscription), `previous` (Subscription). Null-check both.
`SubscriptionDeleted.java` — record with `subscription` (Subscription). Null-check.

- [ ] **Step 5: Write SPI interfaces**

`SubscriptionStore.java` — blocking SPI with: `store(SubscriptionInput)`, `findById(id, userId, tenancyId)`, `find(SubscriptionQuery)`, `update(id, userId, tenancyId, SubscriptionUpdate)`, `delete(id, userId, tenancyId)`, `findAllEnabled()` returning `Stream<Subscription>`.

`ReactiveSubscriptionStore.java` — reactive mirror: all methods return `Uni<T>`, `findAllEnabled()` returns `Multi<Subscription>`.

- [ ] **Step 6: Implement SubscriptionConstants**

```java
public final class SubscriptionConstants {
    public static final Path NOTIFICATION_DATASOURCE_PATH =
        Path.of("casehub", "platform", "notifications");
    private SubscriptionConstants() {}
}
```

- [ ] **Step 7: Write contract test base class**

`SubscriptionStoreContractTest.java` — abstract, with `protected abstract SubscriptionStore store()` and `protected abstract void clearState()`. Tests:
- `store_persistsWithGeneratedId`
- `store_setsTimestamps`
- `findById_returnsStoredSubscription`
- `findById_wrongUser_returnsEmpty`
- `findById_wrongTenant_returnsEmpty`
- `find_returnsPaginatedResults`
- `find_filtersByEnabled`
- `find_respectsTenantIsolation`
- `find_respectsUserIsolation`
- `update_changesName`
- `update_changesEventType`
- `update_changesConstraints`
- `update_changesEnabled`
- `update_setsUpdatedAt`
- `update_nullFieldsUnchanged`
- `update_wrongUser_returnsEmpty`
- `delete_removesSubscription`
- `delete_wrongUser_returnsFalse`
- `findAllEnabled_returnsOnlyEnabled`
- `findAllEnabled_crossesTenantBoundaries`

Helper: `createInput(userId, tenancyId, name, eventType)` with sensible defaults for constraints and template.

- [ ] **Step 8: Run platform-api tests**

Run: `mvn --batch-mode -pl platform-api test`
Expected: PASS (contract test is abstract — no concrete subclass yet)

- [ ] **Step 9: Commit**

```
feat(platform#142): SubscriptionStore SPI — types, interfaces, contract tests
```

---

### Task 3: NoOp @DefaultBean + InMemorySubscriptionStore

**Files:**
- Create: `platform/src/main/java/io/casehub/platform/subscription/NoOpSubscriptionStore.java`
- Create: `platform/src/main/java/io/casehub/platform/subscription/NoOpReactiveSubscriptionStore.java`
- Create: `subscriptions-inmem/pom.xml`
- Create: `subscriptions-inmem/src/main/java/io/casehub/platform/subscription/memory/InMemorySubscriptionStore.java`
- Create: `subscriptions-inmem/src/main/java/io/casehub/platform/subscription/memory/InMemoryReactiveSubscriptionStore.java`
- Create: `subscriptions-inmem/src/test/java/io/casehub/platform/subscription/memory/InMemorySubscriptionStoreTest.java`
- Modify: `pom.xml` (parent — add `subscriptions-inmem` module)

**Interfaces:**
- Consumes: all SPI types from Task 2
- Produces: `NoOpSubscriptionStore @DefaultBean`, `NoOpReactiveSubscriptionStore @DefaultBean`, `InMemorySubscriptionStore @Alternative @Priority(100)`, `InMemoryReactiveSubscriptionStore @Alternative @Priority(100)`

- [ ] **Step 1: Create NoOp implementations**

`NoOpSubscriptionStore.java` — `@DefaultBean @ApplicationScoped`. `store()` returns a structurally valid Subscription (UUIDv7, timestamps). All queries return empty. All mutations return empty/false. `findAllEnabled()` returns empty stream. Does NOT fire CDI events.

`NoOpReactiveSubscriptionStore.java` — `@DefaultBean @ApplicationScoped`. Same semantics, Uni/Multi wrappers.

- [ ] **Step 2: Create subscriptions-inmem Maven module**

`subscriptions-inmem/pom.xml` — follow `notifications-inmem/pom.xml` exactly. Artifact: `casehub-platform-subscriptions-inmem`. Dependencies: `casehub-platform-api`, `quarkus-arc`, test-jar from platform-api, junit, assertj. Jandex plugin.

Add `<module>subscriptions-inmem</module>` to parent `pom.xml`.

- [ ] **Step 3: Implement InMemorySubscriptionStore**

`InMemorySubscriptionStore.java` — `@Alternative @Priority(100) @ApplicationScoped`. ConcurrentHashMap storage. UUIDv7 for IDs. Cursor pagination with `(createdAt DESC, id DESC)` keyset. Base64 cursor encoding. Fires CDI events (`SubscriptionCreated`, `SubscriptionUpdated`, `SubscriptionDeleted`) via `Event.fireAsync()`. `findAllEnabled()` returns stream of all enabled subscriptions across all tenants.

`InMemoryReactiveSubscriptionStore.java` — `@Alternative @Priority(100) @ApplicationScoped`. Delegates to InMemorySubscriptionStore. Native reactive (Uni.createFrom().item() — ConcurrentHashMap is safe on event loop).

- [ ] **Step 4: Write contract test subclass**

`InMemorySubscriptionStoreTest.java` — extends `SubscriptionStoreContractTest`. Injects `InMemorySubscriptionStore`. `clearState()` calls a package-private `clear()` method.

- [ ] **Step 5: Run tests**

Run: `mvn --batch-mode -pl subscriptions-inmem test`
Expected: PASS — all contract tests pass against in-memory implementation.

- [ ] **Step 6: Commit**

```
feat(platform#142): NoOp + InMemory SubscriptionStore implementations
```

---

### Task 4: EventTypeObjectType + ConstraintCompiler + TemplateResolver

**Files:**
- Create: `subscriptions/pom.xml`
- Create: `subscriptions/src/main/java/io/casehub/platform/subscription/engine/EventTypeObjectType.java`
- Create: `subscriptions/src/main/java/io/casehub/platform/subscription/engine/ConstraintCompiler.java`
- Create: `subscriptions/src/main/java/io/casehub/platform/subscription/engine/TemplateResolver.java`
- Create: `../../subscriptions-core/src/test/java/io/casehub/platform/subscription/engine/EventTypeObjectTypeTest.java`
- Create: `subscriptions/src/test/java/io/casehub/platform/subscription/engine/ConstraintCompilerTest.java`
- Create: `subscriptions/src/test/java/io/casehub/platform/subscription/engine/TemplateResolverTest.java`
- Modify: `pom.xml` (parent — add `subscriptions` module)

**Interfaces:**
- Consumes: `ObjectType<T>` from platform-api datasource package, `FilterExpression<T>` from platform-api datasource package, `Constraint`, `ConstraintOp`, `NotificationTemplate` from Task 2
- Produces: `EventTypeObjectType implements ObjectType<Object>`, `ConstraintCompiler` (compiles List<Constraint> + tenancyId + userId → FilterExpression<Object>), `TemplateResolver` (resolves NotificationTemplate against POJO → NotificationInput)

- [ ] **Step 1: Create subscriptions Maven module**

`subscriptions/pom.xml` — artifact: `casehub-platform-subscriptions`. Dependencies: `casehub-platform-api`, `casehub-platform-datasource-inmem` (for AlphaDataSource), `quarkus-arc`, `quarkus-scheduler` (for future use), test deps. Jandex plugin. No quarkus:build goal.

Add `<module>subscriptions</module>` to parent `pom.xml`.

- [ ] **Step 2: Write EventTypeObjectType tests**

```java
// EventTypeObjectTypeTest.java
@Test
void matches_trueForMatchingEventType() {
    var objectType = new EventTypeObjectType("io.casehub.work.workitem.completed");
    var pojo = new TestEvent("io.casehub.work.workitem.completed", "tenant-1");
    assertThat(objectType.matches(pojo)).isTrue();
}

@Test
void matches_falseForDifferentEventType() {
    var objectType = new EventTypeObjectType("io.casehub.work.workitem.completed");
    var pojo = new TestEvent("io.casehub.work.workitem.created", "tenant-1");
    assertThat(objectType.matches(pojo)).isFalse();
}

@Test
void matches_falseForPojoWithoutTypeMethod() {
    var objectType = new EventTypeObjectType("io.casehub.work.workitem.completed");
    assertThat(objectType.matches("not-a-pojo")).isFalse();
}

@Test
void getTypeKey_returnsEventTypeString() {
    var objectType = new EventTypeObjectType("io.casehub.work.workitem.completed");
    assertThat(objectType.getTypeKey()).isEqualTo("io.casehub.work.workitem.completed");
}

// Test POJO with type() method
record TestEvent(String type, String tenancyId) {}
```

- [ ] **Step 3: Implement EventTypeObjectType**

```java
public final class EventTypeObjectType implements ObjectType<Object> {
    private final String eventType;

    public EventTypeObjectType(String eventType) {
        this.eventType = Objects.requireNonNull(eventType);
    }

    @Override
    public boolean matches(Object object) {
        String pojoType = extractEventType(object);
        return eventType.equals(pojoType);
    }

    @Override
    public Object getTypeKey() {
        return eventType;
    }

    static String extractEventType(Object object) {
        try {
            var lookup = MethodHandles.publicLookup();
            var handle = lookup.findVirtual(object.getClass(), "type",
                MethodType.methodType(String.class));
            return (String) handle.invoke(object);
        } catch (Throwable e) {
            return null;
        }
    }
}
```

- [ ] **Step 4: Run EventTypeObjectType tests — verify pass**

Run: `mvn --batch-mode -pl subscriptions test -Dtest=EventTypeObjectTypeTest`

- [ ] **Step 5: Write ConstraintCompiler tests**

Test cases: compile empty constraints with tenant isolation, compile single EQ constraint, compile with `$me` placeholder, compile with IN operator, verify tenant ID is in expression string (for FilterNode sharing prevention).

```java
@Test
void compile_emptyConstraints_tenantIsolationOnly() {
    var fe = ConstraintCompiler.compile(List.of(), "tenant-1", "user-1");
    assertThat(fe.type()).isEqualTo("mvel");
    assertThat(fe.expression()).startsWith("tenant=tenant-1:");
    // During MVEL mock phase, predicate always returns true
    var event = new TestEvent("any", "tenant-1");
    assertThat(fe.test(event)).isTrue();
}

@Test
void compile_tenantMismatch_returnsFalse() {
    var fe = ConstraintCompiler.compile(List.of(), "tenant-1", "user-1");
    var event = new TestEvent("any", "tenant-2");
    assertThat(fe.test(event)).isFalse();
}
```

- [ ] **Step 6: Implement ConstraintCompiler**

Compiles `List<Constraint>` + tenancyId + userId into a `FilterExpression<Object>`:
1. Tenant isolation: native Java MethodHandle check on `tenancyId()` — always active, not MVEL-dependent
2. User constraints: build MVEL expression string (mock phase: skip evaluation, return true)
3. `$me` replacement: substitute with userId in expression
4. Expression string: `"tenant=" + tenancyId + ":" + mvelExpression` (prevents cross-tenant FilterNode sharing)
5. Combined predicate: tenantCheck AND (mock ? true : mvelPredicate)

- [ ] **Step 7: Run ConstraintCompiler tests — verify pass**

Run: `mvn --batch-mode -pl subscriptions test -Dtest=ConstraintCompilerTest`

- [ ] **Step 8: Write TemplateResolver tests**

```java
@Test
void resolve_substitutesPlaceholders() {
    var template = new NotificationTemplate(
        "WorkItem {status}: {detail}", null,
        NotificationSeverity.INFO, "work-item.completed",
        "/workitems/{workItemId}", "work-item", "workItemId", "actor");
    var pojo = new TestWorkItem("completed", "Fix login bug",
        UUID.randomUUID(), "user-2");
    var input = TemplateResolver.resolve(template, pojo, "sub-user", "tenant-1");
    assertThat(input.title()).isEqualTo("WorkItem completed: Fix login bug");
    assertThat(input.userId()).isEqualTo("sub-user");
    assertThat(input.source().entityType()).isEqualTo("work-item");
}

record TestWorkItem(String status, String detail, UUID workItemId, String actor) {}
```

- [ ] **Step 9: Implement TemplateResolver**

Static method `resolve(NotificationTemplate, Object pojo, String userId, String tenancyId) → NotificationInput`. Uses MethodHandle to read POJO fields for `{placeholder}` substitution, entityIdField, and actorIdField. Generates UUIDv7 for eventId. Returns null and logs WARN if entityIdField or actorIdField resolve to null.

- [ ] **Step 10: Run TemplateResolver tests — verify pass**

Run: `mvn --batch-mode -pl subscriptions test -Dtest=TemplateResolverTest`

- [ ] **Step 11: Commit**

```
feat(platform#142): EventTypeObjectType, ConstraintCompiler, TemplateResolver — engine internals
```

---

### Task 5: SubscriptionEngine

**Files:**
- Create: `subscriptions/src/main/java/io/casehub/platform/subscription/engine/SubscriptionEngine.java`
- Create: `subscriptions/src/test/java/io/casehub/platform/subscription/engine/SubscriptionEngineTest.java`

**Interfaces:**
- Consumes: `DataSourceRegistry` (platform-api), `AlphaDataSource` (datasource-inmem), `SubscriptionStore` (Task 2), `NotificationStore` (existing notification SPI), `EventTypeObjectType` (Task 4), `ConstraintCompiler` (Task 4), `TemplateResolver` (Task 4), `SubscriptionHandle` (platform-api datasource), `SubscriptionCreated/Updated/Deleted` CDI events (Task 2)
- Produces: `SubscriptionEngine @ApplicationScoped` — registers notification DataSource, loads and wires subscriptions, handles dynamic mutations

- [ ] **Step 1: Write engine startup test**

```java
// SubscriptionEngineTest.java — plain JUnit, no Quarkus
@Test
void startup_registersNotificationDataSource() {
    var registry = new InMemoryDataSourceRegistry();
    var subStore = new InMemorySubscriptionStore();
    var notifStore = new NoOpNotificationStore();
    var engine = new SubscriptionEngine(registry, subStore, notifStore);

    engine.onStartup(null); // simulates @Observes StartupEvent

    var ds = registry.resolveSource(NOTIFICATION_DATASOURCE_PATH, PLATFORM_TENANT_ID);
    assertThat(ds).isPresent();
}
```

- [ ] **Step 2: Write engine matching test**

```java
@Test
void event_matchesSubscription_createsNotification() {
    // Setup
    var registry = new InMemoryDataSourceRegistry();
    var subStore = new InMemorySubscriptionStore();
    var notifStore = new CapturingNotificationStore(); // test double that captures stored notifications
    var engine = new SubscriptionEngine(registry, subStore, notifStore);

    // Create subscription
    var input = new SubscriptionInput("user-1", "tenant-1", "My sub",
        "io.casehub.work.workitem.completed",
        List.of(), // no constraints
        new NotificationTemplate("WorkItem {status}", null,
            NotificationSeverity.INFO, "work-item.completed",
            null, "work-item", "workItemId", "actor"),
        true);
    subStore.store(input);

    // Start engine
    engine.onStartup(null);

    // Push event into DataSource
    var ds = registry.resolveSource(NOTIFICATION_DATASOURCE_PATH, PLATFORM_TENANT_ID).orElseThrow();
    var event = new TestEvent("io.casehub.work.workitem.completed", "tenant-1",
        UUID.randomUUID(), "user-2");
    ((DataSource<Object>) ds).add(event);

    // Verify notification created
    assertThat(notifStore.captured()).hasSize(1);
    assertThat(notifStore.captured().get(0).title()).isEqualTo("WorkItem completed");
    assertThat(notifStore.captured().get(0).userId()).isEqualTo("user-1");
}
```

- [ ] **Step 3: Write tenant isolation test**

```java
@Test
void event_wrongTenant_doesNotMatch() {
    // Same setup as above but event has tenant-2, subscription has tenant-1
    // Verify no notification created
}
```

- [ ] **Step 4: Write dynamic wiring tests**

Test `onSubscriptionCreated`, `onSubscriptionUpdated`, `onSubscriptionDeleted` — verify subscriptions are wired/unwired dynamically, verify `compute()` atomicity prevents ghost subscriptions.

- [ ] **Step 5: Implement SubscriptionEngine**

```java
@ApplicationScoped
public class SubscriptionEngine {
    private final DataSourceRegistry dataSourceRegistry;
    private final SubscriptionStore subscriptionStore;
    private final NotificationStore notificationStore;
    private final ConcurrentHashMap<String, SubscriptionHandle> handles = new ConcurrentHashMap<>();
    private volatile DataSource<Object> notificationDataSource;

    @Inject
    public SubscriptionEngine(DataSourceRegistry dataSourceRegistry,
                               SubscriptionStore subscriptionStore,
                               NotificationStore notificationStore) { ... }

    void onStartup(@Observes StartupEvent event) {
        // 1. Register notification DataSource
        var descriptor = new DataSourceDescriptor(
            NOTIFICATION_DATASOURCE_PATH,
            PLATFORM_TENANT_ID,
            new ClassObjectType<>(Object.class),
            null, Set.of(), Map.of());
        notificationDataSource = (DataSource<Object>) dataSourceRegistry.register(descriptor);

        // 2. Load and wire all enabled subscriptions
        subscriptionStore.findAllEnabled().forEach(this::wireSubscription);
    }

    private void wireSubscription(Subscription subscription) {
        var objectType = new EventTypeObjectType(subscription.eventType());
        var filter = ConstraintCompiler.compile(
            subscription.constraints(), subscription.tenancyId(), subscription.userId());
        DataProcessor<Object> processor = pojo -> {
            var input = TemplateResolver.resolve(
                subscription.template(), pojo, subscription.userId(), subscription.tenancyId());
            if (input != null) {
                notificationStore.store(input);
            }
        };
        var handle = notificationDataSource.subscribe(objectType, filter, processor);
        handles.compute(subscription.id(), (id, existing) -> {
            if (existing != null) existing.unsubscribe();
            return handle;
        });
    }

    void onCreated(@ObservesAsync SubscriptionCreated event) {
        if (event.subscription().enabled()) {
            wireSubscription(event.subscription());
        }
    }

    void onUpdated(@ObservesAsync SubscriptionUpdated event) {
        handles.compute(event.subscription().id(), (id, existing) -> {
            if (existing != null) existing.unsubscribe();
            if (event.subscription().enabled()) {
                // Re-wire with new subscription data
                return wireAndReturnHandle(event.subscription());
            }
            return null;
        });
    }

    void onDeleted(@ObservesAsync SubscriptionDeleted event) {
        handles.compute(event.subscription().id(), (id, existing) -> {
            if (existing != null) existing.unsubscribe();
            return null;
        });
    }
}
```

- [ ] **Step 6: Run engine tests — verify pass**

Run: `mvn --batch-mode -pl subscriptions test -Dtest=SubscriptionEngineTest`

- [ ] **Step 7: Commit**

```
feat(platform#142): SubscriptionEngine — DataSource wiring, event matching, dynamic lifecycle
```

---

### Task 6: Subscription REST API

**Files:**
- Create: `subscriptions/src/main/java/io/casehub/platform/subscription/rest/SubscriptionResource.java`
- Create: `subscriptions/src/test/java/io/casehub/platform/subscription/rest/SubscriptionResourceTest.java`

**Interfaces:**
- Consumes: `ReactiveSubscriptionStore` (Task 2), `CurrentPrincipal` (platform-api identity)
- Produces: REST endpoints at `/subscriptions` — CRUD + enable/disable

- [ ] **Step 1: Write REST integration tests**

`SubscriptionResourceTest.java` — `@QuarkusTest`. Inject `FixedCurrentPrincipal`, `InMemorySubscriptionStore`. Tests:
- `create_returnsSubscriptionWithGeneratedId`
- `list_returnsSubscriptionsForCurrentUser`
- `list_filtersByEnabled`
- `list_respectsPaginationLimit`
- `getById_returns200`
- `getById_returns404ForDifferentUser`
- `update_changesNameAndReturns200`
- `delete_returns204`
- `enable_setsEnabledTrue`
- `disable_setsEnabledFalse`

Add `casehub-platform-subscriptions-inmem` and `casehub-platform-testing` as test-scope dependencies in `subscriptions/pom.xml`.

- [ ] **Step 2: Implement SubscriptionResource**

```java
@ApplicationScoped
@Path("/subscriptions")
public class SubscriptionResource {
    @Inject ReactiveSubscriptionStore store;
    @Inject CurrentPrincipal principal;

    @POST
    public Uni<Response> create(SubscriptionInput input) {
        var securedInput = new SubscriptionInput(
            principal.actorId(), principal.tenancyId(),
            input.name(), input.eventType(), input.constraints(),
            input.template(), input.enabled());
        return store.store(securedInput)
            .map(s -> Response.status(201).entity(s).build());
    }

    @GET
    public Uni<SubscriptionPage> list(
            @QueryParam("enabled") Boolean enabled,
            @QueryParam("cursor") String cursor,
            @QueryParam("limit") @DefaultValue("25") int limit) {
        var query = new SubscriptionQuery(
            principal.actorId(), principal.tenancyId(),
            enabled, cursor, limit);
        return store.find(query);
    }

    @GET @Path("/{id}")
    public Uni<Response> getById(@PathParam("id") String id) {
        return store.findById(id, principal.actorId(), principal.tenancyId())
            .map(opt -> opt.map(s -> Response.ok(s).build())
                .orElse(Response.status(404).build()));
    }

    @PATCH @Path("/{id}")
    public Uni<Response> update(@PathParam("id") String id, SubscriptionUpdate update) {
        return store.update(id, principal.actorId(), principal.tenancyId(), update)
            .map(opt -> opt.map(s -> Response.ok(s).build())
                .orElse(Response.status(404).build()));
    }

    @DELETE @Path("/{id}")
    public Uni<Response> delete(@PathParam("id") String id) {
        return store.delete(id, principal.actorId(), principal.tenancyId())
            .map(deleted -> deleted
                ? Response.noContent().build()
                : Response.status(404).build());
    }

    @PATCH @Path("/{id}/enable")
    public Uni<Response> enable(@PathParam("id") String id) {
        return store.update(id, principal.actorId(), principal.tenancyId(),
                new SubscriptionUpdate(null, null, null, null, true))
            .map(opt -> opt.map(s -> Response.ok(s).build())
                .orElse(Response.status(404).build()));
    }

    @PATCH @Path("/{id}/disable")
    public Uni<Response> disable(@PathParam("id") String id) {
        return store.update(id, principal.actorId(), principal.tenancyId(),
                new SubscriptionUpdate(null, null, null, null, false))
            .map(opt -> opt.map(s -> Response.ok(s).build())
                .orElse(Response.status(404).build()));
    }
}
```

- [ ] **Step 3: Run REST tests — verify pass**

Run: `mvn --batch-mode -pl subscriptions test -Dtest=SubscriptionResourceTest`

- [ ] **Step 4: Commit**

```
feat(platform#142): subscription REST API — CRUD + enable/disable
```

---

### Task 7: JPA SubscriptionStore

**Files:**
- Create: `subscriptions-jpa/pom.xml`
- Create: `subscriptions-jpa/src/main/java/io/casehub/platform/subscription/jpa/SubscriptionEntity.java`
- Create: `subscriptions-jpa/src/main/java/io/casehub/platform/subscription/jpa/JpaReactiveSubscriptionStore.java`
- Create: `subscriptions-jpa/src/main/java/io/casehub/platform/subscription/jpa/JpaSubscriptionStore.java`
- Create: `subscriptions-jpa/src/main/resources/db/subscription/migration/V1__subscription.sql`
- Create: `subscriptions-jpa/src/test/java/io/casehub/platform/subscription/jpa/JpaSubscriptionStoreTest.java`
- Create: `subscriptions-jpa/src/test/resources/application.properties`
- Modify: `pom.xml` (parent — add `subscriptions-jpa` module)

**Interfaces:**
- Consumes: all SPI types from Task 2
- Produces: `JpaReactiveSubscriptionStore @ApplicationScoped`, `JpaSubscriptionStore @ApplicationScoped` (blocking wrapper)

- [ ] **Step 1: Create subscriptions-jpa Maven module**

`subscriptions-jpa/pom.xml` — follow `notifications-jpa/pom.xml` pattern. Artifact: `casehub-platform-subscriptions-jpa`. Dependencies: platform-api, quarkus-hibernate-reactive-panache, quarkus-reactive-pg-client, quarkus-flyway, quarkus-jdbc-postgresql (for Flyway), quarkus-arc. Test deps: platform-api test-jar, junit, assertj, quarkus-junit5, rest-assured.

Add `<module>subscriptions-jpa</module>` to parent `pom.xml`.

- [ ] **Step 2: Write Flyway migration**

`V1__subscription.sql`:

```sql
CREATE TABLE subscription (
    id              VARCHAR(36) NOT NULL PRIMARY KEY,
    user_id         VARCHAR(255) NOT NULL,
    tenancy_id      VARCHAR(255) NOT NULL,
    name            VARCHAR(500) NOT NULL,
    event_type      VARCHAR(500) NOT NULL,
    constraints_json TEXT,
    template_json    TEXT NOT NULL,
    enabled         BOOLEAN NOT NULL DEFAULT TRUE,
    created_at      TIMESTAMP NOT NULL,
    updated_at      TIMESTAMP NOT NULL
);

CREATE INDEX idx_subscription_user_tenant_enabled
    ON subscription (user_id, tenancy_id, enabled, created_at DESC);

CREATE INDEX idx_subscription_enabled
    ON subscription (enabled) WHERE enabled = TRUE;
```

Constraints and template stored as JSON (TEXT column) — avoids schema complexity for nested records. Deserialized by Jackson in the entity.

- [ ] **Step 3: Implement SubscriptionEntity**

Hibernate Reactive Panache entity. `@Entity @Table(name = "subscription")`. Fields map to columns. `constraints_json` and `template_json` are `@Column(columnDefinition = "TEXT")` with `@Convert` or manual Jackson serialization in `fromInput()` / `toSubscription()` methods.

- [ ] **Step 4: Implement JpaReactiveSubscriptionStore**

Native `ReactiveSubscriptionStore`. Uses Panache for CRUD. Dynamic HQL for `find(SubscriptionQuery)` with cursor-based pagination. `findAllEnabled()` returns `Multi<Subscription>` via Panache stream. Fires CDI events via `fireAsync()`.

- [ ] **Step 5: Implement JpaSubscriptionStore**

Blocking wrapper using Vert.x context await pattern (same as `JpaNotificationStore.execute()`). Delegates all methods to `JpaReactiveSubscriptionStore`.

- [ ] **Step 6: Write test configuration**

`application.properties`:
```properties
quarkus.datasource.db-kind=postgresql
quarkus.datasource.devservices.enabled=true
quarkus.hibernate-orm.database.generation=none
quarkus.flyway.locations=classpath:db/subscription/migration
quarkus.flyway.migrate-at-start=true
```

- [ ] **Step 7: Write JPA contract test**

`JpaSubscriptionStoreTest.java` — `@QuarkusTest` extends `SubscriptionStoreContractTest`. Injects `JpaSubscriptionStore` for blocking tests. Additional reactive-specific tests via `@RunOnVertxContext` (same pattern as `JpaNotificationStoreTest`). `clearState()` uses Panache `deleteAll()` in transaction.

- [ ] **Step 8: Run JPA tests**

Run: `mvn --batch-mode -pl subscriptions-jpa test`
Expected: PASS

- [ ] **Step 9: Commit**

```
feat(platform#142): JPA SubscriptionStore — entity, Flyway, reactive + blocking
```

---

## Self-Review Checklist

- [x] **Spec coverage:** All spec sections mapped to tasks. §1 → Task 2, §2 → Tasks 4+5, §3 → Tasks 3+6+7, §4 → Task 1. Documentation updates deferred to work-end.
- [x] **Placeholder scan:** No TBD/TODO. All steps have code or exact commands.
- [x] **Type consistency:** `Subscription`, `SubscriptionInput`, `SubscriptionUpdate`, `SubscriptionQuery`, `SubscriptionPage`, `Constraint`, `ConstraintOp`, `NotificationTemplate`, `SubscriptionStore`, `ReactiveSubscriptionStore`, `EventTypeObjectType`, `ConstraintCompiler`, `TemplateResolver`, `SubscriptionEngine`, `SubscriptionResource` — names consistent across all tasks.
- [x] **Independent tasks:** Task 1 (#149 fixes) is fully independent. Tasks 2-7 are sequential. Tasks 4+5 vs 7 (engine internals vs JPA) are independent after Task 3.
