package io.casehub.platform.delivery.tracking.jpa;

import io.casehub.platform.api.delivery.DeliveryAttempt;
import io.casehub.platform.api.delivery.DeliveryAttemptQuery;
import io.casehub.platform.api.delivery.DeliveryAttemptStore;
import io.casehub.platform.api.delivery.DeliverySourceType;
import io.casehub.platform.api.delivery.DeliveryStatus;
import io.casehub.platform.api.delivery.DeliveryType;
import io.casehub.platform.api.delivery.EngagementEvent;
import io.casehub.platform.api.delivery.EngagementType;
import io.casehub.platform.api.util.UUIDv7;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

@QuarkusTest
class JpaDeliveryAttemptStoreTest {

    @Inject
    DeliveryAttemptStore store;

    @Test
    @TestTransaction
    void storeAndFindBySource() {
        var attempt = attempt("notif-1", DeliveryStatus.DELIVERED);
        store.store(attempt);

        var found = store.findBySource("notif-1", DeliverySourceType.NOTIFICATION);
        assertThat(found).hasSize(1);
        assertThat(found.getFirst().id()).isEqualTo(attempt.id());
        assertThat(found.getFirst().channelId()).isEqualTo("email");
        assertThat(found.getFirst().status()).isEqualTo(DeliveryStatus.DELIVERED);
        assertThat(found.getFirst().deliveryType()).isEqualTo(DeliveryType.IMMEDIATE);
    }

    @Test
    @TestTransaction
    void updateModifiesExistingRecord() {
        var attempt = attempt("src-1", DeliveryStatus.RETRYING);
        store.store(attempt);

        var updated = new DeliveryAttempt(
                attempt.id(), attempt.sourceId(), attempt.sourceType(), attempt.channelId(),
                attempt.userId(), attempt.tenancyId(), attempt.deliveryType(),
                DeliveryStatus.DELIVERED, 3,
                attempt.createdAt(), Instant.now(), Instant.now(), null, null, attempt.payload(),
                attempt.firstOpenedAt(), attempt.firstClickedAt());
        store.update(updated);

        var found = store.findBySource("src-1", DeliverySourceType.NOTIFICATION);
        assertThat(found.getFirst().status()).isEqualTo(DeliveryStatus.DELIVERED);
        assertThat(found.getFirst().attemptCount()).isEqualTo(3);
        assertThat(found.getFirst().deliveredAt()).isNotNull();
    }

    @Test
    @TestTransaction
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
    @TestTransaction
    void findWithCursorPagination() {
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

    @Test
    @TestTransaction
    void findBySourceReturnsEmptyForUnknown() {
        assertThat(store.findBySource("unknown", DeliverySourceType.NOTIFICATION)).isEmpty();
    }

    // --- helpers ---


    @Test
    @TestTransaction
    void recordEngagementStoresAndUpdatesSummary() {
        var attempt = attempt("notif-1", DeliveryStatus.DELIVERED);
        store.store(attempt);
        var now = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
        var event = new EngagementEvent(
                UUIDv7.generate(), attempt.id(), "notif-1", DeliverySourceType.NOTIFICATION, "email", "user-1", "tenant-1",
                EngagementType.OPENED, now, "{\"source\":\"pixel\"}");
        store.recordEngagement(event);
        var found = store.findEngagementsByAttemptId(attempt.id());
        assertThat(found).hasSize(1);
        assertThat(found.getFirst().type()).isEqualTo(EngagementType.OPENED);
        assertThat(found.getFirst().metadata()).isEqualTo("{\"source\":\"pixel\"}");
        var updated = store.findBySource("notif-1", DeliverySourceType.NOTIFICATION).getFirst();
        assertThat(updated.firstOpenedAt()).isEqualTo(now);
    }

    @Test
    @TestTransaction
    void recordEngagementFirstWriteWins() {
        var attempt = attempt("notif-1", DeliveryStatus.DELIVERED);
        store.store(attempt);
        var first  = Instant.now().minusSeconds(60).truncatedTo(java.time.temporal.ChronoUnit.MICROS);
        var second = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
        store.recordEngagement(new EngagementEvent(
                UUIDv7.generate(), attempt.id(), "notif-1", DeliverySourceType.NOTIFICATION, "email", "user-1", "tenant-1",
                EngagementType.OPENED, first, null));
        store.recordEngagement(new EngagementEvent(
                UUIDv7.generate(), attempt.id(), "notif-1", DeliverySourceType.NOTIFICATION, "email", "user-1", "tenant-1",
                EngagementType.OPENED, second, null));
        var updated = store.findBySource("notif-1", DeliverySourceType.NOTIFICATION).getFirst();
        assertThat(updated.firstOpenedAt()).isEqualTo(first);
    }

    @Test
    @TestTransaction
    void findEngagementsBySourceAcrossAttempts() {
        var a1 = attempt("notif-1", DeliveryStatus.DELIVERED);
        var a2 = attempt("notif-1", "user-2", "tenant-1", "email", DeliveryStatus.DELIVERED);
        store.store(a1);
        store.store(a2);
        store.recordEngagement(new EngagementEvent(
                UUIDv7.generate(), a1.id(), "notif-1", DeliverySourceType.NOTIFICATION, "email", "user-1", "tenant-1",
                EngagementType.OPENED, Instant.now(), null));
        store.recordEngagement(new EngagementEvent(
                UUIDv7.generate(), a2.id(), "notif-1", DeliverySourceType.NOTIFICATION, "email", "user-2", "tenant-1",
                EngagementType.CLICKED, Instant.now(), null));
        assertThat(store.findEngagementsBySource("notif-1", DeliverySourceType.NOTIFICATION)).hasSize(2);
    }

    private DeliveryAttempt attempt(String sourceId, DeliveryStatus status) {
        return attempt(sourceId, "user-1", "tenant-1", "email", status);
    }

    private DeliveryAttempt attempt(String sourceId, String userId, String tenancyId,
                                    String channelId, DeliveryStatus status) {
        return new DeliveryAttempt(
                UUIDv7.generate(), sourceId, DeliverySourceType.NOTIFICATION,
                channelId, userId, tenancyId,
                DeliveryType.IMMEDIATE, status, 1,
                Instant.now(), Instant.now(),
                status == DeliveryStatus.DELIVERED ? Instant.now() : null,
                status == DeliveryStatus.RETRYING ? Instant.now().minus(Duration.ofMinutes(1)) : null,
                null, "{}", null, null);
    }
}
