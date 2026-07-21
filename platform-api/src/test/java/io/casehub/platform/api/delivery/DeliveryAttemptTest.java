package io.casehub.platform.api.delivery;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

class DeliveryAttemptTest {

    @Test
    void rejectsNullId() {
        assertThatNullPointerException().isThrownBy(() ->
                                                            new DeliveryAttempt(null, null, DeliverySourceType.NOTIFICATION, "email", "user1", "tenant1",
                                                                                DeliveryType.IMMEDIATE, DeliveryStatus.DELIVERED, 1,
                                                                                Instant.now(), Instant.now(), Instant.now(), null, null, "{}", null, null));
    }

    @Test
    void rejectsNullChannelId() {
        assertThatNullPointerException().isThrownBy(() ->
                                                            new DeliveryAttempt("id1", null, DeliverySourceType.NOTIFICATION, null, "user1", "tenant1",
                                                                                DeliveryType.IMMEDIATE, DeliveryStatus.DELIVERED, 1,
                                                                                Instant.now(), Instant.now(), Instant.now(), null, null, "{}", null, null));
    }

    @Test
    void rejectsNullPayload() {
        assertThatNullPointerException().isThrownBy(() ->
                                                            new DeliveryAttempt("id1", null, DeliverySourceType.NOTIFICATION, "email", "user1", "tenant1",
                                                                                DeliveryType.IMMEDIATE, DeliveryStatus.DELIVERED, 1,
                                                                                Instant.now(), Instant.now(), Instant.now(), null, null, null, null, null));
    }

    @Test
    void rejectsNullSourceType() {
        assertThatNullPointerException().isThrownBy(() ->
                                                            new DeliveryAttempt("id1", null, null, "email", "user1", "tenant1",
                                                                                DeliveryType.IMMEDIATE, DeliveryStatus.DELIVERED, 1,
                                                                                Instant.now(), Instant.now(), Instant.now(), null, null, "{}", null, null));
    }


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
}
