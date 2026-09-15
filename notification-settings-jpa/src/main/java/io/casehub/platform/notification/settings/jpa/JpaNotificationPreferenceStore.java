package io.casehub.platform.notification.settings.jpa;

import io.casehub.platform.api.notification.settings.NotificationPreferenceStore;
import io.casehub.platform.api.notification.settings.NotificationPreferenceUpdate;
import io.casehub.platform.api.notification.settings.NotificationPreferences;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;

import java.util.Optional;

/**
 * JPA-backed {@link NotificationPreferenceStore}.
 *
 * <p>Tier 2 in the CDI priority ladder — {@code @ApplicationScoped} beats
 * {@code @DefaultBean} no-op (Tier 1) when on the classpath. Beaten by
 * {@code @Alternative @Priority(100)} in-memory (Tier 4).
 *
 * <p>Hibernate ORM (blocking-only — no Hibernate Reactive overhead,
 * since no reactive SPI exists). Uses EntityManager directly per the
 * GE-20260512-66d997 pattern.
 *
 * <p>PostgreSQL, Flyway V1 at {@code classpath:db/notification-settings/migration}.
 * Consumers must add that location to {@code quarkus.flyway.locations}.
 *
 * <p>Do NOT combine with notification-settings-inmem in production scope.
 */
@ApplicationScoped
public class JpaNotificationPreferenceStore implements NotificationPreferenceStore {

    @Inject
    EntityManager entityManager;

    @Override
    public Optional<NotificationPreferences> get(String userId, String tenancyId) {
        NotificationPreferencesEntity.PreferencesPK pk =
                new NotificationPreferencesEntity.PreferencesPK(userId, tenancyId);
        NotificationPreferencesEntity entity = entityManager.find(NotificationPreferencesEntity.class, pk);
        return Optional.ofNullable(entity).map(NotificationPreferencesEntity::toPreferences);
    }

    @Override
    @Transactional
    public NotificationPreferences update(String userId, String tenancyId, NotificationPreferenceUpdate update) {
        NotificationPreferencesEntity.PreferencesPK pk =
                new NotificationPreferencesEntity.PreferencesPK(userId, tenancyId);
        NotificationPreferencesEntity existing = entityManager.find(NotificationPreferencesEntity.class, pk);

        NotificationPreferencesEntity entity = NotificationPreferencesEntity.fromUpdate(
                userId, tenancyId, update, existing);

        // Upsert via merge
        NotificationPreferencesEntity merged = entityManager.merge(entity);
        return merged.toPreferences();
    }
}
