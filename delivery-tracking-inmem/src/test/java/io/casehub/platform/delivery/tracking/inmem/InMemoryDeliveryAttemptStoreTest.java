package io.casehub.platform.delivery.tracking.inmem;

import io.casehub.platform.api.delivery.DeliveryAttempt;
import io.casehub.platform.api.delivery.DeliveryAttemptQuery;
import io.casehub.platform.api.delivery.DeliverySourceType;
import io.casehub.platform.api.delivery.DeliveryStatus;
import io.casehub.platform.api.delivery.DeliveryType;
import io.casehub.platform.api.delivery.EngagementEvent;
import io.casehub.platform.api.delivery.EngagementType;
import io.casehub.platform.api.util.UUIDv7;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class InMemoryDeliveryAttemptStoreTest {

    private InMemoryDeliveryAttemptStore store;

    @BeforeEach
    void setUp() {
        store = new InMemoryDeliveryAttemptStore(10000);
    }

    @Test
    void storeAndFindBySource() {
        var attempt = attempt("notif-1", DeliveryStatus.DELIVERED);
        store.store(attempt);

        var found = store.findBySource("notif-1", DeliverySourceType.NOTIFICATION);
        assertThat(found).hasSize(1);
        assertThat(found.getFirst().id()).isEqualTo(attempt.id());
        assertThat(found.getFirst().channelId()).isEqualTo("email");
        assertThat(found.getFirst().deliveryType()).isEqualTo(DeliveryType.IMMEDIATE);
    }

    @Test
    void claimRetryableReturnsOnlyEligible() {
        var now    = Instant.now();
        var past   = attempt(DeliveryStatus.RETRYING, now.minus(Duration.ofMinutes(1)));
        var future = attempt(DeliveryStatus.RETRYING, now.plus(Duration.ofMinutes(5)));
        store.store(past);
        store.store(future);

        var claimed = store.claimRetryable(now, 10);
        assertThat(claimed).hasSize(1);
        assertThat(claimed.getFirst().id()).isEqualTo(past.id());
    }

    @Test
    void claimRetryableAdvancesNextRetryAt() {
        var now       = Instant.now();
        var retryable = attempt(DeliveryStatus.RETRYING, now.minus(Duration.ofMinutes(1)));
        store.store(retryable);

        store.claimRetryable(now, 10);

        var afterClaim = store.findById(retryable.id());
        assertThat(afterClaim.nextRetryAt()).isAfter(now);
    }

    @Test
    void claimRetryableRespectsMaxBatchSize() {
        var now = Instant.now();
        for (int i = 0; i < 5; i++) {
            store.store(attempt(DeliveryStatus.RETRYING, now.minus(Duration.ofMinutes(1))));
        }

        var claimed = store.claimRetryable(now, 2);
        assertThat(claimed).hasSize(2);
    }

    @Test
    void findByQueryFilters() {
        store.store(attempt("notif-1", "user-a", "tenant-1", "email", DeliveryStatus.DELIVERED));
        store.store(attempt("notif-2", "user-b", "tenant-1", "sms", DeliveryStatus.FAILED));
        store.store(attempt("notif-3", "user-a", "tenant-1", "email", DeliveryStatus.RETRYING));

        var byUser = store.find(new DeliveryAttemptQuery("user-a", "tenant-1", null, null, null, null, 10));
        assertThat(byUser.attempts()).hasSize(2);

        var byChannel = store.find(new DeliveryAttemptQuery(null, "tenant-1", "sms", null, null, null, 10));
        assertThat(byChannel.attempts()).hasSize(1);

        var byStatus = store.find(new DeliveryAttemptQuery(null, "tenant-1", null, DeliveryStatus.DELIVERED, null, null, 10));
        assertThat(byStatus.attempts()).hasSize(1);
    }

    @Test
    void updateModifiesExistingRecord() {
        var attempt = attempt("src-1", DeliveryStatus.RETRYING);
        store.store(attempt);

        var updated = new DeliveryAttempt(
                attempt.id(), attempt.sourceId(), attempt.sourceType(), attempt.channelId(),
                attempt.userId(), attempt.tenancyId(), attempt.deliveryType(),
                DeliveryStatus.DELIVERED, 2,
                attempt.createdAt(), Instant.now(), Instant.now(), null, null, attempt.payload(),
                attempt.firstOpenedAt(), attempt.firstClickedAt());
        store.update(updated);

        var found = store.findBySource("src-1", DeliverySourceType.NOTIFICATION);
        assertThat(found.getFirst().status()).isEqualTo(DeliveryStatus.DELIVERED);
        assertThat(found.getFirst().attemptCount()).isEqualTo(2);
    }

    @Test
    void evictsWhenMaxSizeExceeded() {
        store = new InMemoryDeliveryAttemptStore(3);
        for (int i = 0; i < 5; i++) {
            store.store(attempt("notif-" + i, DeliveryStatus.DELIVERED));
        }

        var all = store.find(new DeliveryAttemptQuery(null, "tenant-1", null, null, null, null, 100));
        assertThat(all.attempts()).hasSize(3);
    }

    @Test
    void findBySourceReturnsEmptyForUnknown() {
        var all = store.findBySource("unknown", DeliverySourceType.NOTIFICATION);
        assertThat(all).isEmpty();
    }

    @Test
    void claimRetryableSkipsNullNextRetryAt() {
        var attempt = new DeliveryAttempt(
                UUIDv7.generate(), "src-1", DeliverySourceType.NOTIFICATION, "email", "user-1", "tenant-1",
                DeliveryType.DIGEST, DeliveryStatus.RETRYING, 0,
                Instant.now(), null, null, null, null, "{}", null, null);
        store.store(attempt);

        var claimed = store.claimRetryable(Instant.now(), 10);
        assertThat(claimed).isEmpty();
    }

    @Test
    void findWithPagination() {
        for (int i = 0; i < 5; i++) {
            store.store(attempt("notif-" + i, DeliveryStatus.DELIVERED));
        }

        var page1 = store.find(new DeliveryAttemptQuery(null, "tenant-1", null, null, null, null, 2));
        assertThat(page1.attempts()).hasSize(2);
        assertThat(page1.nextCursor()).isNotNull();

        var page2 = store.find(new DeliveryAttemptQuery(null, "tenant-1", null, null, null, page1.nextCursor(), 2));
        assertThat(page2.attempts()).hasSize(2);

        var page3 = store.find(new DeliveryAttemptQuery(null, "tenant-1", null, null, null, page2.nextCursor(), 2));
        assertThat(page3.attempts()).hasSize(1);
        assertThat(page3.nextCursor()).isNull();
    }

    // --- helpers ---


    @Test
    void recordEngagementStoresEvent() {
        var attempt = attempt("notif-1", DeliveryStatus.DELIVERED);
        store.store(attempt);
        var event = new EngagementEvent(
                "eng-1", attempt.id(), "notif-1", DeliverySourceType.NOTIFICATION, "email", "user-1", "tenant-1",
                EngagementType.OPENED, Instant.now(), null);
        store.recordEngagement(event);
        var events = store.findEngagementsByAttemptId(attempt.id());
        assertThat(events).hasSize(1);
        assertThat(events.getFirst().type()).isEqualTo(EngagementType.OPENED);
    }

    @Test
    void recordEngagementSetsFirstOpenedAt() {
        var attempt = attempt("notif-1", DeliveryStatus.DELIVERED);
        store.store(attempt);
        var now = Instant.now();
        store.recordEngagement(new EngagementEvent(
                "eng-1", attempt.id(), "notif-1", DeliverySourceType.NOTIFICATION, "email", "user-1", "tenant-1",
                EngagementType.OPENED, now, null));
        var updated = store.findBySource("notif-1", DeliverySourceType.NOTIFICATION).getFirst();
        assertThat(updated.firstOpenedAt()).isEqualTo(now);
        assertThat(updated.firstClickedAt()).isNull();
    }

    @Test
    void recordEngagementSetsFirstClickedAt() {
        var attempt = attempt("notif-1", DeliveryStatus.DELIVERED);
        store.store(attempt);
        var now = Instant.now();
        store.recordEngagement(new EngagementEvent(
                "eng-1", attempt.id(), "notif-1", DeliverySourceType.NOTIFICATION, "email", "user-1", "tenant-1",
                EngagementType.CLICKED, now, null));
        var updated = store.findBySource("notif-1", DeliverySourceType.NOTIFICATION).getFirst();
        assertThat(updated.firstClickedAt()).isEqualTo(now);
        assertThat(updated.firstOpenedAt()).isNull();
    }

    @Test
    void recordEngagementFirstWriteWinsForSummary() {
        var attempt = attempt("notif-1", DeliveryStatus.DELIVERED);
        store.store(attempt);
        var first  = Instant.now().minusSeconds(60);
        var second = Instant.now();
        store.recordEngagement(new EngagementEvent(
                "eng-1", attempt.id(), "notif-1", DeliverySourceType.NOTIFICATION, "email", "user-1", "tenant-1",
                EngagementType.OPENED, first, null));
        store.recordEngagement(new EngagementEvent(
                "eng-2", attempt.id(), "notif-1", DeliverySourceType.NOTIFICATION, "email", "user-1", "tenant-1",
                EngagementType.OPENED, second, null));
        var updated = store.findBySource("notif-1", DeliverySourceType.NOTIFICATION).getFirst();
        assertThat(updated.firstOpenedAt()).isEqualTo(first);
        var events = store.findEngagementsByAttemptId(attempt.id());
        assertThat(events).hasSize(2);
    }

    @Test
    void findEngagementsBySourceAcrossAttempts() {
        var a1 = attempt("notif-1", DeliveryStatus.DELIVERED);
        var a2 = attempt("notif-1", "user-2", "tenant-1", "email", DeliveryStatus.DELIVERED);
        store.store(a1);
        store.store(a2);
        store.recordEngagement(new EngagementEvent(
                "eng-1", a1.id(), "notif-1", DeliverySourceType.NOTIFICATION, "email", "user-1", "tenant-1",
                EngagementType.OPENED, Instant.now(), null));
        store.recordEngagement(new EngagementEvent(
                "eng-2", a2.id(), "notif-1", DeliverySourceType.NOTIFICATION, "email", "user-2", "tenant-1",
                EngagementType.CLICKED, Instant.now(), null));
        var events = store.findEngagementsBySource("notif-1", DeliverySourceType.NOTIFICATION);
        assertThat(events).hasSize(2);
    }

    @Test
    void evictionCascadesToEngagementEvents() {
        var smallStore = new InMemoryDeliveryAttemptStore(1);
        var a1         = attempt("notif-1", DeliveryStatus.DELIVERED);
        smallStore.store(a1);
        smallStore.recordEngagement(new EngagementEvent(
                "eng-1", a1.id(), "notif-1", DeliverySourceType.NOTIFICATION, "email", "user-1", "tenant-1",
                EngagementType.OPENED, Instant.now(), null));
        var a2 = attempt("notif-2", DeliveryStatus.DELIVERED);
        smallStore.store(a2);
        assertThat(smallStore.findEngagementsByAttemptId(a1.id())).isEmpty();
    }

    @Test
    void concurrentRecordEngagementDoesNotCorrupt() throws Exception {
        var attempt = attempt("notif-1", DeliveryStatus.DELIVERED);
        store.store(attempt);
        int threadCount = 10;
        var latch = new java.util.concurrent.CountDownLatch(1);
        var threads = new java.util.ArrayList<Thread>();
        for (int i = 0; i < threadCount; i++) {
            int idx = i;
            var t = new Thread(() -> {
                try { latch.await(); } catch (InterruptedException ignored) {}
                store.recordEngagement(new EngagementEvent(
                        "eng-" + idx, attempt.id(), "notif-1", DeliverySourceType.NOTIFICATION, "email", "user-1", "tenant-1",
                        EngagementType.OPENED, Instant.now(), null));
            });
            threads.add(t);
            t.start();
        }
        latch.countDown();
        for (var t : threads) { t.join(5000); }
        var events = store.findEngagementsByAttemptId(attempt.id());
        assertThat(events).hasSize(threadCount);
    }

    private DeliveryAttempt attempt(String sourceId, DeliveryStatus status) {
        return attempt(sourceId, "user-1", "tenant-1", "email", status);
    }

    private DeliveryAttempt attempt(DeliveryStatus status, Instant nextRetryAt) {
        return new DeliveryAttempt(
                UUIDv7.generate(), null, DeliverySourceType.NOTIFICATION, "email", "user-1", "tenant-1",
                DeliveryType.IMMEDIATE, status, 1,
                Instant.now(), Instant.now(), null, nextRetryAt, "timeout", "{}", null, null);
    }

    private DeliveryAttempt attempt(String sourceId, String userId, String tenancyId,
                                    String channelId, DeliveryStatus status) {
        return new DeliveryAttempt(
                UUIDv7.generate(), sourceId, DeliverySourceType.NOTIFICATION,
                channelId, userId, tenancyId,
                DeliveryType.IMMEDIATE, status, 1,
                Instant.now(), Instant.now(),
                status == DeliveryStatus.DELIVERED ? Instant.now() : null,
                null, null, "{}", null, null);
    }
}
