package io.casehub.platform.delivery;

import io.casehub.platform.api.delivery.DeliveryChannelDescriptor;
import io.casehub.platform.api.delivery.DeliveryChannelRegistry;
import io.casehub.platform.api.delivery.NotificationDeliverer;

import java.util.Optional;
import java.util.Set;

public class NoOpDeliveryChannelRegistry implements DeliveryChannelRegistry {
    @Override public void register(final DeliveryChannelDescriptor descriptor, final NotificationDeliverer deliverer) {}
    @Override public Optional<DeliveryChannelDescriptor> resolve(final String channelId) { return Optional.empty(); }
    @Override public Optional<NotificationDeliverer> resolveDeliverer(final String channelId) { return Optional.empty(); }
    @Override public Set<DeliveryChannelDescriptor> discover() { return Set.of(); }
}
