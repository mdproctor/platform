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
import io.casehub.platform.api.identity.TenancyConstants;
import io.casehub.platform.api.preferences.PlatformPreferenceKeys;
import io.casehub.platform.api.preferences.PreferenceProvider;
import io.casehub.platform.api.preferences.SettingsScope;
import org.jboss.logging.Logger;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Consumer;

public class DeliveryRetryProcessor {

    private static final Logger LOG = Logger.getLogger(DeliveryRetryProcessor.class);

    private final DeliveryAttemptStore store;
    private final DeliveryChannelRegistry channelRegistry;
    private final ObjectMapper objectMapper;
    private final Consumer<DeliveryExhausted> exhaustedCallback;
    private final PreferenceProvider preferenceProvider;
    private final Duration baseDelay;
    private final Duration maxDelay;
    private final int jitterMs;
    private final int batchSize;

    public DeliveryRetryProcessor(DeliveryAttemptStore store,
                                  DeliveryChannelRegistry channelRegistry,
                                  ObjectMapper objectMapper,
                                  Consumer<DeliveryExhausted> exhaustedCallback,
                                  PreferenceProvider preferenceProvider,
                                  Duration baseDelay,
                                  Duration maxDelay,
                                  int jitterMs,
                                  int batchSize) {
        this.store = store;
        this.channelRegistry = channelRegistry;
        this.objectMapper = objectMapper;
        this.exhaustedCallback = exhaustedCallback;
        this.preferenceProvider = preferenceProvider;
        this.baseDelay = baseDelay;
        this.maxDelay = maxDelay;
        this.jitterMs = jitterMs;
        this.batchSize = batchSize;
    }

    public void tick() {
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
        int maxRetries = preferenceProvider
                .resolve(SettingsScope.root(TenancyConstants.PLATFORM_TENANT_ID))
                .getOrDefault(PlatformPreferenceKeys.DELIVERY_RETRY_MAX_RETRIES)
                .value();
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
        if (exhaustedCallback != null) {
            exhaustedCallback.accept(new DeliveryExhausted(expired));
        }
    }

    private Instant computeBackoff(int attemptCount) {
        long delayMs = Math.min(
                baseDelay.toMillis() * (1L << (attemptCount - 1)),
                maxDelay.toMillis());
        long jitter = ThreadLocalRandom.current().nextLong(0, jitterMs + 1L);
        return Instant.now().plusMillis(delayMs + jitter);
    }
}
