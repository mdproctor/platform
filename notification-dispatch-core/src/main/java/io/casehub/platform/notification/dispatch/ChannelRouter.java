package io.casehub.platform.notification.dispatch;

import io.casehub.platform.api.delivery.DeliveryChannelDescriptor;
import io.casehub.platform.api.delivery.DeliveryChannelRegistry;
import io.casehub.platform.api.delivery.DestinationScope;
import io.casehub.platform.api.delivery.DigestSchedule;
import io.casehub.platform.api.delivery.NotificationDeliverer;
import io.casehub.platform.api.notification.NotificationSeverity;
import io.casehub.platform.api.notification.settings.ChannelPreference;
import io.casehub.platform.api.notification.settings.QuietHoursAction;
import io.casehub.platform.api.notification.settings.SuppressionResult;
import org.jboss.logging.Logger;

import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

public class ChannelRouter {

    private static final Logger LOG = Logger.getLogger(ChannelRouter.class);

    private final DeliveryChannelRegistry channelRegistry;

    public ChannelRouter(final DeliveryChannelRegistry channelRegistry) {
        this.channelRegistry = channelRegistry;
    }

    public Set<ResolvedChannel> route(final Map<String, ChannelPreference> channelDefaults,
                                      final SuppressionResult suppressionResult,
                                      final NotificationSeverity severity,
                                      final QuietHoursAction quietHoursAction) {
        final Set<ResolvedChannel> result = new LinkedHashSet<>();

        for (final DeliveryChannelDescriptor descriptor : channelRegistry.discover()) {
            final String channelId = descriptor.channelId();

            final ChannelPreference    userPref = channelDefaults.get(channelId);
            final boolean              enabled;
            final NotificationSeverity minSeverity;

            if (userPref != null) {
                enabled     = userPref.enabled();
                minSeverity = userPref.minSeverity();
            } else {
                enabled     = descriptor.defaultEnabled();
                minSeverity = descriptor.defaultMinSeverity();
            }

            if (!enabled) {
                continue;
            }

            if (!severity.isAtLeast(minSeverity)) {
                continue;
            }

            final NotificationDeliverer deliverer = channelRegistry.resolveDeliverer(channelId)
                                                                   .orElse(null);
            if (deliverer == null) {
                continue;
            }

            final DigestSchedule effectiveDigest;
            if (userPref != null && userPref.digestSchedule() != null) {
                effectiveDigest = userPref.digestSchedule();
            } else {
                effectiveDigest = descriptor.defaultDigestSchedule();
            }

            final boolean quietHoursBuffering = suppressionResult.quietHoursActive()
                                                && quietHoursAction == QuietHoursAction.BUFFER_FOR_DIGEST
                                                && effectiveDigest != null
                                                && descriptor.destinationScope() != DestinationScope.PER_TENANT;

            if (suppressionResult.quietHoursActive()
                && quietHoursAction == QuietHoursAction.BUFFER_FOR_DIGEST
                && effectiveDigest == null) {
                LOG.warnf("BUFFER_FOR_DIGEST on channel %s but no digest schedule — notification suppressed",
                          channelId);
            }

            final boolean suppressed = descriptor.external()
                                       && (suppressionResult.isSnoozed()
                                           || (suppressionResult.quietHoursActive() && !quietHoursBuffering));

            final boolean digested = descriptor.external()
                                     && effectiveDigest != null
                                     && (!severity.isAtLeast(NotificationSeverity.URGENT) || quietHoursBuffering)
                                     && descriptor.destinationScope() != DestinationScope.PER_TENANT;

            result.add(new ResolvedChannel(channelId, deliverer, suppressed, digested,
                                           descriptor.guaranteedMinSeverity(), descriptor.destinationScope()));
        }

        return result;
    }
}
