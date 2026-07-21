package io.casehub.platform.api.delivery;

import java.time.Instant;
import java.util.Objects;

public record EngagementEvent(
        String id,
        String attemptId,
        String sourceId,
        DeliverySourceType sourceType,
        String channelId,
        String userId,
        String tenancyId,
        EngagementType type,
        Instant recordedAt,
        String metadata
) {
    public EngagementEvent {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(sourceType, "sourceType");
        Objects.requireNonNull(channelId, "channelId");
        Objects.requireNonNull(userId, "userId");
        Objects.requireNonNull(tenancyId, "tenancyId");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(recordedAt, "recordedAt");
    }
}
