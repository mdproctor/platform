# Decouple Delivery Tracking Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use
> executing-plans to implement this plan task-by-task. Each task follows TDD
> (test-driven-development) and uses ide-tooling for structural editing.
> Steps use checkbox (`- [ ]`) syntax for tracking.

**Focal issue:** #192 — refactor: decouple delivery tracking from notification store
**Issue group:** #192

**Goal:** Replace `notificationId` with `sourceId + sourceType` across the delivery tracking SPI, all store implementations, and notification-dispatch consumers, with independent per-source-type retention.

**Architecture:** Clean break — new `DeliverySourceType` enum, `DeliveryAttempt` and `EngagementEvent` records gain `sourceId`/`sourceType` fields replacing `notificationId`, `DeliveryAttemptStore` SPI methods renamed. Flyway V3002 migrates existing data. JPA retention refactored into independent attempt + engagement sweeps per source type.

**Tech Stack:** Java 21, Quarkus, JPA/Hibernate, PostgreSQL, Flyway, JUnit 5, AssertJ

## Global Constraints

- `platform-api/` is zero-dependency — no Quarkus, no JPA imports
- `DeliverySourceType` is an enum, not a String — deliberate registration gate
- `sourceId` is nullable (digests have no single source entity)
- `sourceType` is required (non-null) on both `DeliveryAttempt` and `EngagementEvent`
- `EngagementEvent.attemptId` becomes nullable (survives attempt retention purge via SET NULL)
- `findBySource` requires non-null `sourceId` — sourceless entries are not queryable by source
- InMemory store keeps cascade eviction (volatile store, no scheduled retention)
- JPA store gets independent attempt + engagement retention sweeps per source type

---

### Task 1: SPI Types + NoOp Store

**Files:**
- Create: `platform-api/src/main/java/io/casehub/platform/api/delivery/DeliverySourceType.java`
- Modify: `platform-api/src/main/java/io/casehub/platform/api/delivery/DeliveryAttempt.java`
- Modify: `platform-api/src/main/java/io/casehub/platform/api/delivery/EngagementEvent.java`
- Modify: `platform-api/src/main/java/io/casehub/platform/api/delivery/DeliveryAttemptStore.java`
- Modify: `platform-api/src/main/java/io/casehub/platform/api/delivery/DeliveryAttemptQuery.java`
- Modify: `platform/src/main/java/io/casehub/platform/delivery/NoOpDeliveryAttemptStore.java`
- Test: `platform-api/src/test/java/io/casehub/platform/api/delivery/DeliveryAttemptTest.java`
- Test: `platform-api/src/test/java/io/casehub/platform/api/delivery/EngagementEventTest.java`

**Interfaces:**
- Produces: `DeliverySourceType` enum with `NOTIFICATION` value
- Produces: `DeliveryAttempt(String id, String sourceId, DeliverySourceType sourceType, String channelId, ...)` — `notificationId` removed, `sourceId + sourceType` added at positions 2-3
- Produces: `EngagementEvent(String id, String attemptId, String sourceId, DeliverySourceType sourceType, ...)` — `notificationId` removed, `sourceId + sourceType` at positions 3-4, `attemptId` nullable
- Produces: `DeliveryAttemptStore.findBySource(String sourceId, DeliverySourceType sourceType)`
- Produces: `DeliveryAttemptStore.findEngagementsBySource(String sourceId, DeliverySourceType sourceType)`
- Produces: `DeliveryAttemptQuery` with optional `DeliverySourceType sourceType` filter at position 5

- [ ] **Step 1: Create DeliverySourceType enum**

Create file `platform-api/src/main/java/io/casehub/platform/api/delivery/DeliverySourceType.java` via `ide_create_file`:

```java
package io.casehub.platform.api.delivery;

public enum DeliverySourceType {
    NOTIFICATION
}
```

- [ ] **Step 2: Update DeliveryAttempt record**

Use `ide_edit_member` on `DeliveryAttempt.java`, member `DeliveryAttempt`, to replace the entire record declaration. Replace `notificationId` field with `sourceId` + `sourceType`:

```java
public record DeliveryAttempt(
        String id,
        String sourceId,
        DeliverySourceType sourceType,
        String channelId,
        String userId,
        String tenancyId,
        DeliveryType deliveryType,
        DeliveryStatus status,
        int attemptCount,
        Instant createdAt,
        Instant lastAttemptedAt,
        Instant deliveredAt,
        Instant nextRetryAt,
        String failureReason,
        String payload,
        Instant firstOpenedAt,
        Instant firstClickedAt
) {
    public DeliveryAttempt {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(sourceType, "sourceType");
        Objects.requireNonNull(channelId, "channelId");
        Objects.requireNonNull(userId, "userId");
        Objects.requireNonNull(tenancyId, "tenancyId");
        Objects.requireNonNull(deliveryType, "deliveryType");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(payload, "payload");
    }
}
```

- [ ] **Step 3: Update EngagementEvent record**

Use `ide_edit_member` on `EngagementEvent.java`, member `EngagementEvent`, to replace the entire record declaration. Replace `notificationId` with `sourceId` + `sourceType`, make `attemptId` nullable:

```java
public record EngagementEvent(
        String id,
        String attemptId,
        String sourceId,
        DeliverySourceType sourceType,
        String channelId,
        String userId,
        String tenancyId,
        EngagementType type,
        Instant recordedAt,
        String metadata
) {
    public EngagementEvent {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(sourceType, "sourceType");
        Objects.requireNonNull(channelId, "channelId");
        Objects.requireNonNull(userId, "userId");
        Objects.requireNonNull(tenancyId, "tenancyId");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(recordedAt, "recordedAt");
    }
}
```

- [ ] **Step 4: Update DeliveryAttemptStore SPI**

Use `ide_edit_member` on `DeliveryAttemptStore.java` to rename the two methods. Replace `findByNotificationId` with:

```java
List<DeliveryAttempt> findBySource(String sourceId, DeliverySourceType sourceType);
```

Replace `findEngagementsByNotificationId` with:

```java
List<EngagementEvent> findEngagementsBySource(String sourceId, DeliverySourceType sourceType);
```

- [ ] **Step 5: Update DeliveryAttemptQuery**

Use `ide_edit_member` on `DeliveryAttemptQuery.java`, member `DeliveryAttemptQuery`, to add `sourceType` filter:

```java
public record DeliveryAttemptQuery(
        String userId,
        String tenancyId,
        String channelId,
        DeliveryStatus status,
        DeliverySourceType sourceType,
        String cursor,
        int limit
) {
    public DeliveryAttemptQuery {
        Objects.requireNonNull(tenancyId, "tenancyId");
        if (limit <= 0) throw new IllegalArgumentException("limit must be positive");
    }
}
```

- [ ] **Step 6: Update NoOpDeliveryAttemptStore**

Use `ide_edit_member` on `NoOpDeliveryAttemptStore.java` to replace `findByNotificationId` with:

```java
@Override
public List<DeliveryAttempt> findBySource(String sourceId, DeliverySourceType sourceType) {
    return List.of();
}
```

Replace `findEngagementsByNotificationId` with:

```java
@Override
public List<EngagementEvent> findEngagementsBySource(String sourceId, DeliverySourceType sourceType) {
    return List.of();
}
```

- [ ] **Step 7: Write failing tests for DeliveryAttemptTest**

Use `ide_edit_member` to rewrite each existing test method, and `ide_insert_member` for new ones. Every constructor call changes — `notificationId` at position 2 becomes `sourceId` at position 2, new `sourceType` at position 3.

Rewrite `rejectsNullId`:
```java
@Test
void rejectsNullId() {
    assertThatNullPointerException().isThrownBy(() ->
            new DeliveryAttempt(null, null, DeliverySourceType.NOTIFICATION, "email", "user1", "tenant1",
                    DeliveryType.IMMEDIATE, DeliveryStatus.DELIVERED, 1,
                    Instant.now(), Instant.now(), Instant.now(), null, null, "{}", null, null));
}
```

Rewrite `rejectsNullChannelId`:
```java
@Test
void rejectsNullChannelId() {
    assertThatNullPointerException().isThrownBy(() ->
            new DeliveryAttempt("id1", null, DeliverySourceType.NOTIFICATION, null, "user1", "tenant1",
                    DeliveryType.IMMEDIATE, DeliveryStatus.DELIVERED, 1,
                    Instant.now(), Instant.now(), Instant.now(), null, null, "{}", null, null));
}
```

Rewrite `rejectsNullPayload`:
```java
@Test
void rejectsNullPayload() {
    assertThatNullPointerException().isThrownBy(() ->
            new DeliveryAttempt("id1", null, DeliverySourceType.NOTIFICATION, "email", "user1", "tenant1",
                    DeliveryType.IMMEDIATE, DeliveryStatus.DELIVERED, 1,
                    Instant.now(), Instant.now(), Instant.now(), null, null, null, null, null));
}
```

Add new test `rejectsNullSourceType`:
```java
@Test
void rejectsNullSourceType() {
    assertThatNullPointerException().isThrownBy(() ->
            new DeliveryAttempt("id1", null, null, "email", "user1", "tenant1",
                    DeliveryType.IMMEDIATE, DeliveryStatus.DELIVERED, 1,
                    Instant.now(), Instant.now(), Instant.now(), null, null, "{}", null, null));
}
```

Rewrite `acceptsNullableFields`:
```java
@Test
void acceptsNullableFields() {
    var attempt = new DeliveryAttempt(
            "id1", null, DeliverySourceType.NOTIFICATION, "email", "user1", "tenant1",
            DeliveryType.DIGEST, DeliveryStatus.RETRYING, 0,
            Instant.now(), null, null, null, null, "{}", null, null);
    assertThat(attempt.sourceId()).isNull();
    assertThat(attempt.lastAttemptedAt()).isNull();
    assertThat(attempt.deliveredAt()).isNull();
    assertThat(attempt.nextRetryAt()).isNull();
    assertThat(attempt.failureReason()).isNull();
}
```

Rewrite `allFieldsRoundTrip`:
```java
@Test
void allFieldsRoundTrip() {
    var now = Instant.now();
    var attempt = new DeliveryAttempt(
            "id1", "notif-1", DeliverySourceType.NOTIFICATION, "email", "user1", "tenant1",
            DeliveryType.IMMEDIATE, DeliveryStatus.DELIVERED, 1,
            now, now, now, null, null, "{\"title\":\"test\"}", null, null);
    assertThat(attempt.id()).isEqualTo("id1");
    assertThat(attempt.sourceId()).isEqualTo("notif-1");
    assertThat(attempt.sourceType()).isEqualTo(DeliverySourceType.NOTIFICATION);
    assertThat(attempt.channelId()).isEqualTo("email");
    assertThat(attempt.deliveryType()).isEqualTo(DeliveryType.IMMEDIATE);
    assertThat(attempt.status()).isEqualTo(DeliveryStatus.DELIVERED);
    assertThat(attempt.payload()).isEqualTo("{\"title\":\"test\"}");
}
```

- [ ] **Step 8: Write failing tests for EngagementEventTest**

Rewrite all constructor calls to use `sourceId, sourceType` instead of `notificationId`, and remove `requireNonNull(attemptId)` test (attemptId is now nullable).

Rewrite `rejectsNullId`:
```java
@Test
void rejectsNullId() {
    assertThatNullPointerException().isThrownBy(() ->
            new EngagementEvent(null, "attempt-1", "notif-1", DeliverySourceType.NOTIFICATION,
                    "email", "user-1", "tenant-1", EngagementType.OPENED, Instant.now(), null));
}
```

Replace `rejectsNullAttemptId` with `rejectsNullSourceType`:
```java
@Test
void rejectsNullSourceType() {
    assertThatNullPointerException().isThrownBy(() ->
            new EngagementEvent("id-1", "attempt-1", "notif-1", null,
                    "email", "user-1", "tenant-1", EngagementType.OPENED, Instant.now(), null));
}
```

Rewrite `rejectsNullType`:
```java
@Test
void rejectsNullType() {
    assertThatNullPointerException().isThrownBy(() ->
            new EngagementEvent("id-1", "attempt-1", "notif-1", DeliverySourceType.NOTIFICATION,
                    "email", "user-1", "tenant-1", null, Instant.now(), null));
}
```

Rewrite `acceptsNullableFields`:
```java
@Test
void acceptsNullableFields() {
    var event = new EngagementEvent("id-1", null, null, DeliverySourceType.NOTIFICATION,
            "email", "user-1", "tenant-1", EngagementType.CLICKED, Instant.now(), null);
    assertThat(event.attemptId()).isNull();
    assertThat(event.sourceId()).isNull();
    assertThat(event.metadata()).isNull();
}
```

Rewrite `allFieldsRoundTrip`:
```java
@Test
void allFieldsRoundTrip() {
    var now = Instant.now();
    var event = new EngagementEvent("id-1", "attempt-1", "notif-1", DeliverySourceType.NOTIFICATION,
            "email", "user-1", "tenant-1", EngagementType.OPENED, now,
            "{\"url\":\"https://example.com\"}");
    assertThat(event.id()).isEqualTo("id-1");
    assertThat(event.attemptId()).isEqualTo("attempt-1");
    assertThat(event.sourceId()).isEqualTo("notif-1");
    assertThat(event.sourceType()).isEqualTo(DeliverySourceType.NOTIFICATION);
    assertThat(event.channelId()).isEqualTo("email");
    assertThat(event.type()).isEqualTo(EngagementType.OPENED);
    assertThat(event.recordedAt()).isEqualTo(now);
    assertThat(event.metadata()).isEqualTo("{\"url\":\"https://example.com\"}");
}
```

- [ ] **Step 9: Run platform-api tests**

Run: `mvn --batch-mode -pl platform-api test`
Expected: ALL PASS

- [ ] **Step 10: Build platform module to verify NoOp compiles**

Run: `mvn --batch-mode -pl platform compile`
Expected: BUILD SUCCESS

- [ ] **Step 11: Commit**

```bash
git add platform-api/ platform/
git commit -m "refactor(#192): replace notificationId with sourceId + sourceType in delivery SPI"
```

---

### Task 2: InMemory Store

**Files:**
- Modify: `delivery-tracking-inmem/src/main/java/io/casehub/platform/delivery/tracking/inmem/InMemoryDeliveryAttemptStore.java`
- Test: `delivery-tracking-inmem/src/test/java/io/casehub/platform/delivery/tracking/inmem/InMemoryDeliveryAttemptStoreTest.java`

**Interfaces:**
- Consumes: `DeliveryAttempt(id, sourceId, sourceType, channelId, ...)` from Task 1
- Consumes: `DeliveryAttemptStore.findBySource(String, DeliverySourceType)` from Task 1
- Produces: `InMemoryDeliveryAttemptStore` implementing `findBySource` and `findEngagementsBySource` with dual-field filtering

- [ ] **Step 1: Update test helper methods**

The three `attempt(...)` helper methods construct `DeliveryAttempt` with `notificationId`. Rewrite all three to use `sourceId + sourceType`.

Rewrite `attempt(String notificationId, DeliveryStatus status)`:
```java
private DeliveryAttempt attempt(String sourceId, DeliveryStatus status) {
    return attempt(sourceId, "user-1", "tenant-1", "email", status);
}
```

Rewrite `attempt(DeliveryStatus status, Instant nextRetryAt)`:
```java
private DeliveryAttempt attempt(DeliveryStatus status, Instant nextRetryAt) {
    return new DeliveryAttempt(UUIDv7.generate(), null, DeliverySourceType.NOTIFICATION,
            "email", "user-1", "tenant-1",
            DeliveryType.IMMEDIATE, status, 1,
            Instant.now(), null, null, nextRetryAt, null, "{}", null, null);
}
```

Rewrite `attempt(String notificationId, String userId, String tenancyId, String channelId, DeliveryStatus status)`:
```java
private DeliveryAttempt attempt(String sourceId, String userId, String tenancyId,
                                String channelId, DeliveryStatus status) {
    return new DeliveryAttempt(UUIDv7.generate(), sourceId, DeliverySourceType.NOTIFICATION,
            channelId, userId, tenancyId,
            DeliveryType.IMMEDIATE, status, 1,
            Instant.now(), null, null, null, null, "{}", null, null);
}
```

- [ ] **Step 2: Rename test methods and update assertions**

Rename `storeAndFindByNotificationId` → `storeAndFindBySource`. Update body:
```java
@Test
void storeAndFindBySource() {
    var a1 = attempt("src-1", DeliveryStatus.DELIVERED);
    var a2 = attempt("src-1", DeliveryStatus.FAILED);
    var a3 = attempt("src-2", DeliveryStatus.DELIVERED);
    store.store(a1);
    store.store(a2);
    store.store(a3);

    var found = store.findBySource("src-1", DeliverySourceType.NOTIFICATION);
    assertThat(found).hasSize(2);
    assertThat(found).extracting(DeliveryAttempt::sourceId).containsOnly("src-1");
}
```

Rename `findByNotificationIdReturnsEmptyForUnknown` → `findBySourceReturnsEmptyForUnknown`:
```java
@Test
void findBySourceReturnsEmptyForUnknown() {
    assertThat(store.findBySource("unknown", DeliverySourceType.NOTIFICATION)).isEmpty();
}
```

Rename `findEngagementsByNotificationIdAcrossAttempts` → `findEngagementsBySourceAcrossAttempts`. Update body to use `sourceId` assertions and call `findEngagementsBySource`:
```java
@Test
void findEngagementsBySourceAcrossAttempts() {
    var a1 = attempt("src-1", DeliveryStatus.DELIVERED);
    var a2 = attempt("src-1", DeliveryStatus.DELIVERED);
    store.store(a1);
    store.store(a2);
    store.recordEngagement(new EngagementEvent("e1", a1.id(), "src-1", DeliverySourceType.NOTIFICATION,
            "email", "user-1", "tenant-1", EngagementType.OPENED, Instant.now(), null));
    store.recordEngagement(new EngagementEvent("e2", a2.id(), "src-1", DeliverySourceType.NOTIFICATION,
            "email", "user-1", "tenant-1", EngagementType.CLICKED, Instant.now(), null));

    var events = store.findEngagementsBySource("src-1", DeliverySourceType.NOTIFICATION);
    assertThat(events).hasSize(2);
}
```

Update all other tests that construct `EngagementEvent` to use the new constructor signature (add `DeliverySourceType.NOTIFICATION` after `sourceId`).

- [ ] **Step 3: Update InMemoryDeliveryAttemptStore implementation**

Replace `findByNotificationId` method with:
```java
@Override
public List<DeliveryAttempt> findBySource(String sourceId, DeliverySourceType sourceType) {
    return store.values().stream()
                .filter(a -> sourceId.equals(a.sourceId()) && sourceType == a.sourceType())
                .sorted(Comparator.comparing(DeliveryAttempt::createdAt))
                .toList();
}
```

Replace `findEngagementsByNotificationId` with:
```java
@Override
public List<EngagementEvent> findEngagementsBySource(String sourceId, DeliverySourceType sourceType) {
    return engagementStore.values().stream()
                          .flatMap(List::stream)
                          .filter(e -> sourceId.equals(e.sourceId()) && sourceType == e.sourceType())
                          .sorted(Comparator.comparing(EngagementEvent::recordedAt))
                          .toList();
}
```

Update `claimRetryable` — the `DeliveryAttempt` reconstruction uses `a.sourceId(), a.sourceType()` instead of `a.notificationId()`:
```java
var advanced = new DeliveryAttempt(
        a.id(), a.sourceId(), a.sourceType(), a.channelId(), a.userId(), a.tenancyId(),
        a.deliveryType(), a.status(), a.attemptCount(),
        a.createdAt(), a.lastAttemptedAt(), a.deliveredAt(),
        claimExpiry, a.failureReason(), a.payload(),
        a.firstOpenedAt(), a.firstClickedAt());
```

Update `recordEngagement` — the `DeliveryAttempt` reconstruction:
```java
return new DeliveryAttempt(
        attempt.id(), attempt.sourceId(), attempt.sourceType(), attempt.channelId(),
        attempt.userId(), attempt.tenancyId(), attempt.deliveryType(),
        attempt.status(), attempt.attemptCount(),
        attempt.createdAt(), attempt.lastAttemptedAt(), attempt.deliveredAt(),
        attempt.nextRetryAt(), attempt.failureReason(), attempt.payload(),
        firstOpened, firstClicked);
```

Update `find` — add sourceType filter to the stream:
```java
.filter(a -> query.sourceType() == null || a.sourceType() == query.sourceType())
```

- [ ] **Step 4: Run delivery-tracking-inmem tests**

Run: `mvn --batch-mode -pl delivery-tracking-inmem test`
Expected: ALL PASS

- [ ] **Step 5: Commit**

```bash
git add delivery-tracking-inmem/
git commit -m "refactor(#192): update InMemoryDeliveryAttemptStore for sourceId/sourceType"
```

---

### Task 3: JPA Store + Migration

**Files:**
- Modify: `delivery-tracking-jpa/src/main/java/io/casehub/platform/delivery/tracking/jpa/DeliveryAttemptEntity.java`
- Modify: `delivery-tracking-jpa/src/main/java/io/casehub/platform/delivery/tracking/jpa/EngagementEventEntity.java`
- Modify: `delivery-tracking-jpa/src/main/java/io/casehub/platform/delivery/tracking/jpa/JpaDeliveryAttemptStore.java`
- Create: `delivery-tracking-jpa/src/main/resources/db/delivery-tracking/migration/V3002__source_type_decoupling.sql`
- Test: `delivery-tracking-jpa/src/test/java/io/casehub/platform/delivery/tracking/jpa/JpaDeliveryAttemptStoreTest.java`

**Interfaces:**
- Consumes: `DeliveryAttempt`, `EngagementEvent`, `DeliverySourceType`, `DeliveryAttemptStore` from Task 1
- Produces: JPA-backed `findBySource`, `findEngagementsBySource` with JPQL, V3002 migration

- [ ] **Step 1: Create Flyway migration V3002**

Create `delivery-tracking-jpa/src/main/resources/db/delivery-tracking/migration/V3002__source_type_decoupling.sql`:

```sql
-- Decouple delivery tracking from notification store (platform#192)

-- delivery_attempt: add source columns, populate from notification_id, drop old column
ALTER TABLE delivery_attempt ADD COLUMN source_id VARCHAR(255);
ALTER TABLE delivery_attempt ADD COLUMN source_type VARCHAR(30);
UPDATE delivery_attempt SET source_id = notification_id, source_type = 'NOTIFICATION';
ALTER TABLE delivery_attempt ALTER COLUMN source_type SET NOT NULL;
ALTER TABLE delivery_attempt DROP COLUMN notification_id;
DROP INDEX IF EXISTS idx_delivery_attempt_notification;
CREATE INDEX idx_delivery_attempt_source ON delivery_attempt (source_id, source_type);

-- engagement_event: add source columns, populate, drop old column
ALTER TABLE engagement_event ADD COLUMN source_id VARCHAR(255);
ALTER TABLE engagement_event ADD COLUMN source_type VARCHAR(30);
UPDATE engagement_event SET source_id = notification_id, source_type = 'NOTIFICATION';
ALTER TABLE engagement_event ALTER COLUMN source_type SET NOT NULL;
ALTER TABLE engagement_event DROP COLUMN notification_id;
DROP INDEX IF EXISTS idx_engagement_event_notification;
CREATE INDEX idx_engagement_event_source ON engagement_event (source_id, source_type);

-- Break CASCADE → SET NULL, make attempt_id nullable for independent retention
ALTER TABLE engagement_event ALTER COLUMN attempt_id DROP NOT NULL;
ALTER TABLE engagement_event DROP CONSTRAINT engagement_event_attempt_id_fkey;
ALTER TABLE engagement_event ADD CONSTRAINT engagement_event_attempt_id_fkey
    FOREIGN KEY (attempt_id) REFERENCES delivery_attempt(id) ON DELETE SET NULL;
```

- [ ] **Step 2: Update DeliveryAttemptEntity**

Use `ide_edit_member` to replace the `notificationId` field with `sourceId` + `sourceType`:

Remove field `notificationId`. Add:
```java
@Column(name = "source_id")
public String sourceId;

@Enumerated(EnumType.STRING)
@Column(name = "source_type", nullable = false, length = 30)
public DeliverySourceType sourceType;
```

Update `fromDomain`:
```java
public static DeliveryAttemptEntity fromDomain(DeliveryAttempt attempt) {
    var entity = new DeliveryAttemptEntity();
    entity.id              = attempt.id();
    entity.sourceId        = attempt.sourceId();
    entity.sourceType      = attempt.sourceType();
    entity.channelId       = attempt.channelId();
    entity.userId          = attempt.userId();
    entity.tenancyId       = attempt.tenancyId();
    entity.deliveryType    = attempt.deliveryType();
    entity.status          = attempt.status();
    entity.attemptCount    = attempt.attemptCount();
    entity.createdAt       = attempt.createdAt();
    entity.lastAttemptedAt = attempt.lastAttemptedAt();
    entity.deliveredAt     = attempt.deliveredAt();
    entity.nextRetryAt     = attempt.nextRetryAt();
    entity.failureReason   = attempt.failureReason();
    entity.payload         = attempt.payload();
    entity.firstOpenedAt   = attempt.firstOpenedAt();
    entity.firstClickedAt  = attempt.firstClickedAt();
    return entity;
}
```

Update `toDomain`:
```java
public DeliveryAttempt toDomain() {
    return new DeliveryAttempt(
            id, sourceId, sourceType, channelId, userId, tenancyId,
            deliveryType, status, attemptCount,
            createdAt, lastAttemptedAt, deliveredAt,
            nextRetryAt, failureReason, payload,
            firstOpenedAt, firstClickedAt);
}
```

- [ ] **Step 3: Update EngagementEventEntity**

Replace `notificationId` field with `sourceId` + `sourceType`. Make `attemptId` nullable:

```java
@Column(name = "attempt_id", length = 36)
public String attemptId;

@Column(name = "source_id")
public String sourceId;

@Enumerated(EnumType.STRING)
@Column(name = "source_type", nullable = false, length = 30)
public DeliverySourceType sourceType;
```

Update `fromDomain`:
```java
public static EngagementEventEntity fromDomain(EngagementEvent event) {
    var entity = new EngagementEventEntity();
    entity.id = event.id();
    entity.attemptId = event.attemptId();
    entity.sourceId = event.sourceId();
    entity.sourceType = event.sourceType();
    entity.channelId = event.channelId();
    entity.userId = event.userId();
    entity.tenancyId = event.tenancyId();
    entity.type = event.type();
    entity.recordedAt = event.recordedAt();
    entity.metadata = event.metadata();
    return entity;
}
```

Update `toDomain`:
```java
public EngagementEvent toDomain() {
    return new EngagementEvent(
            id, attemptId, sourceId, sourceType, channelId,
            userId, tenancyId, type, recordedAt, metadata);
}
```

- [ ] **Step 4: Update JpaDeliveryAttemptStore**

Replace `update` method's `notificationId` assignment with `sourceId`/`sourceType`:
```java
entity.sourceId    = attempt.sourceId();
entity.sourceType  = attempt.sourceType();
```

Replace `findByNotificationId` with `findBySource`:
```java
@Override
public List<DeliveryAttempt> findBySource(String sourceId, DeliverySourceType sourceType) {
    return entityManager.createQuery(
                    "SELECT e FROM DeliveryAttemptEntity e " +
                    "WHERE e.sourceId = :sourceId AND e.sourceType = :sourceType " +
                    "ORDER BY e.createdAt ASC", DeliveryAttemptEntity.class)
            .setParameter("sourceId", sourceId)
            .setParameter("sourceType", sourceType)
            .getResultList()
            .stream().map(DeliveryAttemptEntity::toDomain).toList();
}
```

Update `find` method — add `sourceType` filter to JPQL builder:
```java
if (query.sourceType() != null) {sb.append(" AND e.sourceType = :sourceType");}
```
And the parameter binding:
```java
if (query.sourceType() != null) {jpql.setParameter("sourceType", query.sourceType());}
```

Replace `recordEngagement` — update the `findEngagementsByNotificationId` JPQL in the method. Note: `recordEngagement` body references `event.notificationId()` nowhere — it uses `event.attemptId()`. No JPQL change needed inside this method, only the entity mapping change (already done in Step 3).

Replace `findEngagementsByNotificationId` with `findEngagementsBySource`:
```java
@Override
public List<EngagementEvent> findEngagementsBySource(String sourceId, DeliverySourceType sourceType) {
    return entityManager
            .createQuery("FROM EngagementEventEntity e " +
                         "WHERE e.sourceId = :sourceId AND e.sourceType = :sourceType " +
                         "ORDER BY e.recordedAt",
                    EngagementEventEntity.class)
            .setParameter("sourceId", sourceId)
            .setParameter("sourceType", sourceType)
            .getResultList()
            .stream()
            .map(EngagementEventEntity::toDomain)
            .toList();
}
```

- [ ] **Step 5: Update JpaDeliveryAttemptStoreTest**

Update test helper methods to use `sourceId + sourceType`:

```java
private DeliveryAttempt attempt(String sourceId, DeliveryStatus status) {
    return attempt(sourceId, "user-1", "tenant-1", "email", status);
}

private DeliveryAttempt attempt(String sourceId, String userId, String tenancyId,
                                String channelId, DeliveryStatus status) {
    return new DeliveryAttempt(UUIDv7.generate(), sourceId, DeliverySourceType.NOTIFICATION,
            channelId, userId, tenancyId,
            DeliveryType.IMMEDIATE, status, 1,
            Instant.now(), null, null, null, null, "{}", null, null);
}
```

Rename and update test methods:
- `storeAndFindByNotificationId` → `storeAndFindBySource` — call `store.findBySource("src-1", DeliverySourceType.NOTIFICATION)`
- `findByNotificationIdReturnsEmptyForUnknown` → `findBySourceReturnsEmptyForUnknown`
- `findEngagementsByNotificationIdAcrossAttempts` → `findEngagementsBySourceAcrossAttempts`

Update all `EngagementEvent` constructor calls to include `DeliverySourceType.NOTIFICATION` at position 4.

Update `updateModifiesExistingRecord` — assertions change from `notificationId()` to `sourceId()`.

Add test for ON DELETE SET NULL:
```java
@Test
@Transactional
void deletingAttemptNullifiesEngagementAttemptId() {
    var attempt = attempt("src-1", DeliveryStatus.DELIVERED);
    store.store(attempt);
    store.recordEngagement(new EngagementEvent("e1", attempt.id(), "src-1",
            DeliverySourceType.NOTIFICATION, "email", "user-1", "tenant-1",
            EngagementType.OPENED, Instant.now(), null));

    entityManager.createQuery("DELETE FROM DeliveryAttemptEntity e WHERE e.id = :id")
            .setParameter("id", attempt.id())
            .executeUpdate();
    entityManager.flush();
    entityManager.clear();

    var events = store.findEngagementsBySource("src-1", DeliverySourceType.NOTIFICATION);
    assertThat(events).hasSize(1);
    assertThat(events.getFirst().attemptId()).isNull();
}
```

- [ ] **Step 6: Run delivery-tracking-jpa tests**

Run: `mvn --batch-mode -pl delivery-tracking-jpa test`
Expected: ALL PASS

- [ ] **Step 7: Commit**

```bash
git add delivery-tracking-jpa/
git commit -m "refactor(#192): JPA store + V3002 migration for sourceId/sourceType"
```

---

### Task 4: Notification-Dispatch Consumers

**Files:**
- Modify: `notification-dispatch/src/main/java/io/casehub/platform/notification/dispatch/DeliveryTracker.java`
- Modify: `notification-dispatch/src/main/java/io/casehub/platform/notification/dispatch/NotificationDispatcher.java`
- Modify: `notification-dispatch/src/main/java/io/casehub/platform/notification/dispatch/InAppEngagementBridge.java`
- Modify: `notification-dispatch/src/main/java/io/casehub/platform/notification/dispatch/EngagementRecorder.java`
- Modify: `notification-dispatch/src/main/java/io/casehub/platform/notification/dispatch/EngagementCallbackResource.java`
- Modify: `notification-dispatch/src/main/java/io/casehub/platform/notification/dispatch/DeliveryRetryProcessor.java`
- Test: `notification-dispatch/src/test/java/io/casehub/platform/notification/dispatch/DeliveryTrackerTest.java`
- Test: `notification-dispatch/src/test/java/io/casehub/platform/notification/dispatch/NotificationDispatcherTest.java`
- Test: `notification-dispatch/src/test/java/io/casehub/platform/notification/dispatch/InAppEngagementBridgeTest.java`
- Test: `notification-dispatch/src/test/java/io/casehub/platform/notification/dispatch/EngagementRecorderTest.java`
- Test: `notification-dispatch/src/test/java/io/casehub/platform/notification/dispatch/EngagementCallbackResourceTest.java`
- Test: `notification-dispatch/src/test/java/io/casehub/platform/notification/dispatch/DeliveryRetryProcessorTest.java`

**Interfaces:**
- Consumes: `DeliveryAttempt`, `EngagementEvent`, `DeliverySourceType`, `DeliveryAttemptStore` from Task 1
- Consumes: `InMemoryDeliveryAttemptStore` from Task 2 (used in tests)

- [ ] **Step 1: Update DeliveryTracker**

Replace `recordSuccess` signature — add `sourceId` + `sourceType`, remove `notificationId`:
```java
public void recordSuccess(String channelId, NotificationInput input,
                           String sourceId, DeliverySourceType sourceType) {
    var now = Instant.now();
    try {
        store.store(new DeliveryAttempt(
                UUIDv7.generate(), sourceId, sourceType, channelId,
                input.userId(), input.tenancyId(),
                DeliveryType.IMMEDIATE, DeliveryStatus.DELIVERED, 1,
                now, now, now, null, null,
                serialize(input), null, null));
    } catch (Exception e) {
        LOG.warnf(e, "Failed to record delivery success for channel '%s', user '%s'",
                  channelId, input.userId());
    }
}
```

Replace `recordFailure`:
```java
public void recordFailure(String channelId, NotificationInput input,
                           String sourceId, DeliverySourceType sourceType,
                           NotificationSeverity guaranteedMinSeverity, String failureReason) {
    var now = Instant.now();
    boolean retryEligible = guaranteedMinSeverity != null
                            && input.severity().isAtLeast(guaranteedMinSeverity);
    try {
        store.store(new DeliveryAttempt(
                UUIDv7.generate(), sourceId, sourceType, channelId,
                input.userId(), input.tenancyId(),
                DeliveryType.IMMEDIATE,
                retryEligible ? DeliveryStatus.RETRYING : DeliveryStatus.FAILED,
                1, now, now, null,
                retryEligible ? now.plus(baseDelay) : null,
                failureReason,
                serialize(input), null, null));
    } catch (Exception e) {
        LOG.warnf(e, "Failed to record delivery failure for channel '%s', user '%s'",
                  channelId, input.userId());
    }
}
```

Update `preRecordDigest` — pass `sourceId=null, sourceType=DeliverySourceType.NOTIFICATION`:
```java
var attempt = new DeliveryAttempt(
        UUIDv7.generate(), null, DeliverySourceType.NOTIFICATION, channelId,
        summary.userId(), summary.tenancyId(),
        DeliveryType.DIGEST,
        retryEligible ? DeliveryStatus.RETRYING : DeliveryStatus.FAILED,
        0, now, null, null,
        null,
        null,
        serialize(summary), null, null);
```

Update `confirmDigestSuccess` — use `preRecorded.sourceId(), preRecorded.sourceType()`:
```java
store.update(new DeliveryAttempt(
        preRecorded.id(), preRecorded.sourceId(), preRecorded.sourceType(),
        preRecorded.channelId(),
        preRecorded.userId(), preRecorded.tenancyId(), preRecorded.deliveryType(),
        DeliveryStatus.DELIVERED, preRecorded.attemptCount() + 1,
        preRecorded.createdAt(), now, now, null, null, preRecorded.payload(),
        preRecorded.firstOpenedAt(), preRecorded.firstClickedAt()));
```

Update `confirmDigestFailure` — same pattern:
```java
store.update(new DeliveryAttempt(
        preRecorded.id(), preRecorded.sourceId(), preRecorded.sourceType(),
        preRecorded.channelId(),
        preRecorded.userId(), preRecorded.tenancyId(), preRecorded.deliveryType(),
        DeliveryStatus.RETRYING, 1,
        preRecorded.createdAt(), now, null,
        now.plus(baseDelay),
        failureReason, preRecorded.payload(),
        preRecorded.firstOpenedAt(), preRecorded.firstClickedAt()));
```

- [ ] **Step 2: Update NotificationDispatcher**

Update delivery tracker calls in `dispatchToUser` — pass `null` sourceId and `DeliverySourceType.NOTIFICATION`:
```java
deliveryTracker.recordSuccess(
        channel.channelId(), notificationInput, null, DeliverySourceType.NOTIFICATION);
```
```java
deliveryTracker.recordFailure(
        channel.channelId(), notificationInput, null,
        DeliverySourceType.NOTIFICATION,
        channel.guaranteedMinSeverity(), result.failureReason());
```
```java
deliveryTracker.recordFailure(
        channel.channelId(), notificationInput, null,
        DeliverySourceType.NOTIFICATION,
        channel.guaranteedMinSeverity(), e.getMessage());
```

- [ ] **Step 3: Update InAppEngagementBridge**

Replace `store.findByNotificationId(notification.id())` with:
```java
var attempts = store.findBySource(notification.id(), DeliverySourceType.NOTIFICATION);
```

- [ ] **Step 4: Update EngagementRecorder**

Replace `attempt.notificationId()` with `attempt.sourceId()` and add `attempt.sourceType()` in `record()`:
```java
var event = new EngagementEvent(
        UUIDv7.generate(),
        attempt.id(),
        attempt.sourceId(),
        attempt.sourceType(),
        attempt.channelId(),
        attempt.userId(),
        attempt.tenancyId(),
        type,
        Instant.now(),
        metadata);
```

- [ ] **Step 5: Update DeliveryRetryProcessor**

Three `new DeliveryAttempt(...)` calls need updating. In `processAttempt` success path:
```java
store.update(new DeliveryAttempt(
        attempt.id(), attempt.sourceId(), attempt.sourceType(), attempt.channelId(),
        attempt.userId(), attempt.tenancyId(), attempt.deliveryType(),
        DeliveryStatus.DELIVERED, attempt.attemptCount() + 1,
        attempt.createdAt(), now, now, null, null, attempt.payload(),
        attempt.firstOpenedAt(), attempt.firstClickedAt()));
```

In `advanceOrExpire`:
```java
store.update(new DeliveryAttempt(
        attempt.id(), attempt.sourceId(), attempt.sourceType(), attempt.channelId(),
        attempt.userId(), attempt.tenancyId(), attempt.deliveryType(),
        DeliveryStatus.RETRYING, newCount,
        attempt.createdAt(), now, null,
        nextRetry, failureReason, attempt.payload(),
        attempt.firstOpenedAt(), attempt.firstClickedAt()));
```

In `expire`:
```java
var expired = new DeliveryAttempt(
        attempt.id(), attempt.sourceId(), attempt.sourceType(), attempt.channelId(),
        attempt.userId(), attempt.tenancyId(), attempt.deliveryType(),
        DeliveryStatus.EXPIRED, attempt.attemptCount() + 1,
        attempt.createdAt(), now, null, null,
        failureReason, attempt.payload(),
        attempt.firstOpenedAt(), attempt.firstClickedAt());
```

- [ ] **Step 6: Update all test helper methods**

Update every test class helper that constructs `DeliveryAttempt` or `EngagementEvent` to use the new constructor signatures. This is mechanical — every `notificationId` parameter becomes `sourceId` at the same position, with `DeliverySourceType.NOTIFICATION` inserted after it.

Key helpers to update:
- `DeliveryTrackerTest.testInput()` — unchanged (constructs NotificationInput, not DeliveryAttempt)
- `DeliveryRetryProcessorTest.retryableAttempt()` — add `sourceType` to constructor
- `InAppEngagementBridgeTest.inAppAttempt()` — `notificationId` → `sourceId`, add `sourceType`
- `EngagementRecorderTest.deliveredAttempt()` — same
- `EngagementCallbackResourceTest.deliveredAttempt()` — same

Update assertions: `.notificationId()` → `.sourceId()`, add `.sourceType()` assertions.

Update `InAppEngagementBridgeTest` — `store.findByNotificationId` calls → `store.findBySource`.

- [ ] **Step 7: Run notification-dispatch tests**

Run: `mvn --batch-mode -pl notification-dispatch test`
Expected: ALL PASS

- [ ] **Step 8: Full build verification**

Run: `mvn --batch-mode install`
Expected: BUILD SUCCESS, all tests pass across all modules

- [ ] **Step 9: Commit**

```bash
git add notification-dispatch/
git commit -m "refactor(#192): update notification-dispatch for sourceId/sourceType"
```

---

### Task 5: Retention Architecture Refactor

**Files:**
- Modify: `delivery-tracking-jpa/src/main/java/io/casehub/platform/delivery/tracking/jpa/JpaDeliveryAttemptStore.java`
- Test: `delivery-tracking-jpa/src/test/java/io/casehub/platform/delivery/tracking/jpa/JpaDeliveryAttemptStoreTest.java`

**Interfaces:**
- Consumes: `DeliverySourceType` from Task 1
- Consumes: JPA entities from Task 3

- [ ] **Step 1: Write failing retention tests**

Add new test methods to `JpaDeliveryAttemptStoreTest`:

```java
@Test
@Transactional
void attemptRetentionPurgesDeliveredBySourceType() {
    var old = new DeliveryAttempt(UUIDv7.generate(), "src-1", DeliverySourceType.NOTIFICATION,
            "email", "user-1", "tenant-1", DeliveryType.IMMEDIATE, DeliveryStatus.DELIVERED, 1,
            Instant.now().minus(Duration.ofDays(100)), Instant.now().minus(Duration.ofDays(100)),
            Instant.now().minus(Duration.ofDays(100)), null, null, "{}", null, null);
    store.store(old);

    var recent = attempt("src-2", DeliveryStatus.DELIVERED);
    store.store(recent);

    // Trigger purge (tested via direct method call or scheduled)
    // After purge with 30-day default: old should be gone, recent should remain
}
```

```java
@Test
@Transactional
void engagementRetentionIndependentFromAttempt() {
    var attempt = attempt("src-1", DeliveryStatus.DELIVERED);
    store.store(attempt);
    store.recordEngagement(new EngagementEvent("e1", attempt.id(), "src-1",
            DeliverySourceType.NOTIFICATION, "email", "user-1", "tenant-1",
            EngagementType.OPENED, Instant.now().minus(Duration.ofDays(100)), null));

    // After attempt purge: engagement survives with null attemptId
    // After engagement purge (if within window): engagement also purged
}
```

- [ ] **Step 2: Refactor retentionPurge into two independent methods**

Replace the existing `retentionPurge()` method with two scheduled methods.

Add config properties:
```java
@ConfigProperty(name = "casehub.delivery.retention.attempt-days", defaultValue = "30")
int defaultAttemptDays;

@ConfigProperty(name = "casehub.delivery.retention.failed-attempt-days", defaultValue = "365")
int defaultFailedAttemptDays;

@ConfigProperty(name = "casehub.delivery.retention.engagement-days", defaultValue = "90")
int defaultEngagementDays;

@Inject
Config config;
```

Remove old config properties `retentionDays` and `failedRetentionDays`.

Implement `attemptRetentionPurge`:
```java
@Scheduled(cron = "0 0 3 * * ?")
@Transactional
void attemptRetentionPurge() {
    for (DeliverySourceType sourceType : DeliverySourceType.values()) {
        int attemptDays = resolveConfig("casehub.delivery.retention.\"%s\".attempt-days",
                sourceType, defaultAttemptDays);
        int failedDays = resolveConfig("casehub.delivery.retention.\"%s\".failed-attempt-days",
                sourceType, defaultFailedAttemptDays);

        Instant attemptCutoff = Instant.now().minus(Duration.ofDays(attemptDays));
        Instant failedCutoff = Instant.now().minus(Duration.ofDays(failedDays));

        int purged = 0;
        purged += purgeAttempts(sourceType, DeliveryStatus.DELIVERED, attemptCutoff);
        purged += purgeAttempts(sourceType, DeliveryStatus.EXPIRED, attemptCutoff);
        purged += purgeAttempts(sourceType, DeliveryStatus.FAILED, failedCutoff);
        purged += purgeStaleRetrying(sourceType, attemptCutoff);

        if (purged > 0) {
            LOG.infof("Attempt retention purge [%s]: %d records removed", sourceType, purged);
        }
    }
    purgeOrphanedPrePersist();
}
```

Implement `engagementRetentionPurge`:
```java
@Scheduled(cron = "0 30 3 * * ?")
@Transactional
void engagementRetentionPurge() {
    for (DeliverySourceType sourceType : DeliverySourceType.values()) {
        int engagementDays = resolveConfig("casehub.delivery.retention.\"%s\".engagement-days",
                sourceType, defaultEngagementDays);
        Instant cutoff = Instant.now().minus(Duration.ofDays(engagementDays));

        int purged = entityManager.createQuery(
                "DELETE FROM EngagementEventEntity e " +
                "WHERE e.sourceType = :sourceType AND e.recordedAt < :cutoff")
                .setParameter("sourceType", sourceType)
                .setParameter("cutoff", cutoff)
                .executeUpdate();

        if (purged > 0) {
            LOG.infof("Engagement retention purge [%s]: %d records removed", sourceType, purged);
        }
    }
}
```

Add helper methods:
```java
private int purgeAttempts(DeliverySourceType sourceType, DeliveryStatus status, Instant cutoff) {
    return entityManager.createQuery(
            "DELETE FROM DeliveryAttemptEntity e " +
            "WHERE e.sourceType = :sourceType AND e.status = :status AND e.createdAt < :cutoff")
            .setParameter("sourceType", sourceType)
            .setParameter("status", status)
            .setParameter("cutoff", cutoff)
            .executeUpdate();
}

private int purgeStaleRetrying(DeliverySourceType sourceType, Instant cutoff) {
    return entityManager.createQuery(
            "DELETE FROM DeliveryAttemptEntity e " +
            "WHERE e.sourceType = :sourceType AND e.status = :status " +
            "AND e.nextRetryAt IS NOT NULL AND e.nextRetryAt < :cutoff")
            .setParameter("sourceType", sourceType)
            .setParameter("status", DeliveryStatus.RETRYING)
            .setParameter("cutoff", cutoff)
            .executeUpdate();
}

private void purgeOrphanedPrePersist() {
    int purged = entityManager.createQuery(
            "DELETE FROM DeliveryAttemptEntity e " +
            "WHERE e.status = :status AND e.nextRetryAt IS NULL AND e.createdAt < :cutoff")
            .setParameter("status", DeliveryStatus.RETRYING)
            .setParameter("cutoff", Instant.now().minus(claimTimeout))
            .executeUpdate();
    if (purged > 0) {
        LOG.infof("Orphaned pre-persist purge: %d records removed", purged);
    }
}

private int resolveConfig(String pattern, DeliverySourceType sourceType, int defaultValue) {
    String key = String.format(pattern, sourceType.name().toLowerCase());
    return config.getOptionalValue(key, Integer.class).orElse(defaultValue);
}
```

- [ ] **Step 3: Run delivery-tracking-jpa tests**

Run: `mvn --batch-mode -pl delivery-tracking-jpa test`
Expected: ALL PASS

- [ ] **Step 4: Full build**

Run: `mvn --batch-mode install`
Expected: BUILD SUCCESS, all tests pass

- [ ] **Step 5: Commit**

```bash
git add delivery-tracking-jpa/
git commit -m "refactor(#192): independent per-source-type retention for attempts and engagements"
```
