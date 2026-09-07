package io.casehub.platform.subscription.inmem;

import io.casehub.platform.api.subscription.Subscription;
import io.casehub.platform.api.subscription.SubscriptionCreated;
import io.casehub.platform.api.subscription.SubscriptionDeleted;
import io.casehub.platform.api.subscription.SubscriptionInput;
import io.casehub.platform.api.subscription.SubscriptionPage;
import io.casehub.platform.api.subscription.SubscriptionQuery;
import io.casehub.platform.api.subscription.SubscriptionScope;
import io.casehub.platform.api.subscription.SubscriptionStore;
import io.casehub.platform.api.subscription.SubscriptionUpdate;
import io.casehub.platform.api.subscription.SubscriptionUpdated;
import io.casehub.platform.api.util.UUIDv7;

import java.time.Instant;
import java.util.Base64;
import java.util.Comparator;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.stream.Stream;

public class InMemorySubscriptionStore implements SubscriptionStore {

    private final ConcurrentHashMap<String, Subscription> store = new ConcurrentHashMap<>();
    private final Consumer<SubscriptionCreated> onCreated;
    private final Consumer<SubscriptionUpdated> onUpdated;
    private final Consumer<SubscriptionDeleted> onDeleted;

    public InMemorySubscriptionStore(
            Consumer<SubscriptionCreated> onCreated,
            Consumer<SubscriptionUpdated> onUpdated,
            Consumer<SubscriptionDeleted> onDeleted) {
        this.onCreated = onCreated;
        this.onUpdated = onUpdated;
        this.onDeleted = onDeleted;
    }

    public InMemorySubscriptionStore() {
        this(null, null, null);
    }

    @Override
    public Subscription store(SubscriptionInput input) {
        var subscription = toSubscription(input);
        store.put(subscription.id(), subscription);
        if (onCreated != null) onCreated.accept(new SubscriptionCreated(subscription));
        return subscription;
    }

    @Override
    public Optional<Subscription> findById(String id, String ownerId, String tenancyId) {
        return Optional.ofNullable(store.get(id))
                       .filter(s -> s.tenancyId().equals(tenancyId))
                       .filter(s -> s.ownerId().equals(ownerId) || s.scope() == SubscriptionScope.SYSTEM);
    }

    @Override
    public SubscriptionPage find(SubscriptionQuery query) {
        var comparator = Comparator.comparing(Subscription::createdAt)
                                   .thenComparing(Subscription::id)
                                   .reversed();

        var effectiveScope = query.scope() != null ? query.scope() : SubscriptionScope.USER;

        var filtered = store.values().stream()
                            .filter(s -> s.tenancyId().equals(query.tenancyId()))
                            .filter(s -> effectiveScope == SubscriptionScope.SYSTEM
                                         ? s.scope() == SubscriptionScope.SYSTEM
                                         : s.ownerId().equals(query.ownerId()) && s.scope() == SubscriptionScope.USER)
                            .filter(s -> query.enabled() == null || s.enabled() == query.enabled())
                            .filter(s -> matchesCursor(s, query.cursor()))
                            .sorted(comparator)
                            .limit(query.limit() + 1)
                            .toList();

        boolean hasMore       = filtered.size() > query.limit();
        var     subscriptions = hasMore ? filtered.subList(0, query.limit()) : filtered;
        String  nextCursor    = hasMore ? encodeCursor(subscriptions.get(subscriptions.size() - 1)) : null;

        return new SubscriptionPage(subscriptions, nextCursor);
    }

    @Override
    public Optional<Subscription> update(String id, String ownerId, String tenancyId, SubscriptionUpdate update) {
        var result = new Object() {
            Subscription updated  = null;
            Subscription previous = null;
        };

        store.compute(id, (key, subscription) -> {
            if (subscription == null
                || !subscription.tenancyId().equals(tenancyId)
                || (!subscription.ownerId().equals(ownerId) && subscription.scope() != SubscriptionScope.SYSTEM)) {
                return subscription;
            }

            result.previous = subscription;
            result.updated  = applyUpdate(subscription, update);
            return result.updated;
        });

        if (result.updated != null && onUpdated != null) {
            onUpdated.accept(new SubscriptionUpdated(result.updated, result.previous));
        }
        return Optional.ofNullable(result.updated);
    }

    @Override
    public boolean delete(String id, String ownerId, String tenancyId) {
        var result = new Object() {
            Subscription deleted = null;
        };

        store.compute(id, (key, subscription) -> {
            if (subscription != null
                && subscription.tenancyId().equals(tenancyId)
                && (subscription.ownerId().equals(ownerId) || subscription.scope() == SubscriptionScope.SYSTEM)) {
                result.deleted = subscription;
                return null;
            }
            return subscription;
        });

        if (result.deleted != null) {
            if (onDeleted != null) onDeleted.accept(new SubscriptionDeleted(result.deleted));
            return true;
        }
        return false;
    }

    @Override
    public Stream<Subscription> findAllEnabled() {
        return store.values().stream()
                    .filter(Subscription::enabled);
    }

    public void clear() {
        store.clear();
    }

    private Subscription toSubscription(SubscriptionInput input) {
        var now = Instant.now();
        return new Subscription(
                UUIDv7.generate(),
                input.ownerId(),
                input.tenancyId(),
                input.name(),
                input.eventType(),
                input.filters(),
                input.targets(),
                input.includeActor(),
                input.template(),
                input.enabled(),
                input.scope(),
                now,
                now
        );
    }

    private Subscription applyUpdate(Subscription subscription, SubscriptionUpdate update) {
        return new Subscription(
                subscription.id(),
                subscription.ownerId(),
                subscription.tenancyId(),
                update.name() != null ? update.name() : subscription.name(),
                update.eventType() != null ? update.eventType() : subscription.eventType(),
                update.filters() != null ? update.filters() : subscription.filters(),
                update.targets() != null ? update.targets() : subscription.targets(),
                update.includeActor() != null ? update.includeActor() : subscription.includeActor(),
                update.template() != null ? update.template() : subscription.template(),
                update.enabled() != null ? update.enabled() : subscription.enabled(),
                subscription.scope(),
                subscription.createdAt(),
                Instant.now()
        );
    }

    private boolean matchesCursor(Subscription s, String cursor) {
        if (cursor == null) {return true;}
        var decoded = decodeCursor(cursor);
        if (decoded == null) { return true; }
        long cursorTimestamp = decoded.timestampMs;
        String cursorId = decoded.id;
        long subscriptionTimestamp = s.createdAt().toEpochMilli();
        if (subscriptionTimestamp < cursorTimestamp) {return true;}
        if (subscriptionTimestamp > cursorTimestamp) {return false;}
        return s.id().compareTo(cursorId) < 0;
    }

    private String encodeCursor(Subscription subscription) {
        long   timestampMs = subscription.createdAt().toEpochMilli();
        String encoded     = timestampMs + ":" + subscription.id();
        return Base64.getUrlEncoder().withoutPadding().encodeToString(encoded.getBytes());
    }

    private CursorData decodeCursor(String cursor) {
        try {
            var decoded = new String(Base64.getUrlDecoder().decode(cursor));
            var parts   = decoded.split(":", 2);
            if (parts.length != 2) {return null;}
            return new CursorData(Long.parseLong(parts[0]), parts[1]);
        } catch (Exception e) {
            return null;
        }
    }

    private record CursorData(long timestampMs, String id) {}
}
