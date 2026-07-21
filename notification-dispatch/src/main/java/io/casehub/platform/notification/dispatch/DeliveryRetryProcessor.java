package io.casehub.platform.notification.dispatch;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.casehub.platform.api.delivery.DeliveryAttempt;
import io.casehub.platform.api.delivery.DeliveryAttemptStore;
import io.casehub.platform.api.delivery.DeliveryChannelRegistry;
import io.casehub.platform.api.delivery.DeliveryExhausted;
import io.casehub.platform.api.delivery.DeliveryResult;
import io.casehub.platform.api.delivery.DeliveryStatus;
import io.casehub.platform.api.delivery.DeliveryType;
import io.casehub.platform.api.delivery.DigestSummary;
import io.casehub.platform.api.notification.NotificationInput;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Event;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ThreadLocalRandom;

@ApplicationScoped
public class DeliveryRetryProcessor {

    private static final Logger LOG = Logger.getLogger(DeliveryRetryProcessor.class);

    private final DeliveryAttemptStore store;
    private final DeliveryChannelRegistry channelRegistry;
    private final ObjectMapper objectMapper;
    private final Event<DeliveryExhausted> exhaustedEvent;
    private final int maxRetries;
    private final Duration baseDelay;
    private final Duration maxDelay;
    private final int jitterMs;
    private final int batchSize;

    @Inject
    public DeliveryRetryProcessor(DeliveryAttemptStore store,
                                  DeliveryChannelRegistry channelRegistry,
                                  ObjectMapper objectMapper,
                                  Event<DeliveryExhausted> exhaustedEvent,
                                  @ConfigProperty(name = "casehub.delivery.retry.max-retries", defaultValue = "5")
                                  int maxRetries,
                                  @ConfigProperty(name = "casehub.delivery.retry.base-delay", defaultValue = "30s")
                                  Duration baseDelay,
                                  @ConfigProperty(name = "casehub.delivery.retry.max-delay", defaultValue = "30m")
                                  Duration maxDelay,
                                  @ConfigProperty(name = "casehub.delivery.retry.jitter-ms", defaultValue = "5000")
                                  int jitterMs,
                                  @ConfigProperty(name = "casehub.delivery.retry.batch-size", defaultValue = "50")
                                  int batchSize) {
        this.store = store;
        this.channelRegistry = channelRegistry;
        this.objectMapper = objectMapper;
        this.exhaustedEvent = exhaustedEvent;
        this.maxRetries = maxRetries;
        this.baseDelay = baseDelay;
        this.maxDelay = maxDelay;
        this.jitterMs = jitterMs;
        this.batchSize = batchSize;
    }

    @Scheduled(every = "${casehub.delivery.retry.tick-interval:30s}")
    void tick() {
        var now = Instant.now();
        var batch = store.claimRetryable(now, batchSize);
        for (var attempt : batch) {
            processAttempt(attempt, now);
        }
    }

    private void processAttempt(DeliveryAttempt attempt, Instant now) {
        try {
            var deliverer = channelRegistry.resolveDeliverer(attempt.channelId()).orElse(null);
            if (deliverer == null) {
                expire(attempt, now, "channel not registered");
                return;
            }

            DeliveryResult result;
            if (attempt.deliveryType() == DeliveryType.IMMEDIATE) {
                var input = objectMapper.readValue(attempt.payload(), NotificationInput.class);
                result = deliverer.deliver(input);
            } else {
                var summary = objectMapper.readValue(attempt.payload(), DigestSummary.class);
                result = deliverer.deliverDigest(summary);
            }

            if (result.success()) {
                store.update(new DeliveryAttempt(
                        attempt.id(), attempt.sourceId(), attempt.sourceType(), attempt.channelId(),
                        attempt.userId(), attempt.tenancyId(), attempt.deliveryType(),
                        DeliveryStatus.DELIVERED, attempt.attemptCount() + 1,
                        attempt.createdAt(), now, now, null, null, attempt.payload(),
                        attempt.firstOpenedAt(), attempt.firstClickedAt()));
            } else {
                advanceOrExpire(attempt, now, result.failureReason());
            }
        } catch (Exception e) {
            LOG.warnf(e, "Retry failed for attempt %s", attempt.id());
            advanceOrExpire(attempt, now, e.getMessage());
        }
    }

    private void advanceOrExpire(DeliveryAttempt attempt, Instant now, String failureReason) {
        int newCount = attempt.attemptCount() + 1;
        if (newCount > maxRetries) {
            expire(attempt, now, failureReason);
        } else {
            Instant nextRetry = computeBackoff(newCount);
            store.update(new DeliveryAttempt(
                    attempt.id(), attempt.sourceId(), attempt.sourceType(), attempt.channelId(),
                    attempt.userId(), attempt.tenancyId(), attempt.deliveryType(),
                    DeliveryStatus.RETRYING, newCount,
                    attempt.createdAt(), now, null,
                    nextRetry, failureReason, attempt.payload(),
                    attempt.firstOpenedAt(), attempt.firstClickedAt()));
        }
    }

    private void expire(DeliveryAttempt attempt, Instant now, String failureReason) {
        var expired = new DeliveryAttempt(
                attempt.id(), attempt.sourceId(), attempt.sourceType(), attempt.channelId(),
                attempt.userId(), attempt.tenancyId(), attempt.deliveryType(),
                DeliveryStatus.EXPIRED, attempt.attemptCount() + 1,
                attempt.createdAt(), now, null, null,
                failureReason, attempt.payload(),
                attempt.firstOpenedAt(), attempt.firstClickedAt());
        store.update(expired);
        exhaustedEvent.fireAsync(new DeliveryExhausted(expired));
    }

    private Instant computeBackoff(int attemptCount) {
        long delayMs = Math.min(
                baseDelay.toMillis() * (1L << (attemptCount - 1)),
                maxDelay.toMillis());
        long jitter = ThreadLocalRandom.current().nextLong(0, jitterMs + 1L);
        return Instant.now().plusMillis(delayMs + jitter);
    }
}
