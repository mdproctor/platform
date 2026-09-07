package io.casehub.platform.notification.dispatch;

import io.casehub.platform.api.delivery.DestinationScope;
import io.casehub.platform.api.delivery.NotificationDeliverer;
import io.casehub.platform.api.notification.NotificationSeverity;

import java.util.Objects;

public record ResolvedChannel(
        String channelId,
        NotificationDeliverer deliverer,
        boolean suppressed,
        boolean digested,
        NotificationSeverity guaranteedMinSeverity,
        DestinationScope destinationScope
) {
    public ResolvedChannel {
        Objects.requireNonNull(channelId, "channelId");
        Objects.requireNonNull(deliverer, "deliverer");
    }
}
