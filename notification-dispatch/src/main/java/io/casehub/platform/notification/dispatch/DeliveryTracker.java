package io.casehub.platform.notification.dispatch;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.casehub.platform.api.delivery.DeliveryAttempt;
import io.casehub.platform.api.delivery.DeliveryAttemptStore;
import io.casehub.platform.api.delivery.DeliverySourceType;
import io.casehub.platform.api.delivery.DeliveryStatus;
import io.casehub.platform.api.delivery.DeliveryType;
import io.casehub.platform.api.delivery.DigestSummary;
import io.casehub.platform.api.notification.NotificationInput;
import io.casehub.platform.api.notification.NotificationSeverity;
import io.casehub.platform.api.util.UUIDv7;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;

@ApplicationScoped
public class DeliveryTracker {

    private static final Logger LOG = Logger.getLogger(DeliveryTracker.class);

    private final DeliveryAttemptStore store;
    private final ObjectMapper objectMapper;
    private final Duration baseDelay;

    @Inject
    public DeliveryTracker(DeliveryAttemptStore store,
                           ObjectMapper objectMapper,
                           @ConfigProperty(name = "casehub.delivery.retry.base-delay", defaultValue = "30s")
                           Duration baseDelay) {
        this.store = store;
        this.objectMapper = objectMapper;
        this.baseDelay = baseDelay;
    }

    public void recordSuccess(String channelId, NotificationInput input,
                              String sourceId, DeliverySourceType sourceType) {
        var now = Instant.now();
        try {
            store.store(new DeliveryAttempt(
                    UUIDv7.generate(), sourceId, sourceType, channelId,
                    input.userId(), input.tenancyId(),
                    DeliveryType.IMMEDIATE, DeliveryStatus.DELIVERED, 1,
                    now, now, now, null, null,
                    serialize(input), null, null));
        } catch (Exception e) {
            LOG.warnf(e, "Failed to record delivery success for channel '%s', user '%s'",
                      channelId, input.userId());
        }
    }

    public void recordFailure(String channelId, NotificationInput input,
                              String sourceId, DeliverySourceType sourceType,
                              NotificationSeverity guaranteedMinSeverity, String failureReason) {
        var now = Instant.now();
        boolean retryEligible = guaranteedMinSeverity != null
                                && input.severity().isAtLeast(guaranteedMinSeverity);
        try {
            store.store(new DeliveryAttempt(
                    UUIDv7.generate(), sourceId, sourceType, channelId,
                    input.userId(), input.tenancyId(),
                    DeliveryType.IMMEDIATE,
                    retryEligible ? DeliveryStatus.RETRYING : DeliveryStatus.FAILED,
                    1, now, now, null,
                    retryEligible ? now.plus(baseDelay) : null,
                    failureReason,
                    serialize(input), null, null));
        } catch (Exception e) {
            LOG.warnf(e, "Failed to record delivery failure for channel '%s', user '%s'",
                      channelId, input.userId());
        }
    }

    public DeliveryAttempt preRecordDigest(String channelId, DigestSummary summary,
                                           NotificationSeverity guaranteedMinSeverity) {
        var now = Instant.now();
        NotificationSeverity maxSeverity = summary.notifications().stream()
                                                  .map(NotificationInput::severity)
                                                  .max(Comparator.comparingInt(Enum::ordinal))
                                                  .orElse(NotificationSeverity.INFO);

        boolean retryEligible = guaranteedMinSeverity != null
                                && maxSeverity.isAtLeast(guaranteedMinSeverity);

        var attempt = new DeliveryAttempt(
                UUIDv7.generate(), null, DeliverySourceType.NOTIFICATION, channelId,
                summary.userId(), summary.tenancyId(),
                DeliveryType.DIGEST,
                retryEligible ? DeliveryStatus.RETRYING : DeliveryStatus.FAILED,
                0, now, null, null,
                null,
                null,
                serialize(summary), null, null);
        try {
            store.store(attempt);
        } catch (Exception e) {
            LOG.warnf(e, "Failed to pre-record digest delivery for channel '%s', user '%s'",
                      channelId, summary.userId());
        }
        return attempt;
    }

    public void confirmDigestSuccess(DeliveryAttempt preRecorded) {
        var now = Instant.now();
        try {
            store.update(new DeliveryAttempt(
                    preRecorded.id(), preRecorded.sourceId(), preRecorded.sourceType(),
                    preRecorded.channelId(),
                    preRecorded.userId(), preRecorded.tenancyId(), preRecorded.deliveryType(),
                    DeliveryStatus.DELIVERED, preRecorded.attemptCount() + 1,
                    preRecorded.createdAt(), now, now, null, null, preRecorded.payload(),
                    preRecorded.firstOpenedAt(), preRecorded.firstClickedAt()));
        } catch (Exception e) {
            LOG.warnf(e, "Failed to confirm digest success for attempt '%s'", preRecorded.id());
        }
    }

    public void confirmDigestFailure(DeliveryAttempt preRecorded, String failureReason) {
        var now = Instant.now();
        try {
            store.update(new DeliveryAttempt(
                    preRecorded.id(), preRecorded.sourceId(), preRecorded.sourceType(),
                    preRecorded.channelId(),
                    preRecorded.userId(), preRecorded.tenancyId(), preRecorded.deliveryType(),
                    DeliveryStatus.RETRYING, 1,
                    preRecorded.createdAt(), now, null,
                    now.plus(baseDelay),
                    failureReason, preRecorded.payload(),
                    preRecorded.firstOpenedAt(), preRecorded.firstClickedAt()));
        } catch (Exception e) {
            LOG.warnf(e, "Failed to confirm digest failure for attempt '%s'", preRecorded.id());
        }
    }

    private String serialize(Object payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            LOG.errorf(e, "Failed to serialize delivery payload");
            return "{}";
        }
    }
}
