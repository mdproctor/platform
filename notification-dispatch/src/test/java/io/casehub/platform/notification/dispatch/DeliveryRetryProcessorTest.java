package io.casehub.platform.notification.dispatch;

import io.casehub.platform.delivery.channel.inmem.InMemoryDeliveryChannelRegistry;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.casehub.platform.api.delivery.DeliveryAttempt;
import io.casehub.platform.api.delivery.DeliveryChannelDescriptor;
import io.casehub.platform.api.delivery.DeliveryChannels;
import io.casehub.platform.api.delivery.DeliveryExhausted;
import io.casehub.platform.api.delivery.DeliveryResult;
import io.casehub.platform.api.delivery.DeliverySourceType;
import io.casehub.platform.api.delivery.DeliveryStatus;
import io.casehub.platform.api.delivery.DeliveryType;
import io.casehub.platform.api.delivery.DigestSummary;
import io.casehub.platform.api.delivery.NotificationDeliverer;
import io.casehub.platform.api.notification.NotificationInput;
import io.casehub.platform.api.notification.NotificationSeverity;
import io.casehub.platform.api.notification.NotificationSource;
import io.casehub.platform.api.util.UUIDv7;
import io.casehub.platform.delivery.tracking.inmem.InMemoryDeliveryAttemptStore;
import jakarta.enterprise.event.Event;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DeliveryRetryProcessorTest {

    private InMemoryDeliveryAttemptStore store;
    private InMemoryDeliveryChannelRegistry channelRegistry;
    private ObjectMapper objectMapper;
    private List<DeliveryExhausted> exhaustedEvents;
    private DeliveryRetryProcessor processor;

    @BeforeEach
    void setUp() {
        store = new InMemoryDeliveryAttemptStore(10000);
        channelRegistry = new InMemoryDeliveryChannelRegistry();
        objectMapper = new ObjectMapper();
        objectMapper.findAndRegisterModules();
        exhaustedEvents = new ArrayList<>();

        processor = new DeliveryRetryProcessor(
                store, channelRegistry, objectMapper,
                new CapturingEvent(exhaustedEvents),
                5, Duration.ofSeconds(30), Duration.ofMinutes(30),
                5000, 50);
    }

    @Test
    void retriesAndDeliversSuccessfully() {
        channelRegistry.register(emailDescriptor(), new SuccessDeliverer());
        store.store(retryableAttempt(DeliveryType.IMMEDIATE));

        processor.tick();

        var page = store.find(new io.casehub.platform.api.delivery.DeliveryAttemptQuery(
                null, "tenant-1", null, null, null, null, 10));
        assertThat(page.attempts()).anyMatch(a ->
                a.status() == DeliveryStatus.DELIVERED && a.deliveredAt() != null);
    }

    @Test
    void incrementsAttemptCountOnFailure() {
        channelRegistry.register(emailDescriptor(), new FailingDeliverer());
        var attempt = retryableAttempt(DeliveryType.IMMEDIATE);
        store.store(attempt);

        processor.tick();

        var found = store.findBySource(attempt.sourceId(), DeliverySourceType.NOTIFICATION);
        assertThat(found.getFirst().attemptCount()).isEqualTo(2);
        assertThat(found.getFirst().status()).isEqualTo(DeliveryStatus.RETRYING);
        assertThat(found.getFirst().nextRetryAt()).isAfter(Instant.now());
    }

    @Test
    void expiresWhenMaxRetriesExceeded() {
        channelRegistry.register(emailDescriptor(), new FailingDeliverer());
        var attempt = retryableAttempt(DeliveryType.IMMEDIATE, 5);
        store.store(attempt);

        processor.tick();

        var found = store.findBySource(attempt.sourceId(), DeliverySourceType.NOTIFICATION);
        assertThat(found.getFirst().status()).isEqualTo(DeliveryStatus.EXPIRED);
    }

    @Test
    void firesDeliveryExhaustedOnExpiry() {
        channelRegistry.register(emailDescriptor(), new FailingDeliverer());
        store.store(retryableAttempt(DeliveryType.IMMEDIATE, 5));

        processor.tick();

        assertThat(exhaustedEvents).hasSize(1);
        assertThat(exhaustedEvents.getFirst().attempt().status()).isEqualTo(DeliveryStatus.EXPIRED);
    }

    @Test
    void handlesExceptionSameAsFailure() {
        channelRegistry.register(emailDescriptor(), new ThrowingDeliverer());
        store.store(retryableAttempt(DeliveryType.IMMEDIATE));

        processor.tick();

        var page = store.find(new io.casehub.platform.api.delivery.DeliveryAttemptQuery(
                null, "tenant-1", null, null, null, null, 10));
        assertThat(page.attempts().getFirst().attemptCount()).isEqualTo(2);
        assertThat(page.attempts().getFirst().status()).isEqualTo(DeliveryStatus.RETRYING);
    }

    @Test
    void handlesMissingDelivererByExpiring() {
        store.store(retryableAttempt(DeliveryType.IMMEDIATE));

        processor.tick();

        var page = store.find(new io.casehub.platform.api.delivery.DeliveryAttemptQuery(
                null, "tenant-1", null, null, null, null, 10));
        assertThat(page.attempts().getFirst().status()).isEqualTo(DeliveryStatus.EXPIRED);
        assertThat(page.attempts().getFirst().failureReason()).contains("channel not registered");
    }

    @Test
    void retriesDigestViaDeliverDigest() {
        var capturingDeliverer = new CapturingDigestRetryDeliverer();
        channelRegistry.register(emailDescriptor(), capturingDeliverer);
        store.store(retryableDigestAttempt());

        processor.tick();

        assertThat(capturingDeliverer.digestDelivered).isTrue();
    }

    @Test
    void skipsAttemptsWithFutureNextRetryAt() {
        channelRegistry.register(emailDescriptor(), new SuccessDeliverer());
        var attempt = new DeliveryAttempt(
                UUIDv7.generate(), "notif-1", DeliverySourceType.NOTIFICATION, DeliveryChannels.EMAIL, "user-1", "tenant-1",
                DeliveryType.IMMEDIATE, DeliveryStatus.RETRYING, 1,
                Instant.now(), Instant.now(), null,
                Instant.now().plus(Duration.ofHours(1)),
                "timeout", serialize(testInput()), null, null);
        store.store(attempt);

        processor.tick();

        var found = store.findBySource("notif-1", DeliverySourceType.NOTIFICATION);
        assertThat(found.getFirst().status()).isEqualTo(DeliveryStatus.RETRYING);
    }

    // --- helpers ---

    private DeliveryAttempt retryableAttempt(DeliveryType type) {
        return retryableAttempt(type, 1);
    }

    private DeliveryAttempt retryableAttempt(DeliveryType type, int attemptCount) {
        return new DeliveryAttempt(
                UUIDv7.generate(), "notif-1", DeliverySourceType.NOTIFICATION, DeliveryChannels.EMAIL, "user-1", "tenant-1",
                type, DeliveryStatus.RETRYING, attemptCount,
                Instant.now(), Instant.now(), null,
                Instant.now().minus(Duration.ofMinutes(1)),
                "previous failure", serialize(testInput()), null, null);
    }

    private DeliveryAttempt retryableDigestAttempt() {
        return new DeliveryAttempt(
                UUIDv7.generate(), null, DeliverySourceType.NOTIFICATION, DeliveryChannels.EMAIL, "user-1", "tenant-1",
                DeliveryType.DIGEST, DeliveryStatus.RETRYING, 1,
                Instant.now(), Instant.now(), null,
                Instant.now().minus(Duration.ofMinutes(1)),
                "previous failure", serialize(testDigestSummary()), null, null);
    }

    private NotificationInput testInput() {
        return new NotificationInput(
                "user-1", "tenant-1", "Test", "body",
                "test.event", NotificationSeverity.WARNING, null,
                new NotificationSource("evt-1", "work-item", "wi-1", "actor-1"));
    }

    private DigestSummary testDigestSummary() {
        var now = Instant.now();
        return new DigestSummary("user-1", "tenant-1", DeliveryChannels.EMAIL,
                List.of(testInput()), now.minus(Duration.ofHours(4)), now, null);
    }

    private String serialize(Object obj) {
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private DeliveryChannelDescriptor emailDescriptor() {
        return new DeliveryChannelDescriptor(DeliveryChannels.EMAIL, "Email",
                true, true, NotificationSeverity.INFO, null, NotificationSeverity.WARNING);
    }

    private static class SuccessDeliverer implements NotificationDeliverer {
        @Override public String channelId() { return DeliveryChannels.EMAIL; }
        @Override public DeliveryResult deliver(NotificationInput n) { return new DeliveryResult(true, null); }
    }

    private static class FailingDeliverer implements NotificationDeliverer {
        @Override public String channelId() { return DeliveryChannels.EMAIL; }
        @Override public DeliveryResult deliver(NotificationInput n) { return new DeliveryResult(false, "SMTP error"); }
    }

    private static class ThrowingDeliverer implements NotificationDeliverer {
        @Override public String channelId() { return DeliveryChannels.EMAIL; }
        @Override public DeliveryResult deliver(NotificationInput n) { throw new RuntimeException("Connection reset"); }
    }

    private static class CapturingDigestRetryDeliverer implements NotificationDeliverer {
        boolean digestDelivered = false;
        @Override public String channelId() { return DeliveryChannels.EMAIL; }
        @Override public DeliveryResult deliver(NotificationInput n) { return new DeliveryResult(true, null); }
        @Override public DeliveryResult deliverDigest(DigestSummary s) { digestDelivered = true; return new DeliveryResult(true, null); }
    }

    private static class CapturingEvent implements Event<DeliveryExhausted> {
        private final List<DeliveryExhausted> events;
        CapturingEvent(List<DeliveryExhausted> events) { this.events = events; }
        @Override public void fire(DeliveryExhausted e) { events.add(e); }
        @Override public <U extends DeliveryExhausted> jakarta.enterprise.event.Event<U> select(Class<U> c, java.lang.annotation.Annotation... a) { throw new UnsupportedOperationException(); }
        @Override public <U extends DeliveryExhausted> jakarta.enterprise.event.Event<U> select(jakarta.enterprise.util.TypeLiteral<U> t, java.lang.annotation.Annotation... a) { throw new UnsupportedOperationException(); }
        @Override public jakarta.enterprise.event.Event<DeliveryExhausted> select(java.lang.annotation.Annotation... a) { throw new UnsupportedOperationException(); }
        @Override public java.util.concurrent.CompletionStage<DeliveryExhausted> fireAsync(DeliveryExhausted e) { events.add(e); return java.util.concurrent.CompletableFuture.completedFuture(e); }
        @Override public java.util.concurrent.CompletionStage<DeliveryExhausted> fireAsync(DeliveryExhausted e, jakarta.enterprise.event.NotificationOptions o) { return fireAsync(e); }
    }
}
