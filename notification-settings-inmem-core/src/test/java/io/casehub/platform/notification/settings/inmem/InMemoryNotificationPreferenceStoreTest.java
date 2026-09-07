package io.casehub.platform.notification.settings.inmem;

import io.casehub.platform.api.notification.NotificationSeverity;
import io.casehub.platform.api.notification.settings.ChannelPreference;
import io.casehub.platform.api.notification.settings.NotificationPreferenceUpdate;
import io.casehub.platform.api.notification.settings.QuietHours;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalTime;
import java.time.ZoneId;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class InMemoryNotificationPreferenceStoreTest {

    private InMemoryNotificationPreferenceStore store;

    @BeforeEach
    void setUp() {
        store = new InMemoryNotificationPreferenceStore();
    }

    @Test
    void get_returnsEmpty_whenNoPreferencesStored() {
        var result = store.get("user1", "tenant1");
        assertThat(result).isEmpty();
    }

    @Test
    void update_createsPreferences_whenNoneExist() {
        var channelPrefs = Map.of("email", new ChannelPreference(true, NotificationSeverity.WARNING, null, null));
        var update = new NotificationPreferenceUpdate(channelPrefs, null, false);

        var result = store.update("user1", "tenant1", update);

        assertThat(result.userId()).isEqualTo("user1");
        assertThat(result.tenancyId()).isEqualTo("tenant1");
        assertThat(result.channelDefaults()).isEqualTo(channelPrefs);
        assertThat(result.quietHours()).isNull();
        assertThat(result.updatedAt()).isNotNull();
    }

    @Test
    void update_updatesChannelDefaults() {
        var initial = Map.of("email", new ChannelPreference(true, NotificationSeverity.INFO, null, null));
        store.update("user1", "tenant1", new NotificationPreferenceUpdate(initial, null, false));

        var updated = Map.of(
                "email", new ChannelPreference(false, NotificationSeverity.URGENT, null, null),
                "sms", new ChannelPreference(true, NotificationSeverity.WARNING, null, null)
        );
        var result = store.update("user1", "tenant1", new NotificationPreferenceUpdate(updated, null, false));

        assertThat(result.channelDefaults()).isEqualTo(updated);
    }

    @Test
    void update_setsQuietHours() {
        var quietHours = new QuietHours(
                LocalTime.of(22, 0),
                LocalTime.of(7, 0),
                ZoneId.of("America/New_York"),
                null
        );
        var update = new NotificationPreferenceUpdate(Map.of(), quietHours, false);

        var result = store.update("user1", "tenant1", update);

        assertThat(result.quietHours()).isEqualTo(quietHours);
    }

    @Test
    void update_clearsQuietHours_whenClearFlagTrue() {
        var quietHours = new QuietHours(
                LocalTime.of(22, 0),
                LocalTime.of(7, 0),
                ZoneId.of("America/New_York"),
                null
        );
        store.update("user1", "tenant1", new NotificationPreferenceUpdate(Map.of(), quietHours, false));

        var result = store.update("user1", "tenant1", new NotificationPreferenceUpdate(Map.of(), null, true));

        assertThat(result.quietHours()).isNull();
    }

    @Test
    void update_preservesQuietHours_whenNullAndNotCleared() {
        var quietHours = new QuietHours(
                LocalTime.of(22, 0),
                LocalTime.of(7, 0),
                ZoneId.of("America/New_York"),
                null
        );
        store.update("user1", "tenant1", new NotificationPreferenceUpdate(Map.of(), quietHours, false));

        var result = store.update("user1", "tenant1",
                new NotificationPreferenceUpdate(Map.of("email", new ChannelPreference(true, NotificationSeverity.INFO, null, null)), null, false));

        assertThat(result.quietHours()).isEqualTo(quietHours);
    }

    @Test
    void get_isolatesByUserAndTenancy() {
        store.update("user1", "tenant1", new NotificationPreferenceUpdate(
                Map.of("email", new ChannelPreference(true, NotificationSeverity.INFO, null, null)), null, false));
        store.update("user2", "tenant1", new NotificationPreferenceUpdate(
                Map.of("sms", new ChannelPreference(true, NotificationSeverity.WARNING, null, null)), null, false));
        store.update("user1", "tenant2", new NotificationPreferenceUpdate(
                Map.of("push", new ChannelPreference(true, NotificationSeverity.URGENT, null, null)), null, false));

        var user1Tenant1 = store.get("user1", "tenant1");
        var user2Tenant1 = store.get("user2", "tenant1");
        var user1Tenant2 = store.get("user1", "tenant2");

        assertThat(user1Tenant1).isPresent();
        assertThat(user1Tenant1.get().channelDefaults()).containsKey("email");

        assertThat(user2Tenant1).isPresent();
        assertThat(user2Tenant1.get().channelDefaults()).containsKey("sms");

        assertThat(user1Tenant2).isPresent();
        assertThat(user1Tenant2.get().channelDefaults()).containsKey("push");
    }
}
