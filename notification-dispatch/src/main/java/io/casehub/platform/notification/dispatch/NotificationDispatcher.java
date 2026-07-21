package io.casehub.platform.notification.dispatch;

import io.casehub.platform.api.delivery.DeliveryResult;
import io.casehub.platform.api.delivery.DeliverySourceType;
import io.casehub.platform.api.delivery.DigestBuffer;
import io.casehub.platform.api.delivery.DigestBufferKey;
import io.casehub.platform.api.notification.NotificationInput;
import io.casehub.platform.api.notification.settings.NotificationPreferenceStore;
import io.casehub.platform.api.notification.settings.NotificationPreferences;
import io.casehub.platform.api.notification.settings.QuietHours;
import io.casehub.platform.api.notification.settings.QuietHoursAction;
import io.casehub.platform.api.notification.settings.SuppressionStore;
import io.casehub.platform.api.subscription.Subscription;
import io.casehub.platform.api.subscription.SubscriptionMatched;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.ObservesAsync;
import jakarta.inject.Inject;

import org.jboss.logging.Logger;

import java.time.Instant;
import java.util.Map;
import java.util.Set;

/**
 * Notification dispatch pipeline — observes {@link SubscriptionMatched} events
 * and orchestrates target resolution, suppression evaluation, template resolution,
 * channel routing, and delivery.
 *
 * <p>Pipeline flow per spec:
 * <ol>
 *   <li>Resolve targets → deduplicated user set</li>
 *   <li>Per user: pre-fetch preferences + suppression data (one query each)</li>
 *   <li>Evaluate suppression (pure function over pre-fetched data)</li>
 *   <li>If muted → skip entirely</li>
 *   <li>Resolve template → NotificationInput (null check → skip)</li>
 *   <li>Route to channels</li>
 *   <li>Per channel: deliver with error isolation (try/catch per channel)</li>
 * </ol>
 */
@ApplicationScoped
public class NotificationDispatcher {

    private static final Logger LOG = Logger.getLogger(NotificationDispatcher.class);

    private final TargetResolver targetResolver;
    private final SuppressionEvaluator suppressionEvaluator;
    private final ChannelRouter channelRouter;
    private final NotificationPreferenceStore preferenceStore;
    private final SuppressionStore suppressionStore;
    private final DigestBuffer digestBuffer;
    private final DeliveryTracker deliveryTracker;

    @Inject
    public NotificationDispatcher(final TargetResolver targetResolver,
                                  final SuppressionEvaluator suppressionEvaluator,
                                  final ChannelRouter channelRouter,
                                  final NotificationPreferenceStore preferenceStore,
                                  final SuppressionStore suppressionStore,
                                  final DigestBuffer digestBuffer,
                                  final DeliveryTracker deliveryTracker) {
        this.targetResolver = targetResolver;
        this.suppressionEvaluator = suppressionEvaluator;
        this.channelRouter = channelRouter;
        this.preferenceStore = preferenceStore;
        this.suppressionStore = suppressionStore;
        this.digestBuffer = digestBuffer;
        this.deliveryTracker = deliveryTracker;
    }

    /**
     * Handle a subscription match event. Runs asynchronously on the managed executor pool,
     * decoupled from the alpha network's thread.
     *
     * @param event matched subscription + event POJO
     */
    void onMatch(@ObservesAsync final SubscriptionMatched event) {
        final Subscription subscription = event.subscription();
        final Object pojo = event.pojo();
        final String tenancyId = subscription.tenancyId();

        // Step 1: Resolve targets
        final Set<String> recipientUserIds = targetResolver.resolve(subscription, pojo);
        if (recipientUserIds.isEmpty()) {
            return;
        }

        // Step 2–7: Per-user pipeline
        for (final String userId : recipientUserIds) {
            dispatchToUser(userId, tenancyId, subscription, pojo);
        }
    }

    private void dispatchToUser(final String userId,
                                final String tenancyId,
                                final Subscription subscription,
                                final Object pojo) {
        // Pre-fetch per-user data (one query each — no redundant lookups)
        final var preferences = preferenceStore.get(userId, tenancyId);
        final var activeMutes = suppressionStore.activeMutes(userId, tenancyId);
        final var activeSnooze = suppressionStore.activeSnooze(userId, tenancyId);

        // Extract suppression metadata from template
        final var template = subscription.template();
        final String entityType = template.entityType();
        final String entityId = TemplateResolver.extractField(pojo, template.entityIdField());
        final String category = template.category();

        // Quiet hours from preferences (nullable)
        final QuietHours quietHours = preferences
                .map(NotificationPreferences::quietHours)
                .orElse(null);

        // Evaluate suppression
        final Instant now = Instant.now();
        final var suppressionResult = suppressionEvaluator.evaluate(
                activeMutes, activeSnooze, quietHours,
                entityType,
                entityId != null ? entityId : "",
                category, now);

        // If muted → drop entirely (all channels)
        if (suppressionResult.isMuted()) {
            return;
        }

        // Template resolution — only when not muted (avoid wasted work)
        final NotificationInput notificationInput = TemplateResolver.resolve(
                template, pojo, userId, tenancyId);
        if (notificationInput == null) {
            // WARN already logged by TemplateResolver
            return;
        }

        // Channel routing
        final Map<String, io.casehub.platform.api.notification.settings.ChannelPreference> channelDefaults =
                preferences.map(NotificationPreferences::channelDefaults).orElse(Map.of());

        final QuietHoursAction quietHoursAction = preferences
                .map(NotificationPreferences::quietHours)
                .map(QuietHours::action)
                .orElse(null);

        final Set<ResolvedChannel> channels = channelRouter.route(
                channelDefaults, suppressionResult, notificationInput.severity(), quietHoursAction);

        // Delivery — three-path routing: digest, suppress, or deliver
        for (final ResolvedChannel channel : channels) {
            if (channel.digested()) {
                digestBuffer.add(
                        new DigestBufferKey(userId, tenancyId, channel.channelId()),
                        notificationInput);
                continue;
            }
            if (channel.suppressed()) {
                continue;
            }
            try {
                final DeliveryResult result = channel.deliverer().deliver(notificationInput);
                if (result.success()) {
                    deliveryTracker.recordSuccess(
                            channel.channelId(), notificationInput, null, DeliverySourceType.NOTIFICATION);
                } else {
                    LOG.warnf("Delivery failed for channel '%s', user '%s': %s",
                            channel.channelId(), userId, result.failureReason());
                    deliveryTracker.recordFailure(
                            channel.channelId(), notificationInput, null,
                            DeliverySourceType.NOTIFICATION,
                            channel.guaranteedMinSeverity(), result.failureReason());
                }
            } catch (Exception e) {
                LOG.warnf(e, "Delivery error for channel '%s', user '%s'",
                        channel.channelId(), userId);
                deliveryTracker.recordFailure(
                        channel.channelId(), notificationInput, null,
                        DeliverySourceType.NOTIFICATION,
                        channel.guaranteedMinSeverity(), e.getMessage());
            }
        }
    }
}
