package io.casehub.platform.notification.dispatch.quarkus;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.casehub.platform.api.delivery.DeliveryAttemptStore;
import io.casehub.platform.api.delivery.DeliveryChannelRegistry;
import io.casehub.platform.api.delivery.DeliveryExhausted;
import io.casehub.platform.api.delivery.DigestBuffer;
import io.casehub.platform.api.delivery.EngagementRecorded;
import io.casehub.platform.api.identity.GroupMembershipProvider;
import io.casehub.platform.api.notification.NotificationStatusChanged;
import io.casehub.platform.api.notification.NotificationStore;
import io.casehub.platform.api.notification.settings.NotificationPreferenceStore;
import io.casehub.platform.api.notification.settings.SuppressionStore;
import io.casehub.platform.api.preferences.PreferenceProvider;
import io.casehub.platform.api.subscription.EntityWatcherProvider;
import io.casehub.platform.api.subscription.SubscriptionMatched;
import io.casehub.platform.notification.dispatch.ChannelRouter;
import io.casehub.platform.notification.dispatch.DeliveryRetryProcessor;
import io.casehub.platform.notification.dispatch.DeliveryTracker;
import io.casehub.platform.notification.dispatch.DigestFlushScheduler;
import io.casehub.platform.notification.dispatch.EngagementCallbackService;
import io.casehub.platform.notification.dispatch.EngagementRecorder;
import io.casehub.platform.notification.dispatch.InAppEngagementBridge;
import io.casehub.platform.notification.dispatch.InAppNotificationDeliverer;
import io.casehub.platform.notification.dispatch.NotificationDispatcher;
import io.casehub.platform.notification.dispatch.SuppressionEvaluator;
import io.casehub.platform.notification.dispatch.TargetResolver;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Event;
import jakarta.enterprise.event.ObservesAsync;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.time.Duration;

@ApplicationScoped
public class DispatchBeans {

    @Inject DeliveryRetryProcessor retryProcessor;
    @Inject DigestFlushScheduler digestScheduler;

    // --- Pure logic POJOs ---

    @Produces
    @ApplicationScoped
    public SuppressionEvaluator suppressionEvaluator() {
        return new SuppressionEvaluator();
    }

    @Produces
    @ApplicationScoped
    public TargetResolver targetResolver(GroupMembershipProvider groupMembershipProvider,
                                         EntityWatcherProvider entityWatcherProvider) {
        return new TargetResolver(groupMembershipProvider, entityWatcherProvider);
    }

    @Produces
    @ApplicationScoped
    public ChannelRouter channelRouter(DeliveryChannelRegistry channelRegistry) {
        return new ChannelRouter(channelRegistry);
    }

    @Produces
    @ApplicationScoped
    public DeliveryTracker deliveryTracker(DeliveryAttemptStore store,
                                           ObjectMapper objectMapper,
                                           @ConfigProperty(name = "casehub.delivery.retry.base-delay", defaultValue = "30s")
                                           Duration baseDelay) {
        return new DeliveryTracker(store, objectMapper, baseDelay);
    }

    // --- Event<T> → Consumer<T> ---

    @Produces
    @ApplicationScoped
    public EngagementRecorder engagementRecorder(DeliveryAttemptStore store,
                                                 Event<EngagementRecorded> engagementEvent,
                                                 PreferenceProvider preferenceProvider) {
        return new EngagementRecorder(store, engagementEvent::fireAsync, preferenceProvider);
    }

    @Produces
    @ApplicationScoped
    public DeliveryRetryProcessor deliveryRetryProcessor(
            DeliveryAttemptStore store,
            DeliveryChannelRegistry channelRegistry,
            ObjectMapper objectMapper,
            Event<DeliveryExhausted> exhaustedEvent,
            PreferenceProvider preferenceProvider,
            @ConfigProperty(name = "casehub.delivery.retry.base-delay", defaultValue = "30s") Duration baseDelay,
            @ConfigProperty(name = "casehub.delivery.retry.max-delay", defaultValue = "30m") Duration maxDelay,
            @ConfigProperty(name = "casehub.delivery.retry.jitter-ms", defaultValue = "5000") int jitterMs,
            @ConfigProperty(name = "casehub.delivery.retry.batch-size", defaultValue = "50") int batchSize) {
        return new DeliveryRetryProcessor(store, channelRegistry, objectMapper,
                exhaustedEvent::fireAsync, preferenceProvider,
                baseDelay, maxDelay, jitterMs, batchSize);
    }

    // --- @Scheduled wrappers ---

    @Produces
    @ApplicationScoped
    public DigestFlushScheduler digestFlushScheduler(DigestBuffer digestBuffer,
                                                     NotificationPreferenceStore preferenceStore,
                                                     SuppressionStore suppressionStore,
                                                     SuppressionEvaluator suppressionEvaluator,
                                                     DeliveryChannelRegistry channelRegistry,
                                                     DeliveryTracker deliveryTracker) {
        return new DigestFlushScheduler(digestBuffer, preferenceStore, suppressionStore,
                suppressionEvaluator, channelRegistry, deliveryTracker);
    }

    @Scheduled(every = "${casehub.delivery.retry.tick-interval:30s}")
    void retryTick() {
        retryProcessor.tick();
    }

    @Scheduled(every = "${casehub.notification.digest.tick-interval:1m}")
    void digestTick() {
        digestScheduler.tick();
    }

    // --- @ObservesAsync delegates ---

    @Produces
    @ApplicationScoped
    public NotificationDispatcher notificationDispatcher(TargetResolver targetResolver,
                                                          SuppressionEvaluator suppressionEvaluator,
                                                          ChannelRouter channelRouter,
                                                          NotificationPreferenceStore preferenceStore,
                                                          SuppressionStore suppressionStore,
                                                          DigestBuffer digestBuffer,
                                                          DeliveryTracker deliveryTracker) {
        return new NotificationDispatcher(targetResolver, suppressionEvaluator, channelRouter,
                preferenceStore, suppressionStore, digestBuffer, deliveryTracker);
    }

    void onSubscriptionMatched(@ObservesAsync SubscriptionMatched event,
                                NotificationDispatcher dispatcher) {
        dispatcher.onMatch(event);
    }

    @Produces
    @ApplicationScoped
    public InAppEngagementBridge inAppEngagementBridge(DeliveryAttemptStore store,
                                                       EngagementRecorder recorder,
                                                       PreferenceProvider preferenceProvider) {
        return new InAppEngagementBridge(store, recorder, preferenceProvider);
    }

    void onNotificationStatusChanged(@ObservesAsync NotificationStatusChanged event,
                                      InAppEngagementBridge bridge) {
        bridge.onStatusChanged(event);
    }

    // --- Self-registering deliverer ---

    @Produces
    @ApplicationScoped
    public InAppNotificationDeliverer inAppNotificationDeliverer(NotificationStore notificationStore,
                                                                  DeliveryChannelRegistry channelRegistry) {
        return new InAppNotificationDeliverer(notificationStore, channelRegistry);
    }

    @Produces
    @ApplicationScoped
    public EngagementCallbackService engagementCallbackService(
            DeliveryAttemptStore deliveryAttemptStore,
            EngagementRecorder engagementRecorder,
            io.casehub.platform.api.identity.CurrentPrincipal currentPrincipal,
            jakarta.enterprise.inject.Instance<io.casehub.platform.api.delivery.EngagementCallbackHandler> handlerInstances,
            PreferenceProvider preferenceProvider) {
        java.util.Map<String, io.casehub.platform.api.delivery.EngagementCallbackHandler> handlerMap =
                handlerInstances.stream().collect(java.util.stream.Collectors.toMap(
                        io.casehub.platform.api.delivery.EngagementCallbackHandler::channelId, h -> h));
        return new EngagementCallbackService(deliveryAttemptStore, engagementRecorder,
                currentPrincipal, handlerMap, preferenceProvider);
    }
}
