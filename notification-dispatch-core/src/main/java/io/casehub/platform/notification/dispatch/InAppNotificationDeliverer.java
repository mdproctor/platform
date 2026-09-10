package io.casehub.platform.notification.dispatch;

import io.casehub.platform.api.delivery.DeliveryChannelDescriptor;
import io.casehub.platform.api.delivery.DeliveryChannelRegistry;
import io.casehub.platform.api.delivery.DeliveryChannels;
import io.casehub.platform.api.delivery.DeliveryResult;
import io.casehub.platform.api.delivery.NotificationDeliverer;
import io.casehub.platform.api.notification.NotificationInput;
import io.casehub.platform.api.notification.NotificationSeverity;
import io.casehub.platform.api.notification.NotificationStore;

import org.jboss.logging.Logger;

public class InAppNotificationDeliverer implements NotificationDeliverer {

    private static final Logger LOG = Logger.getLogger(InAppNotificationDeliverer.class);

    private final NotificationStore notificationStore;

    public InAppNotificationDeliverer(final NotificationStore notificationStore,
                                      final DeliveryChannelRegistry channelRegistry) {
        this.notificationStore = notificationStore;
        channelRegistry.register(
                new DeliveryChannelDescriptor(
                        DeliveryChannels.IN_APP,
                        "In-App Inbox",
                        false,
                        true,
                        NotificationSeverity.INFO,
                        null,
                        null,
                        null),
                this);
    }

    @Override
    public String channelId() {
        return DeliveryChannels.IN_APP;
    }

    @Override
    public DeliveryResult deliver(final NotificationInput notification) {
        try {
            notificationStore.store(notification);
            return new DeliveryResult(true, null);
        } catch (Exception e) {
            LOG.warnf(e, "Failed to store in-app notification for user '%s'", notification.userId());
            return new DeliveryResult(false, e.getMessage());
        }
    }
}
