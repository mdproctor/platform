package io.casehub.platform.api.subscription;

import java.util.Optional;
import java.util.stream.Stream;

/**
 * Subscription persistence SPI — single blocking interface, no reactive counterpart.
 *
 * <p><strong>CDI Events:</strong> Non-no-op implementations must fire:
 * <ul>
 *   <li>{@link SubscriptionCreated} — after {@code store()}</li>
 *   <li>{@link SubscriptionUpdated} — after {@code update()}</li>
 *   <li>{@link SubscriptionDeleted} — after {@code delete()}</li>
 * </ul>
 *
 * <p>The no-op {@code @DefaultBean} implementation must NOT fire events.
 *
 * <p><strong>Scope-Dependent Authorisation:</strong> For {@link SubscriptionScope#USER USER}
 * scope subscriptions, {@code ownerId} is enforced at the SPI boundary — {@code findById},
 * {@code update}, and {@code delete} include {@code owner_id = ?} in the WHERE clause.
 * For {@link SubscriptionScope#SYSTEM SYSTEM} scope subscriptions, only {@code tenancy_id}
 * is enforced at the SPI boundary — admin authorisation is enforced at the REST layer.
 * Implementations use OR-disjunction queries:
 * {@code WHERE tenancy_id = ? AND (owner_id = ? OR scope = 'SYSTEM')}.
 *
 * <p><strong>Single Event Type:</strong> Each subscription matches exactly one event
 * type. Multi-type subscriptions require multiple subscription records.
 */
public interface SubscriptionStore {

    Subscription store(SubscriptionInput input);

    Optional<Subscription> findById(String id, String ownerId, String tenancyId);

    SubscriptionPage find(SubscriptionQuery query);

    Optional<Subscription> update(String id, String ownerId, String tenancyId, SubscriptionUpdate update);

    boolean delete(String id, String ownerId, String tenancyId);

    Stream<Subscription> findAllEnabled();
}
