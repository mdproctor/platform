# Notification Store Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use hortora:subagent-driven-development (recommended) or hortora:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Implement the in-app notification store (#135) — SPI types, NoOp defaults, in-memory backend, JPA backend with H2 reactive testing, and REST + SSE API layer.

**Architecture:** Store SPI pattern — SPI in platform-api, @DefaultBean no-ops in platform/, InMemory @Alternative @Priority(100) in notifications-inmem/, JPA @ApplicationScoped in notifications-jpa/ (Hibernate Reactive Panache, both SPIs natively), REST + SSE in notifications/. CDI events on mutations drive SSE push. All backends implement both blocking and reactive SPIs natively.

**Tech Stack:** Java 21, Quarkus 3.32, Hibernate Reactive Panache, Flyway, H2 (PostgreSQL mode + reactive emulation), SSE via RESTEasy Reactive, Mutiny, JUnit 5.

**Spec:** `docs/superpowers/specs/2026-07-05-notification-store-design.md`

## Global Constraints

- `platform-api/` remains zero-dependency — no Quarkus, no JPA. Mutiny is `provided` scope only.
- Every backend implements both `NotificationStore` (blocking) and `ReactiveNotificationStore` (reactive) **natively** — no bridges.
- NoOp `@DefaultBean` implementations must NOT fire CDI events.
- All notification IDs are UUID v7 (time-ordered) for cursor pagination stability.
- Tenant isolation on all operations — `tenancyId` parameter on every SPI method.
- `userId` on mutation methods (`markRead`, `dismiss`) — SPI-boundary ownership enforcement.
- Cursor pagination is keyset-based: `(created_at DESC, id DESC)`. Cursor encoding is implementation-owned.
- Retention is store-owned: in-memory uses bounded size, JPA uses `@Scheduled` purge.

---

### Task 1: SPI Types + Contract Tests (platform-api)

**Files:**
- Create: `platform-api/src/main/java/io/casehub/platform/api/notification/NotificationSeverity.java`
- Create: `platform-api/src/main/java/io/casehub/platform/api/notification/NotificationStatus.java`
- Create: `platform-api/src/main/java/io/casehub/platform/api/notification/NotificationSource.java`
- Create: `platform-api/src/main/java/io/casehub/platform/api/notification/NotificationInput.java`
- Create: `platform-api/src/main/java/io/casehub/platform/api/notification/Notification.java`
- Create: `platform-api/src/main/java/io/casehub/platform/api/notification/NotificationQuery.java`
- Create: `platform-api/src/main/java/io/casehub/platform/api/notification/NotificationPage.java`
- Create: `platform-api/src/main/java/io/casehub/platform/api/notification/NotificationStore.java`
- Create: `platform-api/src/main/java/io/casehub/platform/api/notification/ReactiveNotificationStore.java`
- Create: `platform-api/src/main/java/io/casehub/platform/api/notification/NotificationCreated.java`
- Create: `platform-api/src/main/java/io/casehub/platform/api/notification/NotificationStatusChanged.java`
- Create: `platform-api/src/main/java/io/casehub/platform/api/notification/AllNotificationsRead.java`
- Create: `platform-api/src/test/java/io/casehub/platform/api/notification/NotificationSpiTest.java`
- Create: `platform-api/src/test/java/io/casehub/platform/api/notification/NotificationStoreContractTest.java`

**Interfaces:**
- Consumes: nothing (foundational task)
- Produces: `NotificationStore` interface (7 methods), `ReactiveNotificationStore` interface (7 methods), `Notification` record, `NotificationInput` record, `NotificationSource` record, `NotificationQuery` record, `NotificationPage` record, `NotificationSeverity` enum, `NotificationStatus` enum, `NotificationCreated` event record, `NotificationStatusChanged` event record, `AllNotificationsRead` event record, `NotificationStoreContractTest` abstract base class

**Implementation notes:**
- All types go in package `io.casehub.platform.api.notification`
- Follow the spec exactly — record fields, null validations, compact constructors as specified
- `NotificationStoreContractTest` follows the `AccessControlProviderContractTest` pattern: abstract base with `protected abstract NotificationStore store()` and `protected abstract void clearState()`. Tests cover: store + retrieve, storeAll, find with filters, unreadCount, markRead, dismiss, markAllRead, status lifecycle (UNREAD→READ→DISMISSED, UNREAD→DISMISSED), tenant isolation, user ownership enforcement, cursor pagination ordering
- `NotificationSpiTest` validates record construction, null rejection, defensive copies on `NotificationPage.notifications()`

- [ ] **Step 1: Write SPI record tests** — `NotificationSpiTest.java` covering record construction, null validation, defensive copies
- [ ] **Step 2: Implement enums and records** — `NotificationSeverity`, `NotificationStatus`, `NotificationSource`, `NotificationInput`, `Notification`, `NotificationQuery`, `NotificationPage`
- [ ] **Step 3: Run tests — verify pass**
- [ ] **Step 4: Write CDI event record tests** — add to `NotificationSpiTest.java` for `NotificationCreated`, `NotificationStatusChanged`, `AllNotificationsRead`
- [ ] **Step 5: Implement CDI event records**
- [ ] **Step 6: Run tests — verify pass**
- [ ] **Step 7: Write SPI interface** — `NotificationStore.java` with Javadoc. No tests for the interface itself (it has no implementation yet).
- [ ] **Step 8: Write reactive SPI interface** — `ReactiveNotificationStore.java` with Javadoc
- [ ] **Step 9: Write contract test base class** — `NotificationStoreContractTest.java` extending JUnit 5. Abstract `store()` and `clearState()` methods. Comprehensive test methods covering all SPI operations, edge cases, and tenant/user isolation.
- [ ] **Step 10: Run `mvn --batch-mode -pl platform-api test`** — contract test class compiles but has no concrete subclass yet (no failures expected — abstract class isn't instantiated)
- [ ] **Step 11: Commit** — `feat(#135): NotificationStore SPI — types, interfaces, contract tests`

---

### Task 2: NoOp Implementations (platform/)

**Files:**
- Create: `platform/src/main/java/io/casehub/platform/notification/NoOpNotificationStore.java`
- Create: `platform/src/main/java/io/casehub/platform/notification/NoOpReactiveNotificationStore.java`
- Create: `platform/src/test/java/io/casehub/platform/notification/NoOpNotificationStoreTest.java`

**Interfaces:**
- Consumes: `NotificationStore`, `ReactiveNotificationStore`, `NotificationInput`, `Notification`, `NotificationSeverity`, `NotificationStatus`, `NotificationSource`, `NotificationQuery`, `NotificationPage` (all from Task 1)
- Produces: `NoOpNotificationStore @DefaultBean @ApplicationScoped`, `NoOpReactiveNotificationStore @DefaultBean @ApplicationScoped`

**Implementation notes:**
- `NoOpNotificationStore`: `store()` returns a structurally valid `Notification` (UUID v7, UNREAD, Instant.now()), all queries return empty, all mutations return empty/zero. Does NOT fire CDI events.
- `NoOpReactiveNotificationStore`: delegates to `NoOpNotificationStore` via `@Inject`. `Uni.createFrom().item(() -> delegate.method())` — no `runSubscriptionOn` (no-op does no I/O). Does NOT fire CDI events.
- Test: `NoOpNotificationStoreTest` — verify store returns valid record, queries return empty, mutations return empty, no exceptions thrown. Plain JUnit 5 (no @QuarkusTest).

- [ ] **Step 1: Write NoOp tests** — `NoOpNotificationStoreTest.java` verifying store returns valid Notification, find returns empty page, unreadCount returns 0, markRead/dismiss return empty, markAllRead returns 0
- [ ] **Step 2: Implement `NoOpNotificationStore`** — `@DefaultBean @ApplicationScoped`, private `toNotification(NotificationInput)` helper
- [ ] **Step 3: Run tests — verify pass**
- [ ] **Step 4: Implement `NoOpReactiveNotificationStore`** — `@DefaultBean @ApplicationScoped`, injects `NoOpNotificationStore`, delegates each method
- [ ] **Step 5: Run `mvn --batch-mode -pl platform test`** — verify pass
- [ ] **Step 6: Commit** — `feat(#135): NoOp notification store — @DefaultBean blocking + reactive`

---

### Task 3: In-Memory Implementation (notifications-inmem/)

**Files:**
- Create: `notifications-inmem/pom.xml`
- Create: `notifications-inmem/src/main/java/io/casehub/platform/notification/inmem/InMemoryNotificationStore.java`
- Create: `notifications-inmem/src/main/java/io/casehub/platform/notification/inmem/InMemoryReactiveNotificationStore.java`
- Create: `notifications-inmem/src/test/java/io/casehub/platform/notification/inmem/InMemoryNotificationStoreTest.java`
- Modify: `pom.xml` (parent — add `<module>notifications-inmem</module>`)

**Interfaces:**
- Consumes: `NotificationStore`, `ReactiveNotificationStore`, all records/enums from Task 1, `NotificationStoreContractTest` from Task 1
- Produces: `InMemoryNotificationStore @Alternative @Priority(100) @ApplicationScoped`, `InMemoryReactiveNotificationStore @Alternative @Priority(100) @ApplicationScoped`

**Implementation notes:**
- `pom.xml`: parent `casehub-platform-parent`, artifactId `casehub-platform-notifications-inmem`. Dependencies: `casehub-platform-api`, `quarkus-arc`. Test deps: `casehub-platform-api` test-jar, `junit-jupiter`, `assertj-core`. Plugins: `jandex-maven-plugin` (no `quarkus-maven-plugin`). Follow `acl-inmem/pom.xml` pattern exactly.
- `InMemoryNotificationStore`: `ConcurrentHashMap<String, Notification>`. UUID v7 generation: use `java.util.UUID.nameUUIDFromBytes()` is not suitable — implement a `UUIDv7.generate()` utility in platform-api that constructs a time-ordered UUID per RFC 9562 §5.7 (48-bit Unix timestamp millis + 12-bit random + 62-bit random). No external library needed — manual construction from `Instant.now()` and `ThreadLocalRandom`. This utility is shared by all notification store implementations. Constructor-injected `Event<NotificationCreated>`, `Event<NotificationStatusChanged>`, `Event<AllNotificationsRead>`. Package-private no-arg constructor for CDI proxy + unit tests. `find()` implements cursor pagination: sort by `(createdAt DESC, id DESC)`, encode cursor as `Base64(createdAt_epoch_millis + ":" + id)`, decode on query. Retention: `@ConfigProperty(name = "casehub.notification.inmem.max-size", defaultValue = "10000")` — evict oldest on insert when full.
- `InMemoryReactiveNotificationStore`: delegates to `InMemoryNotificationStore`. `Uni.createFrom().item(() -> delegate.method())` without `runSubscriptionOn()` — ConcurrentHashMap is non-blocking, safe on event loop.
- `InMemoryNotificationStoreTest`: extends `NotificationStoreContractTest`. Creates `InMemoryNotificationStore` with null CDI events (no-arg constructor) in `clearState()`. Additional tests for bounded-size eviction.

- [ ] **Step 1: Create `notifications-inmem/pom.xml`** — follow `acl-inmem/pom.xml` pattern
- [ ] **Step 2: Add `<module>notifications-inmem</module>` to parent `pom.xml`**
- [ ] **Step 3: Write `InMemoryNotificationStoreTest`** — extends `NotificationStoreContractTest`, overrides `store()` and `clearState()`. Add eviction test.
- [ ] **Step 4: Run test — verify it fails** (implementation doesn't exist yet)
- [ ] **Step 5: Implement `InMemoryNotificationStore`** — `@Alternative @Priority(100) @ApplicationScoped`, ConcurrentHashMap, CDI events, cursor pagination, retention
- [ ] **Step 6: Run tests — verify pass**
- [ ] **Step 7: Implement `InMemoryReactiveNotificationStore`** — delegates to blocking store
- [ ] **Step 8: Run `mvn --batch-mode -pl notifications-inmem test`** — verify pass
- [ ] **Step 9: Commit** — `feat(#135): InMemory notification store — @Alternative @Priority(100) blocking + reactive`

---

### Task 4: JPA Implementation (notifications-jpa/)

**Files:**
- Create: `notifications-jpa/pom.xml`
- Create: `notifications-jpa/src/main/java/io/casehub/platform/notification/jpa/NotificationEntity.java`
- Create: `notifications-jpa/src/main/java/io/casehub/platform/notification/jpa/JpaReactiveNotificationStore.java`
- Create: `notifications-jpa/src/main/java/io/casehub/platform/notification/jpa/JpaNotificationStore.java`
- Create: `notifications-jpa/src/main/resources/db/notification/migration/V1__notification.sql`
- Create: `notifications-jpa/src/test/java/io/casehub/platform/notification/jpa/JpaNotificationStoreTest.java`
- Create: `notifications-jpa/src/test/resources/application.properties`
- Modify: `pom.xml` (parent — add `<module>notifications-jpa</module>`)

**Interfaces:**
- Consumes: `NotificationStore`, `ReactiveNotificationStore`, all records/enums from Task 1, `NotificationStoreContractTest` from Task 1
- Produces: `JpaReactiveNotificationStore @ApplicationScoped` (native Hibernate Reactive), `JpaNotificationStore @ApplicationScoped` (Vert.x context + await on reactive), `NotificationEntity`, Flyway migration at `classpath:db/notification/migration`

**Implementation notes:**

- `pom.xml`: parent `casehub-platform-parent`, artifactId `casehub-platform-notifications-jpa`. Dependencies: `casehub-platform-api`, `quarkus-hibernate-reactive-panache`, `quarkus-reactive-pg-client` (optional), `quarkus-flyway`, `quarkus-jdbc-postgresql` (optional), `quarkus-jdbc-h2` (test). Test deps: `casehub-platform-api` test-jar, `casehub-platform` (test scope — for NoOp deps), `casehub-platform-testing` (test), `quarkus-test-vertx`, `quarkus-junit5`. Plugins: `quarkus-maven-plugin` (generate-code + generate-code-tests, NO `build` goal), `jandex-maven-plugin`. Follow `acl-jpa/pom.xml` pattern.

- `NotificationEntity`: extends `PanacheEntityBase`. `@Id public String id` (UUID v7, application-assigned — not `@GeneratedValue`). All fields match `Notification` record. Source fields flattened: `sourceEventId`, `sourceEntityType`, `sourceEntityId`, `sourceActorId`. `@Enumerated(EnumType.STRING)` for severity and status. Indexes as specified in design.

- `V1__notification.sql`: CREATE TABLE + 3 indexes, as specified in design. `MODE=PostgreSQL` compatible.

- `JpaReactiveNotificationStore`: `@ApplicationScoped implements ReactiveNotificationStore`. Uses `Panache.withSession()` / `Panache.withTransaction()`. `store()` builds entity, persists, fires `NotificationCreated` via `fireAsync()`. `find()` uses HQL with keyset cursor. `markAllRead()` uses bulk `UPDATE` HQL. All methods return `Uni<>` natively.

- `JpaNotificationStore`: `@ApplicationScoped implements NotificationStore`. Injects `Vertx` and `JpaReactiveNotificationStore`. Uses the `execute()` helper pattern from `JpaAccessControlProvider` — creates a Vert.x duplicated context, runs the reactive operation on it, and converts to blocking via `.subscribeAsCompletionStage().toCompletableFuture().join()`. This is NOT the bridge anti-pattern: the I/O is reactive (Vert.x PG client / H2 reactive emulated), blocking is just a calling convention for worker-thread callers.

- `application.properties` (test): H2 with both JDBC and reactive URLs, PostgreSQL mode, Flyway at start. Follow `quarkus-test-database.md` protocol.

- `JpaNotificationStoreTest`: `@QuarkusTest`. Tests both SPIs against H2. Uses `@TestTransaction` where appropriate. Validates Flyway migration runs, entity mapping works, cursor pagination produces correct ordering.

- [ ] **Step 1: Create `notifications-jpa/pom.xml`** — follow `acl-jpa/pom.xml` pattern with H2 test deps added
- [ ] **Step 2: Add `<module>notifications-jpa</module>` to parent `pom.xml`**
- [ ] **Step 3: Create Flyway migration** — `V1__notification.sql`
- [ ] **Step 4: Create `NotificationEntity`** — `PanacheEntityBase`, application-assigned String ID, all fields
- [ ] **Step 5: Create test `application.properties`** — H2 PostgreSQL mode, both JDBC + reactive URLs, Flyway
- [ ] **Step 6: Write `JpaNotificationStoreTest`** — `@QuarkusTest`, tests for store/find/markRead/dismiss/markAllRead/unreadCount, cursor pagination, tenant isolation
- [ ] **Step 7: Implement `JpaReactiveNotificationStore`** — native Hibernate Reactive Panache, CDI events
- [ ] **Step 8: Implement `JpaNotificationStore`** — Vert.x context + await on reactive, following `JpaAccessControlProvider.execute()` pattern
- [ ] **Step 9: Run `mvn --batch-mode -pl notifications-jpa test`** — verify pass
- [ ] **Step 10: Commit** — `feat(#135): JPA notification store — Hibernate Reactive + H2 tests`

---

### Task 5: REST + SSE Module (notifications/)

**Files:**
- Create: `notifications/pom.xml`
- Create: `notifications/src/main/java/io/casehub/platform/notification/rest/NotificationResource.java`
- Create: `notifications/src/main/java/io/casehub/platform/notification/rest/NotificationSseResource.java`
- Create: `notifications/src/test/java/io/casehub/platform/notification/rest/NotificationResourceTest.java`
- Create: `notifications/src/test/java/io/casehub/platform/notification/rest/NotificationSseResourceTest.java`
- Create: `notifications/src/test/resources/application.properties`
- Modify: `pom.xml` (parent — add `<module>notifications</module>`)

**Interfaces:**
- Consumes: `ReactiveNotificationStore` (injected — whichever impl is on classpath), `CurrentPrincipal`, `NotificationCreated`, `NotificationStatusChanged`, `AllNotificationsRead` CDI events
- Produces: REST endpoints at `/notifications/*`, SSE endpoint at `/notifications/stream`

**Implementation notes:**

- `pom.xml`: parent `casehub-platform-parent`, artifactId `casehub-platform-notifications`. Dependencies: `casehub-platform-api`, `quarkus-rest` (RESTEasy Reactive), `quarkus-rest-jackson`, `quarkus-arc`. Test deps: `casehub-platform-notifications-inmem` (test scope — in-memory backend for @QuarkusTest), `casehub-platform` (test scope — NoOp deps for CurrentPrincipal), `casehub-platform-testing` (test — FixedCurrentPrincipal), `quarkus-junit5`, `io.rest-assured:rest-assured`. Plugins: `quarkus-maven-plugin` (generate-code + generate-code-tests, NO `build` goal), `jandex-maven-plugin`.

- `NotificationResource`: `@Path("/notifications") @ApplicationScoped`. Injects `ReactiveNotificationStore` and `CurrentPrincipal`. All methods return `Uni<>` (RESTEasy Reactive on event loop). `list()` → `GET /notifications` with `@QueryParam` for status, category, cursor, limit. `unreadCount()` → `GET /notifications/unread-count`. `markRead()` → `PATCH /notifications/{id}/read`. `dismiss()` → `PATCH /notifications/{id}/dismiss`. `markAllRead()` → `POST /notifications/mark-all-read`. Principal provides userId and tenancyId — never from request params.

- `NotificationPushService`: `@Path("/notifications/stream") @ApplicationScoped`. `ConcurrentHashMap<String, Set<SseEventSink>>` keyed by `tenancyId::userId`. Captures userId/tenancyId from `CurrentPrincipal` at stream establishment. `@ObservesAsync` handlers for `NotificationCreated`, `NotificationStatusChanged`, `AllNotificationsRead` push to matching user connections. Emitter lifecycle: `onClose`/`onError` callbacks remove from map. `AllNotificationsRead` handler queries `unreadCount()` from store (doesn't assume zero).

- `NotificationResourceTest`: `@QuarkusTest`. Uses REST Assured for HTTP assertions. Tests all endpoints with `FixedCurrentPrincipal`. Verifies 200/404 responses, JSON structure, tenant isolation (different tenant → 404).

- `NotificationSseResourceTest`: `@QuarkusTest`. Tests SSE connection, receives `unread-count` on connect, receives `notification` event on store write, receives `notification-updated` on status change.

- [ ] **Step 1: Create `notifications/pom.xml`** — RESTEasy Reactive deps, in-memory store for tests
- [ ] **Step 2: Add `<module>notifications</module>` to parent `pom.xml`**
- [ ] **Step 3: Create test `application.properties`**
- [ ] **Step 4: Write `NotificationResourceTest`** — REST Assured tests for all endpoints
- [ ] **Step 5: Implement `NotificationResource`** — `@Path("/notifications")`, all REST endpoints using `ReactiveNotificationStore`
- [ ] **Step 6: Run tests — verify pass**
- [ ] **Step 7: Write `NotificationSseResourceTest`** — SSE connection test, event push test
- [ ] **Step 8: Implement `NotificationPushService`** — SSE endpoint, CDI event observers, connection management, principal capture
- [ ] **Step 9: Run `mvn --batch-mode -pl notifications test`** — verify pass
- [ ] **Step 10: Commit** — `feat(#135): notification REST + SSE — endpoints and real-time push`

---

### Task 6: Integration Build + CLAUDE.md Update

**Files:**
- Modify: `CLAUDE.md` (add new modules to module table)

**Interfaces:**
- Consumes: all 5 previous tasks
- Produces: updated CLAUDE.md, verified full build

- [ ] **Step 1: Run full build** — `mvn --batch-mode install`
- [ ] **Step 2: Update CLAUDE.md** — add `notifications-inmem/`, `notifications-jpa/`, `notifications/` to the Modules table with descriptions matching the spec
- [ ] **Step 3: Run full build again** — verify clean
- [ ] **Step 4: Commit** — `docs(#135): add notification modules to CLAUDE.md`
