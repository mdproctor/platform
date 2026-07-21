package io.casehub.platform.delivery;

import io.casehub.platform.api.delivery.DeliveryAttempt;
import io.casehub.platform.api.delivery.DeliveryAttemptPage;
import io.casehub.platform.api.delivery.DeliveryAttemptQuery;
import io.casehub.platform.api.delivery.DeliveryAttemptStore;
import io.casehub.platform.api.delivery.DeliverySourceType;
import io.casehub.platform.api.delivery.EngagementEvent;
import io.quarkus.arc.DefaultBean;
import jakarta.enterprise.context.ApplicationScoped;

import java.time.Instant;
import java.util.List;

@DefaultBean
@ApplicationScoped
public class NoOpDeliveryAttemptStore implements DeliveryAttemptStore {

    @Override
    public void store(DeliveryAttempt attempt) {}

    @Override
    public void update(DeliveryAttempt attempt) {}

    @Override
    public DeliveryAttempt findById(String id) {
        return null;
    }


    @Override
    public List<DeliveryAttempt> claimRetryable(Instant now, int batchSize) {
        return List.of();
    }

    @Override
    public DeliveryAttemptPage find(DeliveryAttemptQuery query) {
        return new DeliveryAttemptPage(List.of(), null);
    }

    @Override
    public List<DeliveryAttempt> findBySource(String sourceId, DeliverySourceType sourceType) {
        return List.of();
    }

    @Override
    public void recordEngagement(EngagementEvent event) {}

    @Override
    public List<EngagementEvent> findEngagementsByAttemptId(String attemptId) {
        return List.of();
    }

    @Override
    public List<EngagementEvent> findEngagementsBySource(String sourceId, DeliverySourceType sourceType) {
        return List.of();
    }
}
