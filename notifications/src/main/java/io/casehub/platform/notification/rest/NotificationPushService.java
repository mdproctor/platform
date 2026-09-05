package io.casehub.platform.notification.rest;

import io.casehub.pages.push.EventBroadcaster;
import io.casehub.platform.api.governance.SessionIsolator;
import io.casehub.platform.api.notification.AllNotificationsRead;
import io.casehub.platform.api.notification.NotificationCreated;
import io.casehub.platform.api.notification.NotificationStatusChanged;
import io.casehub.platform.api.notification.NotificationStore;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.ObservesAsync;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

@ApplicationScoped
public class NotificationPushService {

    private static final Logger LOG = Logger.getLogger(NotificationPushService.class);

    private final EventBroadcaster broadcaster;
    private final NotificationStore store;
    private final SessionIsolator sessionIsolator;

    @Inject
    public NotificationPushService(
            EventBroadcaster broadcaster,
            NotificationStore store,
            SessionIsolator sessionIsolator) {
        this.broadcaster = broadcaster;
        this.store = store;
        this.sessionIsolator = sessionIsolator;
    }

    void onNotificationCreated(@ObservesAsync NotificationCreated event) {
        var notification = event.notification();
        String userId = notification.userId();

        broadcaster.broadcast("notifications:" + userId + ":new", notification);
        broadcastUnreadCount(userId, notification.tenancyId());
    }

    void onNotificationStatusChanged(@ObservesAsync NotificationStatusChanged event) {
        var notification = event.notification();
        String userId = notification.userId();

        broadcaster.broadcast("notifications:" + userId + ":updated", notification);
        broadcastUnreadCount(userId, notification.tenancyId());
    }

    void onAllNotificationsRead(@ObservesAsync AllNotificationsRead event) {
        broadcastUnreadCount(event.userId(), event.tenancyId());
    }

    private void broadcastUnreadCount(String userId, String tenancyId) {
        try {
            long count = sessionIsolator.runIsolated(
                    () -> store.unreadCount(userId, tenancyId));
            broadcaster.broadcast("notifications:" + userId + ":unread-count",
                    new UnreadCount(count));
        } catch (Exception e) {
            LOG.errorf(e, "Failed to broadcast unread count for user %s", userId);
        }
    }

    private record UnreadCount(long count) {}
}
