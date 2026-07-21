package io.casehub.platform.notification.dispatch;

import io.casehub.platform.api.delivery.DeliveryAttempt;
import io.casehub.platform.api.delivery.DeliverySourceType;
import io.casehub.platform.api.delivery.DeliveryStatus;
import io.casehub.platform.api.delivery.DeliveryType;
import io.casehub.platform.api.delivery.EngagementRecorded;
import io.casehub.platform.api.delivery.EngagementType;
import io.casehub.platform.api.util.UUIDv7;
import io.casehub.platform.delivery.tracking.inmem.InMemoryDeliveryAttemptStore;
import jakarta.enterprise.event.Event;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

import static org.assertj.core.api.Assertions.assertThat;

class EngagementRecorderTest {

    private InMemoryDeliveryAttemptStore store;
    private List<EngagementRecorded> firedEvents;
    private EngagementRecorder recorder;

    @BeforeEach
    void setUp() {
        store = new InMemoryDeliveryAttemptStore(10000);
        firedEvents = new ArrayList<>();
        recorder = new EngagementRecorder(store, new CapturingEngagementEvent(firedEvents), true);
    }

    @Test
    void recordStoresEventAndFiresCdiEvent() {
        var attempt = deliveredAttempt();
        store.store(attempt);
        recorder.record(attempt, EngagementType.OPENED, null);
        var events = store.findEngagementsByAttemptId(attempt.id());
        assertThat(events).hasSize(1);
        assertThat(events.getFirst().type()).isEqualTo(EngagementType.OPENED);
        assertThat(events.getFirst().attemptId()).isEqualTo(attempt.id());
        assertThat(events.getFirst().sourceId()).isEqualTo(attempt.sourceId());
        assertThat(events.getFirst().channelId()).isEqualTo(attempt.channelId());
        assertThat(events.getFirst().userId()).isEqualTo(attempt.userId());
        assertThat(events.getFirst().tenancyId()).isEqualTo(attempt.tenancyId());
        assertThat(firedEvents).hasSize(1);
        assertThat(firedEvents.getFirst().event().type()).isEqualTo(EngagementType.OPENED);
    }

    @Test
    void recordWithMetadata() {
        var attempt = deliveredAttempt();
        store.store(attempt);
        recorder.record(attempt, EngagementType.CLICKED, "{\"url\":\"https://example.com\"}");
        var events = store.findEngagementsByAttemptId(attempt.id());
        assertThat(events.getFirst().metadata()).isEqualTo("{\"url\":\"https://example.com\"}");
    }

    @Test
    void noOpWhenDisabled() {
        var disabledRecorder = new EngagementRecorder(store, new CapturingEngagementEvent(firedEvents), false);
        var attempt = deliveredAttempt();
        store.store(attempt);
        disabledRecorder.record(attempt, EngagementType.OPENED, null);
        assertThat(store.findEngagementsByAttemptId(attempt.id())).isEmpty();
        assertThat(firedEvents).isEmpty();
    }

    private DeliveryAttempt deliveredAttempt() {
        return new DeliveryAttempt(
                UUIDv7.generate(), "notif-1", DeliverySourceType.NOTIFICATION, "email", "user-1", "tenant-1",
                DeliveryType.IMMEDIATE, DeliveryStatus.DELIVERED, 1,
                Instant.now(), Instant.now(), Instant.now(), null, null, "{}",
                null, null);
    }

    static class CapturingEngagementEvent implements Event<EngagementRecorded> {
        private final List<EngagementRecorded> events;
        CapturingEngagementEvent(List<EngagementRecorded> events) { this.events = events; }
        @Override public void fire(EngagementRecorded e) { events.add(e); }
        @Override public <U extends EngagementRecorded> Event<U> select(Class<U> c, java.lang.annotation.Annotation... a) { throw new UnsupportedOperationException(); }
        @Override public <U extends EngagementRecorded> Event<U> select(jakarta.enterprise.util.TypeLiteral<U> t, java.lang.annotation.Annotation... a) { throw new UnsupportedOperationException(); }
        @Override public Event<EngagementRecorded> select(java.lang.annotation.Annotation... a) { throw new UnsupportedOperationException(); }
        @Override public CompletionStage<EngagementRecorded> fireAsync(EngagementRecorded e) { events.add(e); return CompletableFuture.completedFuture(e); }
        @Override public CompletionStage<EngagementRecorded> fireAsync(EngagementRecorded e, jakarta.enterprise.event.NotificationOptions o) { return fireAsync(e); }
    }
}
