package io.casehub.platform.subscription;

import io.casehub.platform.api.subscription.Subscription;
import io.casehub.platform.api.subscription.SubscriptionInput;
import io.casehub.platform.api.subscription.SubscriptionPage;
import io.casehub.platform.api.subscription.SubscriptionQuery;
import io.casehub.platform.api.subscription.SubscriptionStore;
import io.casehub.platform.api.subscription.SubscriptionUpdate;
import io.casehub.platform.api.util.UUIDv7;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

public class NoOpSubscriptionStore implements SubscriptionStore {
    @Override public Subscription store(final SubscriptionInput input) {
        final var now = Instant.now();
        return new Subscription(UUIDv7.generate(), input.ownerId(), input.tenancyId(), input.name(), input.eventType(), input.filters(), input.targets(), input.includeActor(), input.template(), input.enabled(), input.scope(), now, now);
    }
    @Override public Optional<Subscription> findById(final String id, final String ownerId, final String tenancyId) { return Optional.empty(); }
    @Override public SubscriptionPage find(final SubscriptionQuery query) { return new SubscriptionPage(List.of(), null); }
    @Override public Optional<Subscription> update(final String id, final String ownerId, final String tenancyId, final SubscriptionUpdate update) { return Optional.empty(); }
    @Override public boolean delete(final String id, final String ownerId, final String tenancyId) { return false; }
    @Override public Stream<Subscription> findAllEnabled() { return Stream.empty(); }
}
