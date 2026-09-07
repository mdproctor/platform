package io.casehub.platform.notification;

import io.casehub.platform.api.notification.Notification;
import io.casehub.platform.api.notification.NotificationInput;
import io.casehub.platform.api.notification.NotificationPage;
import io.casehub.platform.api.notification.NotificationQuery;
import io.casehub.platform.api.notification.NotificationStatus;
import io.casehub.platform.api.notification.NotificationStore;
import io.casehub.platform.api.util.UUIDv7;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public class NoOpNotificationStore implements NotificationStore {

    @Override public Notification store(final NotificationInput input) { return toNotification(input); }
    @Override public List<Notification> storeAll(final List<NotificationInput> inputs) { return inputs.stream().map(this::toNotification).toList(); }
    @Override public NotificationPage find(final NotificationQuery query) { return new NotificationPage(List.of(), null); }
    @Override public long unreadCount(final String userId, final String tenancyId) { return 0L; }
    @Override public Optional<Notification> markRead(final String id, final String userId, final String tenancyId) { return Optional.empty(); }
    @Override public Optional<Notification> dismiss(final String id, final String userId, final String tenancyId) { return Optional.empty(); }
    @Override public int markAllRead(final String userId, final String tenancyId) { return 0; }

    private Notification toNotification(final NotificationInput input) {
        return new Notification(UUIDv7.generate(), input.userId(), input.tenancyId(),
                input.title(), input.body(), input.category(), input.severity(),
                input.actionUrl(), input.source(), NotificationStatus.UNREAD, Instant.now(), null, null);
    }
}
