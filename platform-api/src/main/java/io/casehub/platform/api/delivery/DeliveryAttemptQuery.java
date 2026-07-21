package io.casehub.platform.api.delivery;

import java.util.Objects;

public record DeliveryAttemptQuery(
        String userId,
        String tenancyId,
        String channelId,
        DeliveryStatus status,
        DeliverySourceType sourceType,
        String cursor,
        int limit
) {
    public DeliveryAttemptQuery {
        Objects.requireNonNull(tenancyId, "tenancyId");
        if (limit <= 0) {throw new IllegalArgumentException("limit must be positive");}
    }
}
