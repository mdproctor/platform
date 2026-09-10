package io.casehub.platform.delivery.channel.inmem;

import io.casehub.platform.api.delivery.DeliveryChannelDescriptor;
import io.casehub.platform.api.delivery.DeliveryChannelRegistry;
import io.casehub.platform.api.delivery.NotificationDeliverer;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

public class InMemoryDeliveryChannelRegistry implements DeliveryChannelRegistry {

    private record ChannelEntry(DeliveryChannelDescriptor descriptor, NotificationDeliverer deliverer) {}

    private final Map<String, ChannelEntry> channels = new ConcurrentHashMap<>();

    @Override
    public void register(final DeliveryChannelDescriptor descriptor, final NotificationDeliverer deliverer) {
        Objects.requireNonNull(descriptor, "descriptor");
        Objects.requireNonNull(deliverer, "deliverer");
        channels.put(descriptor.channelId(), new ChannelEntry(descriptor, deliverer));
    }

    @Override
    public Optional<DeliveryChannelDescriptor> resolve(final String channelId) {
        var entry = channels.get(channelId);
        return entry != null ? Optional.of(entry.descriptor()) : Optional.empty();
    }

    @Override
    public Optional<NotificationDeliverer> resolveDeliverer(final String channelId) {
        var entry = channels.get(channelId);
        return entry != null ? Optional.of(entry.deliverer()) : Optional.empty();
    }

    @Override
    public Set<DeliveryChannelDescriptor> discover() {
        return channels.values().stream()
                .map(ChannelEntry::descriptor)
                .collect(Collectors.toUnmodifiableSet());
    }
}
