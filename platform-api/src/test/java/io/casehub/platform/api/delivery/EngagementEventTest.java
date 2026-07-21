package io.casehub.platform.api.delivery;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

class EngagementEventTest {

    @Test
    void rejectsNullId() {
        assertThatNullPointerException().isThrownBy(() ->
                                                            new EngagementEvent(null, "attempt-1", "notif-1", DeliverySourceType.NOTIFICATION,
                                                                                "email", "user-1", "tenant-1", EngagementType.OPENED, Instant.now(), null));
    }

    @Test
    void rejectsNullSourceType() {
        assertThatNullPointerException().isThrownBy(() ->
                                                            new EngagementEvent("id-1", "attempt-1", "notif-1", null,
                                                                                "email", "user-1", "tenant-1", EngagementType.OPENED, Instant.now(), null));
    }

    @Test
    void rejectsNullType() {
        assertThatNullPointerException().isThrownBy(() ->
                                                            new EngagementEvent("id-1", "attempt-1", "notif-1", DeliverySourceType.NOTIFICATION,
                                                                                "email", "user-1", "tenant-1", null, Instant.now(), null));
    }

    @Test
    void acceptsNullableFields() {
        var event = new EngagementEvent("id-1", null, null, DeliverySourceType.NOTIFICATION,
                                        "email", "user-1", "tenant-1", EngagementType.CLICKED, Instant.now(), null);
        assertThat(event.attemptId()).isNull();
        assertThat(event.sourceId()).isNull();
        assertThat(event.metadata()).isNull();
    }

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

    @Test
    void rawEngagementRejectsNullAttemptId() {
        assertThatNullPointerException().isThrownBy(() ->
                new RawEngagement(null, EngagementType.OPENED, null));
    }

    @Test
    void rawEngagementRejectsNullType() {
        assertThatNullPointerException().isThrownBy(() ->
                new RawEngagement("attempt-1", null, null));
    }

    @Test
    void rawEngagementAcceptsNullMetadata() {
        var raw = new RawEngagement("attempt-1", EngagementType.CLICKED, null);
        assertThat(raw.metadata()).isNull();
    }

    @Test
    void engagementRecordedRejectsNullEvent() {
        assertThatNullPointerException().isThrownBy(() ->
                new EngagementRecorded(null));
    }
}
