package io.casehub.platform.notification.dispatch;

import io.casehub.platform.api.delivery.DeliveryAttemptStore;
import io.casehub.platform.api.delivery.DeliverySourceType;
import io.casehub.platform.api.delivery.DeliveryChannels;
import io.casehub.platform.api.delivery.EngagementType;
import io.casehub.platform.api.notification.NotificationStatus;
import io.casehub.platform.api.notification.NotificationStatusChanged;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.ObservesAsync;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

@ApplicationScoped
public class InAppEngagementBridge {

    private static final Logger LOG = Logger.getLogger(InAppEngagementBridge.class);

    private final DeliveryAttemptStore store;
    private final EngagementRecorder recorder;
    private final boolean enabled;

    @Inject
    public InAppEngagementBridge(DeliveryAttemptStore store,
                                 EngagementRecorder recorder,
                                 @ConfigProperty(name = "casehub.delivery.engagement.enabled", defaultValue = "false")
                                 boolean enabled) {
        this.store = store;
        this.recorder = recorder;
        this.enabled = enabled;
    }

    void onStatusChanged(@ObservesAsync NotificationStatusChanged event) {
        if (!enabled) {
            return;
        }
        var notification = event.notification();
        var newStatus = notification.status();
        EngagementType type;
        if (newStatus == NotificationStatus.READ) {
            type = EngagementType.OPENED;
        } else if (newStatus == NotificationStatus.DISMISSED) {
            type = EngagementType.DISMISSED;
        } else {
            return;
        }
        var attempts = store.findBySource(notification.id(), DeliverySourceType.NOTIFICATION);
        var inAppAttempt = attempts.stream()
                .filter(a -> DeliveryChannels.IN_APP.equals(a.channelId()))
                .findFirst()
                .orElse(null);
        if (inAppAttempt == null) {
            LOG.debugf("No in-app delivery attempt for notification '%s' — skipping engagement bridge",
                    notification.id());
            return;
        }
        recorder.record(inAppAttempt, type, null);
    }
}
