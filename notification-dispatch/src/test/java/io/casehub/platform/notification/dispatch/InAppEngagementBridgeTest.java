package io.casehub.platform.notification.dispatch;

import io.casehub.platform.api.delivery.DeliveryAttempt;
import io.casehub.platform.api.delivery.DeliveryChannels;
import io.casehub.platform.api.delivery.DeliverySourceType;
import io.casehub.platform.api.delivery.DeliveryStatus;
import io.casehub.platform.api.delivery.DeliveryType;
import io.casehub.platform.api.delivery.EngagementRecorded;
import io.casehub.platform.api.delivery.EngagementType;
import io.casehub.platform.api.notification.Notification;
import io.casehub.platform.api.notification.NotificationSeverity;
import io.casehub.platform.api.notification.NotificationSource;
import io.casehub.platform.api.notification.NotificationStatus;
import io.casehub.platform.api.notification.NotificationStatusChanged;
import io.casehub.platform.api.util.UUIDv7;
import io.casehub.platform.delivery.tracking.inmem.InMemoryDeliveryAttemptStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class InAppEngagementBridgeTest {

    private InMemoryDeliveryAttemptStore store;
    private List<EngagementRecorded> firedEvents;
    private InAppEngagementBridge bridge;

    @BeforeEach
    void setUp() {
        store = new InMemoryDeliveryAttemptStore(10000);
        firedEvents = new ArrayList<>();
        var recorder = new EngagementRecorder(store,
                new EngagementRecorderTest.CapturingEngagementEvent(firedEvents), true);
        bridge = new InAppEngagementBridge(store, recorder, true);
    }

    @Test
    void mapsReadToOpened() {
        var attempt = inAppAttempt("notif-1");
        store.store(attempt);
        var notification = testNotification("notif-1", NotificationStatus.READ);
        bridge.onStatusChanged(new NotificationStatusChanged(notification, NotificationStatus.UNREAD));
        var events = store.findEngagementsByAttemptId(attempt.id());
        assertThat(events).hasSize(1);
        assertThat(events.getFirst().type()).isEqualTo(EngagementType.OPENED);
    }

    @Test
    void mapsDismissedToDismissed() {
        var attempt = inAppAttempt("notif-1");
        store.store(attempt);
        var notification = testNotification("notif-1", NotificationStatus.DISMISSED);
        bridge.onStatusChanged(new NotificationStatusChanged(notification, NotificationStatus.READ));
        var events = store.findEngagementsByAttemptId(attempt.id());
        assertThat(events).hasSize(1);
        assertThat(events.getFirst().type()).isEqualTo(EngagementType.DISMISSED);
    }

    @Test
    void skipsWhenNoInAppAttemptFound() {
        var notification = testNotification("notif-no-attempt", NotificationStatus.READ);
        bridge.onStatusChanged(new NotificationStatusChanged(notification, NotificationStatus.UNREAD));
        assertThat(firedEvents).isEmpty();
    }

    @Test
    void skipsNonInAppAttempts() {
        var emailAttempt = new DeliveryAttempt(
                UUIDv7.generate(), "notif-1", DeliverySourceType.NOTIFICATION, "email", "user-1", "tenant-1",
                DeliveryType.IMMEDIATE, DeliveryStatus.DELIVERED, 1,
                Instant.now(), Instant.now(), Instant.now(), null, null, "{}",
                null, null);
        store.store(emailAttempt);
        var notification = testNotification("notif-1", NotificationStatus.READ);
        bridge.onStatusChanged(new NotificationStatusChanged(notification, NotificationStatus.UNREAD));
        assertThat(store.findEngagementsByAttemptId(emailAttempt.id())).isEmpty();
    }

    @Test
    void skipsWhenDisabled() {
        var disabledRecorder = new EngagementRecorder(store,
                new EngagementRecorderTest.CapturingEngagementEvent(firedEvents), false);
        var disabledBridge = new InAppEngagementBridge(store, disabledRecorder, false);
        var attempt = inAppAttempt("notif-1");
        store.store(attempt);
        var notification = testNotification("notif-1", NotificationStatus.READ);
        disabledBridge.onStatusChanged(new NotificationStatusChanged(notification, NotificationStatus.UNREAD));
        assertThat(store.findEngagementsByAttemptId(attempt.id())).isEmpty();
    }

    @Test
    void handlesMultipleStatusChangesIndependently() {
        var attempt1 = inAppAttempt("notif-1");
        var attempt2 = inAppAttempt("notif-2");
        store.store(attempt1);
        store.store(attempt2);
        bridge.onStatusChanged(new NotificationStatusChanged(
                testNotification("notif-1", NotificationStatus.READ), NotificationStatus.UNREAD));
        bridge.onStatusChanged(new NotificationStatusChanged(
                testNotification("notif-2", NotificationStatus.READ), NotificationStatus.UNREAD));
        assertThat(store.findEngagementsByAttemptId(attempt1.id())).hasSize(1);
        assertThat(store.findEngagementsByAttemptId(attempt2.id())).hasSize(1);
        assertThat(firedEvents).hasSize(2);
    }

    private DeliveryAttempt inAppAttempt(String notificationId) {
        return new DeliveryAttempt(
                UUIDv7.generate(), notificationId, DeliverySourceType.NOTIFICATION, DeliveryChannels.IN_APP, "user-1", "tenant-1",
                DeliveryType.IMMEDIATE, DeliveryStatus.DELIVERED, 1,
                Instant.now(), Instant.now(), Instant.now(), null, null, "{}",
                null, null);
    }

    private Notification testNotification(String id, NotificationStatus status) {
        return new Notification(
                id, "user-1", "tenant-1", "Test", "body",
                "test", NotificationSeverity.INFO, null,
                new NotificationSource("evt-1", "work-item", "wi-1", "actor-1"),
                status, Instant.now(),
                status == NotificationStatus.READ ? Instant.now() : null,
                status == NotificationStatus.DISMISSED ? Instant.now() : null);
    }
}
