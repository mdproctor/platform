# Decouple Delivery Tracking from Notification Store

**Issue:** casehubio/platform#192
**Date:** 2026-07-21
**Status:** Approved

## Problem

`DeliveryAttempt.notificationId` is a hard FK to the notification concept. Any consumer that wants delivery/read tracking must create a notification record, which pollutes the inbox. This blocks qhorus from using the delivery SPI for chat message tracking and prevents other consumers (webhooks, coordination channels) from leveraging the delivery infrastructure.

## Approach

Clean break at the SPI: replace `notificationId` with `sourceId + sourceType` across all delivery tracking types, stores, and consumers. No parallel API period — pre-release with zero external consumers.

## Design

### 1. SPI Changes (platform-api)

#### New enum: `DeliverySourceType`

```java
public enum DeliverySourceType {
    NOTIFICATION
}
```

Enum over String constants — new consumers must deliberately register in platform-api. Future values: `CHAT_MESSAGE`, `WEBHOOK`, etc. Issue #192 specifies `sourceType` as a free-form String; the enum is a deliberate divergence — it prevents typos, enables per-source-type config resolution, and forces new consumers through a platform-api release gate. Acceptable for a pre-release platform with no external consumers.

#### DeliveryAttempt record

Replace `notificationId` with `sourceId` + `sourceType`:

```java
public record DeliveryAttempt(
    String id,
    String sourceId,                // was notificationId — nullable (digests have no single source)
    DeliverySourceType sourceType,  // new — required, discriminator
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
)
```

Compact constructor: `Objects.requireNonNull(sourceType, "sourceType")`. `sourceId` nullable.

#### EngagementEvent record

Same replacement, plus `attemptId` becomes nullable (survives attempt purge via SET NULL cascade):

```java
public record EngagementEvent(
    String id,
    String attemptId,               // nullable after retention purge
    String sourceId,                // was notificationId, denormalized from attempt
    DeliverySourceType sourceType,  // denormalized from attempt
    String channelId,
    String userId,
    String tenancyId,
    EngagementType type,
    Instant recordedAt,
    String metadata
)
```

Compact constructor: `Objects.requireNonNull(sourceType, "sourceType")`. `attemptId` nullable (survives attempt retention purge via SET NULL). `sourceId` nullable (denormalized from attempt, may be null for digests).

#### DeliveryAttemptStore SPI

Method renames:

| Current | New |
|---------|-----|
| `findByNotificationId(String)` | `findBySource(String sourceId, DeliverySourceType sourceType)` |
| `findEngagementsByNotificationId(String)` | `findEngagementsBySource(String sourceId, DeliverySourceType sourceType)` |

`findBySource` and `findEngagementsBySource` require non-null `sourceId` — sourceless entries (pre-persist digests) are not queryable by source. Use `findById` for known attempts or `find(DeliveryAttemptQuery)` for filtered queries.

All other methods unchanged.

#### DeliveryAttemptQuery

Add optional `sourceType` filter:

```java
public record DeliveryAttemptQuery(
    String userId,
    String tenancyId,
    String channelId,
    DeliveryStatus status,
    DeliverySourceType sourceType,  // new — optional filter
    String cursor,
    int limit
)
```

JPA `find()` integration: the bespoke JPQL builder adds a conditional `AND e.sourceType = :sourceType` clause when `query.sourceType() != null`, matching the existing pattern for `userId`, `channelId`, and `status` filters. InMemory `find()` adds a matching `.filter()` predicate.

### 2. Store Implementations

#### NoOpDeliveryAttemptStore (platform/)

Method signatures change, return types unchanged (empty lists, null, no-op).

#### InMemoryDeliveryAttemptStore (delivery-tracking-inmem/)

- `findBySource`: dual-field filter on `sourceId + sourceType`
- `findEngagementsBySource`: same
- Eviction cascade-removes engagements keyed by evicted attempt ID — volatile store has no scheduled retention sweep, so independent lifecycle without cascade would leak engagement events without bound

#### JpaDeliveryAttemptStore (delivery-tracking-jpa/)

Entity changes:

```java
// DeliveryAttemptEntity
@Column(name = "source_id")
public String sourceId;

@Enumerated(EnumType.STRING)
@Column(name = "source_type", nullable = false, length = 30)
public DeliverySourceType sourceType;

// EngagementEventEntity
@Column(name = "attempt_id", length = 36)  // now nullable
public String attemptId;

@Column(name = "source_id")
public String sourceId;

@Enumerated(EnumType.STRING)
@Column(name = "source_type", nullable = false, length = 30)
public DeliverySourceType sourceType;
```

JPQL queries updated to filter on `sourceId AND sourceType`.

### 3. Flyway Migration (V3002)

```sql
-- delivery_attempt: add source columns, populate, drop notification_id
ALTER TABLE delivery_attempt ADD COLUMN source_id VARCHAR(255);
ALTER TABLE delivery_attempt ADD COLUMN source_type VARCHAR(30);
UPDATE delivery_attempt SET source_id = notification_id, source_type = 'NOTIFICATION';
ALTER TABLE delivery_attempt ALTER COLUMN source_type SET NOT NULL;
ALTER TABLE delivery_attempt DROP COLUMN notification_id;
DROP INDEX IF EXISTS idx_delivery_attempt_notification;
CREATE INDEX idx_delivery_attempt_source ON delivery_attempt (source_id, source_type);

-- engagement_event: same treatment + FK change
ALTER TABLE engagement_event ADD COLUMN source_id VARCHAR(255);
ALTER TABLE engagement_event ADD COLUMN source_type VARCHAR(30);
UPDATE engagement_event SET source_id = notification_id, source_type = 'NOTIFICATION';
ALTER TABLE engagement_event ALTER COLUMN source_type SET NOT NULL;
ALTER TABLE engagement_event DROP COLUMN notification_id;
DROP INDEX IF EXISTS idx_engagement_event_notification;
CREATE INDEX idx_engagement_event_source ON engagement_event (source_id, source_type);

-- Break CASCADE → SET NULL, make attempt_id nullable
ALTER TABLE engagement_event ALTER COLUMN attempt_id DROP NOT NULL;
ALTER TABLE engagement_event DROP CONSTRAINT engagement_event_attempt_id_fkey;
ALTER TABLE engagement_event ADD CONSTRAINT engagement_event_attempt_id_fkey
    FOREIGN KEY (attempt_id) REFERENCES delivery_attempt(id) ON DELETE SET NULL;
```

### 4. Notification-Dispatch Changes

#### DeliveryTracker

Replace `notificationId` parameter with `sourceId + sourceType` across all five public methods:

```java
// Immediate delivery
public void recordSuccess(String channelId, NotificationInput input,
                           String sourceId, DeliverySourceType sourceType)

public void recordFailure(String channelId, NotificationInput input,
                           String sourceId, DeliverySourceType sourceType,
                           NotificationSeverity guaranteedMinSeverity, String failureReason)

// Digest delivery
public DeliveryAttempt preRecordDigest(String channelId, DigestSummary summary,
                                       NotificationSeverity guaranteedMinSeverity)
// Internal: sourceId = null, sourceType = NOTIFICATION (digests are batched notifications)

public void confirmDigestSuccess(DeliveryAttempt preRecorded)
// Internal: reads preRecorded.sourceId() + preRecorded.sourceType()

public void confirmDigestFailure(DeliveryAttempt preRecorded, String failureReason)
// Internal: reads preRecorded.sourceId() + preRecorded.sourceType()
```

`preRecordDigest` signature is unchanged — it has no notificationId parameter today (digests have no single source). Internally it constructs a DeliveryAttempt with `sourceId=null, sourceType=NOTIFICATION`. `confirmDigestSuccess` and `confirmDigestFailure` read the source fields from the pre-recorded attempt rather than accessing `notificationId`.

#### NotificationDispatcher

Passes `DeliverySourceType.NOTIFICATION`. The notification ID is null at dispatch time (notification not yet stored) — this is the existing behaviour and is correct for fire-and-forget.

#### InAppEngagementBridge

The one real coupling point:

```java
var attempts = store.findBySource(notification.id(), DeliverySourceType.NOTIFICATION);
```

Explicitly declares its notification context via the enum.

#### EngagementRecorder

Constructs `EngagementEvent` from a `DeliveryAttempt` — reads `attempt.sourceId()` and `attempt.sourceType()` to denormalize onto the event record. The constructor call changes from `attempt.notificationId()` to `attempt.sourceId()` and adds `attempt.sourceType()`.

#### DeliveryRetryProcessor

Three explicit `new DeliveryAttempt(...)` constructor calls (`processAttempt` success path, `advanceOrExpire`, `expire`) currently pass `attempt.notificationId()`. Each changes to `attempt.sourceId(), attempt.sourceType()`.

#### Other consumers

`EngagementCallbackResource`, `DigestFlushScheduler` — structurally unchanged. `EngagementCallbackResource` uses `findById` and passes the attempt to `recorder.record()`. `DigestFlushScheduler` calls `DeliveryTracker` methods whose signatures are unchanged (digest methods have no notificationId parameter).

### 5. Retention Architecture

Independent lifecycle management for attempts and engagements, configurable per source type.

#### Two independent sweeps

**Attempt sweep** — daily at 03:00, status-aware:

```java
void attemptRetentionPurge() {
    for (DeliverySourceType sourceType : DeliverySourceType.values()) {
        int attemptDays = resolveAttemptRetention(sourceType);
        int failedDays = resolveFailedAttemptRetention(sourceType);

        purgeAttempts(sourceType, DeliveryStatus.DELIVERED, attemptDays);
        purgeAttempts(sourceType, DeliveryStatus.EXPIRED, attemptDays);
        purgeAttempts(sourceType, DeliveryStatus.FAILED, failedDays);
        purgeStaleRetrying(sourceType, attemptDays);
    }
    purgeOrphanedPrePersist(claimTimeout);
}
```

`purgeOrphanedPrePersist` catches pre-recorded digest attempts that were never confirmed (status=RETRYING, nextRetryAt IS NULL, older than `claimTimeout`). This runs outside the per-source-type loop — orphaned pre-persist records are a timing issue, not a retention policy, and the 5-minute claimTimeout is the correct cutoff regardless of source type.

When attempts are deleted, `ON DELETE SET NULL` nullifies `engagement_event.attempt_id` — engagements survive.

**Engagement sweep** — daily at 03:30 (after attempt sweep):

```java
void engagementRetentionPurge() {
    for (DeliverySourceType sourceType : DeliverySourceType.values()) {
        int engagementDays = resolveEngagementRetention(sourceType);
        purgeEngagements(sourceType, engagementDays);
    }
}
```

#### Configuration

**Config namespace migration:** the existing keys under `casehub.delivery.tracking.*` move to `casehub.delivery.retention.*`. The new namespace separates the retention policy concern from the store module ("tracking") and supports the hierarchical per-source-type config structure. Any deployment overrides of the old keys must be migrated.

**Default retention change:** the global attempt retention default changes from 90 days to 30 days. This reflects a shorter default appropriate for a multi-consumer delivery infrastructure. The per-source-type override mechanism can restore the prior 90-day behavior for notifications — see example below. This change has no operational impact on a pre-release platform with no production data.

```properties
# Global defaults
casehub.delivery.retention.attempt-days=30
casehub.delivery.retention.failed-attempt-days=365
casehub.delivery.retention.engagement-days=90

# Per-source-type overrides (example: restore 90-day notification retention)
casehub.delivery.retention."notification".attempt-days=90
casehub.delivery.retention."notification".engagement-days=365
```

Resolution: source-type-specific value wins if present, otherwise global default.

#### InMemory store

No scheduled retention — volatile, size-based eviction only. Eviction cascade-removes engagements keyed by evicted attempt IDs (see §2).

### 6. Test Strategy

All changes modify existing test classes — no new test classes.

**platform-api** — record construction, null validation (sourceType required, sourceId nullable, attemptId nullable on EngagementEvent).

**delivery-tracking-inmem** — `findBySource` dual-field filtering, no cross-type collisions, engagement cascade-removed on attempt eviction.

**delivery-tracking-jpa** — JPQL correctness, ON DELETE SET NULL behaviour, per-source-type retention sweeps (attempt and engagement independent), orphaned pre-persist purge preserves current claimTimeout behavior, query with sourceType filter, migration data population.

**notification-dispatch** — sourceId + sourceType threaded through all five DeliveryTracker methods, InAppEngagementBridge uses `findBySource(notificationId, NOTIFICATION)`, EngagementRecorder denormalizes correctly with sourceType, retry processor preserves source fields across retry cycles.

## Modules Affected

| Module | Changes |
|--------|---------|
| `platform-api` | DeliveryAttempt, EngagementEvent, DeliveryAttemptStore, DeliveryAttemptQuery, DeliverySourceType (new) |
| `platform` | NoOpDeliveryAttemptStore |
| `delivery-tracking-inmem` | InMemoryDeliveryAttemptStore |
| `delivery-tracking-jpa` | JpaDeliveryAttemptStore, DeliveryAttemptEntity, EngagementEventEntity, V3002 migration, retention refactor |
| `notification-dispatch` | DeliveryTracker, NotificationDispatcher, InAppEngagementBridge, EngagementRecorder, DeliveryRetryProcessor |

## Not In Scope

- New DeliverySourceType values beyond NOTIFICATION — added when consumers integrate
- External repo propagation — qhorus, engine consume the SPI but don't reference delivery tracking today
