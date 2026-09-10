package io.casehub.platform.notification;

import io.casehub.platform.api.delivery.DeliveryChannelDescriptor;
import io.casehub.platform.api.delivery.DeliveryChannelRegistry;
import io.casehub.platform.api.delivery.DigestSchedule;
import io.casehub.platform.api.notification.NotificationSeverity;
import io.casehub.platform.api.notification.settings.ChannelPreference;
import io.casehub.platform.api.notification.settings.NotificationPreferenceUpdate;
import io.casehub.platform.api.notification.settings.NotificationPreferences;
import io.casehub.platform.api.notification.settings.QuietHours;
import io.casehub.platform.api.notification.settings.QuietHoursAction;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PreferenceValidatorTest {

    private DeliveryChannelRegistry channelRegistry;
    private PreferenceValidator validator;

    @BeforeEach
    void setUp() {
        channelRegistry = mock(DeliveryChannelRegistry.class);
        when(channelRegistry.discover()).thenReturn(Set.of());
        validator = new PreferenceValidator(channelRegistry);
    }

    @Test
    void bufferForDigest_withDigestedChannel_passes() {
        var quietHours = new QuietHours(
                LocalTime.of(22, 0), LocalTime.of(7, 0),
                ZoneId.of("UTC"), QuietHoursAction.BUFFER_FOR_DIGEST);
        var channels = Map.of("email", new ChannelPreference(
                true, NotificationSeverity.INFO,
                new DigestSchedule.Interval(Duration.ofHours(1)), null));
        var update = new NotificationPreferenceUpdate(channels, quietHours, false);

        assertThatCode(() -> validator.validate(update, null))
                .doesNotThrowAnyException();
    }

    @Test
    void bufferForDigest_noDigestedChannel_throws() {
        var quietHours = new QuietHours(
                LocalTime.of(22, 0), LocalTime.of(7, 0),
                ZoneId.of("UTC"), QuietHoursAction.BUFFER_FOR_DIGEST);
        var channels = Map.of("email", new ChannelPreference(
                true, NotificationSeverity.INFO, null, null));
        var update = new NotificationPreferenceUpdate(channels, quietHours, false);

        assertThatThrownBy(() -> validator.validate(update, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("BUFFER_FOR_DIGEST");
    }

    @Test
    void bufferForDigest_channelDefaultHasDigest_passes() {
        var descriptor = new DeliveryChannelDescriptor(
                "email", "Email", true, true,
                NotificationSeverity.INFO,
                new DigestSchedule.Interval(Duration.ofHours(1)), null, null);
        when(channelRegistry.discover()).thenReturn(Set.of(descriptor));

        var quietHours = new QuietHours(
                LocalTime.of(22, 0), LocalTime.of(7, 0),
                ZoneId.of("UTC"), QuietHoursAction.BUFFER_FOR_DIGEST);
        var update = new NotificationPreferenceUpdate(Map.of(), quietHours, false);

        assertThatCode(() -> validator.validate(update, null))
                .doesNotThrowAnyException();
    }

    @Test
    void suppressAction_noDigestedChannel_passes() {
        var quietHours = new QuietHours(
                LocalTime.of(22, 0), LocalTime.of(7, 0),
                ZoneId.of("UTC"), QuietHoursAction.SUPPRESS);
        var update = new NotificationPreferenceUpdate(Map.of(), quietHours, false);

        assertThatCode(() -> validator.validate(update, null))
                .doesNotThrowAnyException();
    }

    @Test
    void noQuietHours_passes() {
        var update = new NotificationPreferenceUpdate(Map.of(), null, false);
        assertThatCode(() -> validator.validate(update, null))
                .doesNotThrowAnyException();
    }
}
