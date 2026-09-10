package io.casehub.platform.notification.dispatch;

import io.casehub.platform.api.delivery.DeliveryAttemptStore;
import io.casehub.platform.api.delivery.DeliveryChannels;
import io.casehub.platform.api.delivery.DeliverySourceType;
import io.casehub.platform.api.delivery.EngagementType;
import io.casehub.platform.api.notification.NotificationStatus;
import io.casehub.platform.api.notification.NotificationStatusChanged;
import io.casehub.platform.api.identity.TenancyConstants;
import io.casehub.platform.api.preferences.PlatformPreferenceKeys;
import io.casehub.platform.api.preferences.PreferenceProvider;
import io.casehub.platform.api.preferences.SettingsScope;
import org.jboss.logging.Logger;

public class InAppEngagementBridge {

    private static final Logger LOG = Logger.getLogger(InAppEngagementBridge.class);

    private final DeliveryAttemptStore store;
    private final EngagementRecorder recorder;
    private final PreferenceProvider preferenceProvider;

    public InAppEngagementBridge(DeliveryAttemptStore store,
                                 EngagementRecorder recorder,
                                 PreferenceProvider preferenceProvider) {
        this.store = store;
        this.recorder = recorder;
        this.preferenceProvider = preferenceProvider;
    }

    public void onStatusChanged(NotificationStatusChanged event) {
        boolean enabled = preferenceProvider
                .resolve(SettingsScope.root(TenancyConstants.PLATFORM_TENANT_ID))
                .getOrDefault(PlatformPreferenceKeys.ENGAGEMENT_ENABLED)
                .value();
        if (!enabled) {
            return;
        }
        var            notification = event.notification();
        var            newStatus    = notification.status();
        EngagementType type;
        if (newStatus == NotificationStatus.READ) {
            type = EngagementType.OPENED;
        } else if (newStatus == NotificationStatus.DISMISSED) {
            type = EngagementType.DISMISSED;
        } else {
            return;
        }
        var attempts = store.findBySource(notification.id(), DeliverySourceType.NOTIFICATION, notification.tenancyId());
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
