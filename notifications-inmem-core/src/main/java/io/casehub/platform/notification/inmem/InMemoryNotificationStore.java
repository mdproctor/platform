package io.casehub.platform.notification.inmem;

import io.casehub.platform.api.notification.AllNotificationsRead;
import io.casehub.platform.api.notification.Notification;
import io.casehub.platform.api.notification.NotificationCreated;
import io.casehub.platform.api.notification.NotificationInput;
import io.casehub.platform.api.notification.NotificationPage;
import io.casehub.platform.api.notification.NotificationQuery;
import io.casehub.platform.api.notification.NotificationStatus;
import io.casehub.platform.api.notification.NotificationStatusChanged;
import io.casehub.platform.api.notification.NotificationStore;
import io.casehub.platform.api.util.UUIDv7;

import java.time.Instant;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

public class InMemoryNotificationStore implements NotificationStore {

    private final ConcurrentHashMap<String, Notification> store = new ConcurrentHashMap<>();
    private final int maxSize;
    private final Consumer<NotificationCreated> onCreated;
    private final Consumer<NotificationStatusChanged> onStatusChanged;
    private final Consumer<AllNotificationsRead> onAllRead;

    public InMemoryNotificationStore(int maxSize,
                                     Consumer<NotificationCreated> onCreated,
                                     Consumer<NotificationStatusChanged> onStatusChanged,
                                     Consumer<AllNotificationsRead> onAllRead) {
        this.maxSize = maxSize;
        this.onCreated = onCreated;
        this.onStatusChanged = onStatusChanged;
        this.onAllRead = onAllRead;
    }

    public InMemoryNotificationStore() {
        this(10000, null, null, null);
    }

    public InMemoryNotificationStore(int maxSize) {
        this(maxSize, null, null, null);
    }

    @Override
    public Notification store(NotificationInput input) {
        var notification = toNotification(input);
        evictIfNeeded(1);
        store.put(notification.id(), notification);
        fireNotificationCreated(notification);
        return notification;
    }

    @Override
    public List<Notification> storeAll(List<NotificationInput> inputs) {
        var notifications = inputs.stream()
                .map(this::toNotification)
                .toList();
        evictIfNeeded(notifications.size());
        notifications.forEach(n -> store.put(n.id(), n));
        notifications.forEach(this::fireNotificationCreated);
        return notifications;
    }

    @Override
    public NotificationPage find(NotificationQuery query) {
        var comparator = Comparator.comparing(Notification::createdAt)
                .thenComparing(Notification::id)
                .reversed();

        var filtered = store.values().stream()
                .filter(n -> n.userId().equals(query.userId()))
                .filter(n -> n.tenancyId().equals(query.tenancyId()))
                .filter(n -> query.status() == null || n.status() == query.status())
                .filter(n -> query.category() == null || n.category().equals(query.category()))
                .filter(n -> matchesCursor(n, query.cursor()))
                .sorted(comparator)
                .limit(query.limit() + 1)
                .toList();

        boolean hasMore = filtered.size() > query.limit();
        var notifications = hasMore ? filtered.subList(0, query.limit()) : filtered;
        String nextCursor = hasMore ? encodeCursor(notifications.get(notifications.size() - 1)) : null;

        return new NotificationPage(notifications, nextCursor);
    }

    @Override
    public long unreadCount(String userId, String tenancyId) {
        return store.values().stream()
                .filter(n -> n.userId().equals(userId))
                .filter(n -> n.tenancyId().equals(tenancyId))
                .filter(n -> n.status() == NotificationStatus.UNREAD)
                .count();
    }

    @Override
    public Optional<Notification> markRead(String id, String userId, String tenancyId) {
        var now = Instant.now();
        var result = new Object() {
            Notification updated = null;
            NotificationStatus previousStatus = null;
        };

        store.compute(id, (key, notification) -> {
            if (notification == null
                    || !notification.userId().equals(userId)
                    || !notification.tenancyId().equals(tenancyId)
                    || notification.status() == NotificationStatus.DISMISSED) {
                return notification;
            }

            result.previousStatus = notification.status();
            result.updated = new Notification(
                    notification.id(),
                    notification.userId(),
                    notification.tenancyId(),
                    notification.title(),
                    notification.body(),
                    notification.category(),
                    notification.severity(),
                    notification.actionUrl(),
                    notification.source(),
                    NotificationStatus.READ,
                    notification.createdAt(),
                    now,
                    notification.dismissedAt()
            );
            return result.updated;
        });

        if (result.updated != null) {
            fireNotificationStatusChanged(result.updated, result.previousStatus);
        }
        return Optional.ofNullable(result.updated);
    }

    @Override
    public Optional<Notification> dismiss(String id, String userId, String tenancyId) {
        var now = Instant.now();
        var result = new Object() {
            Notification updated = null;
            NotificationStatus previousStatus = null;
        };

        store.compute(id, (key, notification) -> {
            if (notification == null
                    || !notification.userId().equals(userId)
                    || !notification.tenancyId().equals(tenancyId)
                    || notification.status() == NotificationStatus.DISMISSED) {
                return notification;
            }

            result.previousStatus = notification.status();
            result.updated = new Notification(
                    notification.id(),
                    notification.userId(),
                    notification.tenancyId(),
                    notification.title(),
                    notification.body(),
                    notification.category(),
                    notification.severity(),
                    notification.actionUrl(),
                    notification.source(),
                    NotificationStatus.DISMISSED,
                    notification.createdAt(),
                    notification.readAt(),
                    now
            );
            return result.updated;
        });

        if (result.updated != null) {
            fireNotificationStatusChanged(result.updated, result.previousStatus);
        }
        return Optional.ofNullable(result.updated);
    }

    @Override
    public int markAllRead(String userId, String tenancyId) {
        var now = Instant.now();
        var count = new int[]{0};

        store.forEach((id, notification) -> {
            if (notification.userId().equals(userId)
                    && notification.tenancyId().equals(tenancyId)
                    && notification.status() == NotificationStatus.UNREAD) {
                store.computeIfPresent(id, (key, current) -> {
                    if (current.status() == NotificationStatus.UNREAD) {
                        count[0]++;
                        return new Notification(
                                current.id(),
                                current.userId(),
                                current.tenancyId(),
                                current.title(),
                                current.body(),
                                current.category(),
                                current.severity(),
                                current.actionUrl(),
                                current.source(),
                                NotificationStatus.READ,
                                current.createdAt(),
                                now,
                                current.dismissedAt()
                        );
                    }
                    return current;
                });
            }
        });

        if (count[0] > 0) {
            fireAllNotificationsRead(userId, tenancyId, count[0]);
        }
        return count[0];
    }

    private Notification toNotification(NotificationInput input) {
        return new Notification(
                UUIDv7.generate(),
                input.userId(),
                input.tenancyId(),
                input.title(),
                input.body(),
                input.category(),
                input.severity(),
                input.actionUrl(),
                input.source(),
                NotificationStatus.UNREAD,
                Instant.now(),
                null,
                null
        );
    }

    private void evictIfNeeded(int incoming) {
        int toEvict = store.size() + incoming - maxSize;
        if (toEvict <= 0) return;

        var oldest = store.values().stream()
                .sorted(Comparator.comparing(Notification::createdAt).thenComparing(Notification::id))
                .limit(toEvict)
                .map(Notification::id)
                .toList();

        oldest.forEach(store::remove);
    }

    private boolean matchesCursor(Notification n, String cursor) {
        if (cursor == null) return true;

        var decoded = decodeCursor(cursor);
        if (decoded == null) return true;

        long cursorTimestamp = decoded.timestampMs;
        String cursorId = decoded.id;

        long notificationTimestamp = n.createdAt().toEpochMilli();

        if (notificationTimestamp < cursorTimestamp) return true;
        if (notificationTimestamp > cursorTimestamp) return false;
        return n.id().compareTo(cursorId) < 0;
    }

    private String encodeCursor(Notification notification) {
        long timestampMs = notification.createdAt().toEpochMilli();
        String encoded = timestampMs + ":" + notification.id();
        return Base64.getUrlEncoder().withoutPadding().encodeToString(encoded.getBytes());
    }

    private CursorData decodeCursor(String cursor) {
        try {
            var decoded = new String(Base64.getUrlDecoder().decode(cursor));
            var parts = decoded.split(":", 2);
            if (parts.length != 2) return null;
            return new CursorData(Long.parseLong(parts[0]), parts[1]);
        } catch (Exception e) {
            return null;
        }
    }

    private void fireNotificationCreated(Notification notification) {
        if (onCreated != null) {
            onCreated.accept(new NotificationCreated(notification));
        }
    }

    private void fireNotificationStatusChanged(Notification notification, NotificationStatus previousStatus) {
        if (onStatusChanged != null) {
            onStatusChanged.accept(new NotificationStatusChanged(notification, previousStatus));
        }
    }

    private void fireAllNotificationsRead(String userId, String tenancyId, int count) {
        if (onAllRead != null) {
            onAllRead.accept(new AllNotificationsRead(userId, tenancyId, count));
        }
    }

    public void clear() {
        store.clear();
    }

    private record CursorData(long timestampMs, String id) {}
}
