package io.casehub.platform.notification.rest;

import io.casehub.pages.push.EventBroadcaster;
import io.casehub.platform.api.governance.SessionIsolator;
import io.casehub.platform.api.notification.AllNotificationsRead;
import io.casehub.platform.api.notification.Notification;
import io.casehub.platform.api.notification.NotificationCreated;
import io.casehub.platform.api.notification.NotificationSeverity;
import io.casehub.platform.api.notification.NotificationSource;
import io.casehub.platform.api.notification.NotificationStatus;
import io.casehub.platform.api.notification.NotificationStatusChanged;
import io.casehub.platform.api.notification.NotificationStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.function.Supplier;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class NotificationPushServiceTest {

    private EventBroadcaster broadcaster;
    private NotificationStore store;
    private SessionIsolator sessionIsolator;
    private NotificationPushService service;

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setUp() {
        broadcaster = mock(EventBroadcaster.class);
        store = mock(NotificationStore.class);
        sessionIsolator = mock(SessionIsolator.class);
        when(sessionIsolator.runIsolated(any(Supplier.class))).thenAnswer(inv -> {
            var supplier = inv.getArgument(0, Supplier.class);
            return supplier.get();
        });
        service = new NotificationPushService(broadcaster, store, sessionIsolator);
    }

    private Notification testNotification(String userId, String tenancyId) {
        return new Notification(
                "notif-1", userId, tenancyId,
                "Test Title", "Test Body", "test-category",
                NotificationSeverity.INFO, null,
                new NotificationSource("evt-1", "case", "case-1", "actor-1"),
                NotificationStatus.UNREAD, Instant.now(), null, null);
    }

    @Test
    void onNotificationCreated_broadcastsToNewAndUnreadCount() {
        var notification = testNotification("user-1", "tenant-1");
        when(store.unreadCount("user-1", "tenant-1")).thenReturn(5L);

        service.onNotificationCreated(new NotificationCreated(notification));

        verify(broadcaster).broadcast(eq("notifications:user-1:new"), any(Notification.class));
        verify(broadcaster).broadcast(eq("notifications:user-1:unread-count"), any(Object.class));
    }

    @Test
    void onNotificationStatusChanged_broadcastsToUpdatedAndUnreadCount() {
        var notification = testNotification("user-2", "tenant-1");
        when(store.unreadCount("user-2", "tenant-1")).thenReturn(3L);

        service.onNotificationStatusChanged(
                new NotificationStatusChanged(notification, NotificationStatus.UNREAD));

        verify(broadcaster).broadcast(eq("notifications:user-2:updated"), any(Notification.class));
        verify(broadcaster).broadcast(eq("notifications:user-2:unread-count"), any(Object.class));
    }

    @Test
    void onAllNotificationsRead_broadcastsUnreadCountOnly() {
        when(store.unreadCount("user-3", "tenant-1")).thenReturn(0L);

        service.onAllNotificationsRead(new AllNotificationsRead("user-3", "tenant-1", 0));

        verify(broadcaster).broadcast(eq("notifications:user-3:unread-count"), any(Object.class));
        verify(broadcaster, times(1)).broadcast(anyString(), any(Object.class));
    }

    @Test
    void unreadCountFailure_logsAndDoesNotPropagate() {
        var notification = testNotification("user-4", "tenant-1");
        when(store.unreadCount("user-4", "tenant-1")).thenThrow(new RuntimeException("DB down"));

        service.onNotificationCreated(new NotificationCreated(notification));

        verify(broadcaster).broadcast(eq("notifications:user-4:new"), any(Notification.class));
        verify(broadcaster, times(1)).broadcast(anyString(), any(Object.class));
    }
}
