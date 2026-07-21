package io.casehub.platform.api.delivery;

import java.time.Instant;
import java.util.List;

public interface DeliveryAttemptStore {
    void store(DeliveryAttempt attempt);
    void update(DeliveryAttempt attempt);

    DeliveryAttempt findById(String id);

    List<DeliveryAttempt> claimRetryable(Instant now, int batchSize);
    DeliveryAttemptPage find(DeliveryAttemptQuery query);

    List<DeliveryAttempt> findBySource(String sourceId, DeliverySourceType sourceType);

    void recordEngagement(EngagementEvent event);

    List<EngagementEvent> findEngagementsByAttemptId(String attemptId);

    List<EngagementEvent> findEngagementsBySource(String sourceId, DeliverySourceType sourceType);
}
