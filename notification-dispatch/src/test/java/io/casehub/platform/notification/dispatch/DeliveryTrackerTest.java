package io.casehub.platform.notification.dispatch;

import io.casehub.platform.api.delivery.DeliveryAttemptQuery;
import io.casehub.platform.api.delivery.DeliveryAttemptStore;
import io.casehub.platform.api.delivery.DeliverySourceType;
import io.casehub.platform.api.delivery.DeliveryStatus;
import io.casehub.platform.api.delivery.DeliveryType;
import io.casehub.platform.api.delivery.DigestSummary;
import io.casehub.platform.api.notification.NotificationInput;
import io.casehub.platform.api.notification.NotificationSeverity;
import io.casehub.platform.api.notification.NotificationSource;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DeliveryTrackerTest {

    private DeliveryTracker tracker;
    private DeliveryAttemptStore store;

    @BeforeEach
    void setUp() {
        store = new io.casehub.platform.delivery.tracking.inmem.InMemoryDeliveryAttemptStore(10000);
        var objectMapper = new ObjectMapper();
        objectMapper.findAndRegisterModules();
        tracker = new DeliveryTracker(store, objectMapper, Duration.ofSeconds(30));
    }

    @Test
    void recordsSuccessfulDelivery() {
        var input = testInput(NotificationSeverity.INFO);
        tracker.recordSuccess("email", input, "notif-1", DeliverySourceType.NOTIFICATION);

        var attempts = store.findBySource("notif-1", DeliverySourceType.NOTIFICATION);
        assertThat(attempts).hasSize(1);
        assertThat(attempts.getFirst().status()).isEqualTo(DeliveryStatus.DELIVERED);
        assertThat(attempts.getFirst().deliveredAt()).isNotNull();
        assertThat(attempts.getFirst().deliveryType()).isEqualTo(DeliveryType.IMMEDIATE);
        assertThat(attempts.getFirst().channelId()).isEqualTo("email");
        assertThat(attempts.getFirst().attemptCount()).isEqualTo(1);
    }

    @Test
    void recordsFailureWithRetry() {
        var input = testInput(NotificationSeverity.URGENT);
        tracker.recordFailure("email", input, "notif-1", DeliverySourceType.NOTIFICATION,
                NotificationSeverity.WARNING, "connection refused");

        var attempts = store.findBySource("notif-1", DeliverySourceType.NOTIFICATION);
        assertThat(attempts).hasSize(1);
        assertThat(attempts.getFirst().status()).isEqualTo(DeliveryStatus.RETRYING);
        assertThat(attempts.getFirst().nextRetryAt()).isNotNull();
        assertThat(attempts.getFirst().attemptCount()).isEqualTo(1);
        assertThat(attempts.getFirst().failureReason()).isEqualTo("connection refused");
    }

    @Test
    void recordsFailureWithoutRetryWhenBelowThreshold() {
        var input = testInput(NotificationSeverity.INFO);
        tracker.recordFailure("email", input, "notif-1", DeliverySourceType.NOTIFICATION,
                NotificationSeverity.WARNING, "connection refused");

        var attempts = store.findBySource("notif-1", DeliverySourceType.NOTIFICATION);
        assertThat(attempts).hasSize(1);
        assertThat(attempts.getFirst().status()).isEqualTo(DeliveryStatus.FAILED);
        assertThat(attempts.getFirst().nextRetryAt()).isNull();
    }

    @Test
    void recordsFailureWithoutRetryWhenThresholdNull() {
        var input = testInput(NotificationSeverity.URGENT);
        tracker.recordFailure("email", input, "notif-1", DeliverySourceType.NOTIFICATION,
                null, "connection refused");

        var attempts = store.findBySource("notif-1", DeliverySourceType.NOTIFICATION);
        assertThat(attempts).hasSize(1);
        assertThat(attempts.getFirst().status()).isEqualTo(DeliveryStatus.FAILED);
    }

    @Test
    void preRecordDigestCreatesRetryingWithNullNextRetryAt() {
        var summary = testDigestSummary(NotificationSeverity.URGENT);
        var preRecorded = tracker.preRecordDigest("email", summary,
                NotificationSeverity.WARNING);

        assertThat(preRecorded).isNotNull();
        var page = store.find(new DeliveryAttemptQuery(
                summary.userId(), summary.tenancyId(), null, null, null, null, 10));
        assertThat(page.attempts()).anyMatch(a ->
                a.id().equals(preRecorded.id())
                        && a.status() == DeliveryStatus.RETRYING
                        && a.nextRetryAt() == null
                        && a.attemptCount() == 0
                        && a.deliveryType() == DeliveryType.DIGEST);
    }

    @Test
    void preRecordDigestBelowThresholdCreatesFailed() {
        var summary = testDigestSummary(NotificationSeverity.INFO);
        var preRecorded = tracker.preRecordDigest("email", summary,
                NotificationSeverity.WARNING);

        var page = store.find(new DeliveryAttemptQuery(
                summary.userId(), summary.tenancyId(), null, null, null, null, 10));
        assertThat(page.attempts()).anyMatch(a ->
                a.id().equals(preRecorded.id())
                        && a.status() == DeliveryStatus.FAILED);
    }

    @Test
    void confirmDigestSuccess() {
        var summary = testDigestSummary(NotificationSeverity.URGENT);
        var preRecorded = tracker.preRecordDigest("email", summary,
                NotificationSeverity.WARNING);

        tracker.confirmDigestSuccess(preRecorded);

        var page = store.find(new DeliveryAttemptQuery(
                summary.userId(), summary.tenancyId(), null, null, null, null, 10));
        assertThat(page.attempts()).anyMatch(a ->
                a.id().equals(preRecorded.id())
                        && a.status() == DeliveryStatus.DELIVERED
                        && a.deliveredAt() != null);
    }

    @Test
    void confirmDigestFailure() {
        var summary = testDigestSummary(NotificationSeverity.URGENT);
        var preRecorded = tracker.preRecordDigest("email", summary,
                NotificationSeverity.WARNING);

        tracker.confirmDigestFailure(preRecorded, "SMTP timeout");

        var page = store.find(new DeliveryAttemptQuery(
                summary.userId(), summary.tenancyId(), null, null, null, null, 10));
        assertThat(page.attempts()).anyMatch(a ->
                a.id().equals(preRecorded.id())
                        && a.status() == DeliveryStatus.RETRYING
                        && a.nextRetryAt() != null
                        && a.attemptCount() == 1
                        && a.failureReason().equals("SMTP timeout"));
    }

    @Test
    void payloadSerializesAndDeserializes() {
        var input = testInput(NotificationSeverity.WARNING);
        tracker.recordSuccess("email", input, "notif-1", DeliverySourceType.NOTIFICATION);

        var attempts = store.findBySource("notif-1", DeliverySourceType.NOTIFICATION);
        assertThat(attempts.getFirst().payload()).contains("Test Notification");
    }

    // --- helpers ---

    private NotificationInput testInput(NotificationSeverity severity) {
        return new NotificationInput(
                "user-1", "tenant-1", "Test Notification", "body",
                "test.event", severity, null,
                new NotificationSource("evt-1", "work-item", "wi-1", "actor-1"));
    }

    private DigestSummary testDigestSummary(NotificationSeverity maxSeverity) {
        var now = Instant.now();
        return new DigestSummary(
                "user-1", "tenant-1", "email",
                List.of(testInput(maxSeverity)),
                now.minus(Duration.ofHours(4)), now, null);
    }
}
