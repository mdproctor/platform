package io.casehub.platform.notification.settings.jpa;

import io.casehub.platform.api.notification.settings.MuteRule;
import io.casehub.platform.api.notification.settings.MuteRuleInput;
import io.casehub.platform.api.notification.settings.Snooze;
import io.casehub.platform.api.notification.settings.SnoozeInput;
import io.casehub.platform.api.notification.settings.SuppressionStore;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * JPA-backed {@link SuppressionStore}.
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
 * <p>Expiry cleanup: {@link SuppressionRetentionScheduler} runs daily via {@code @Scheduled}
 * to purge expired mute rules and snooze records.
 *
 * <p>Do NOT combine with notification-settings-inmem in production scope.
 */
@ApplicationScoped
public class JpaSuppressionStore implements SuppressionStore {

    @Inject
    EntityManager entityManager;

    // ===== Mute rules =====

    @Override
    @Transactional
    public MuteRule addMute(MuteRuleInput input) {
        MuteRuleEntity entity = MuteRuleEntity.fromInput(input);
        entityManager.persist(entity);
        return entity.toMuteRule();
    }

    @Override
    public List<MuteRule> activeMutes(String userId, String tenancyId) {
        Instant now = Instant.now();
        List<MuteRuleEntity> entities = entityManager.createQuery(
                        "SELECT m FROM MuteRuleEntity m WHERE m.userId = :userId AND m.tenancyId = :tenancyId"
                        + " AND (m.expiresAt IS NULL OR m.expiresAt > :now)",
                        MuteRuleEntity.class)
                .setParameter("userId", userId)
                .setParameter("tenancyId", tenancyId)
                .setParameter("now", now)
                .getResultList();

        return entities.stream()
                .map(MuteRuleEntity::toMuteRule)
                .toList();
    }

    @Override
    @Transactional
    public boolean removeMute(String muteId, String userId, String tenancyId) {
        int deleted = entityManager.createQuery(
                        "DELETE FROM MuteRuleEntity m WHERE m.id = :id AND m.userId = :userId AND m.tenancyId = :tenancyId")
                .setParameter("id", muteId)
                .setParameter("userId", userId)
                .setParameter("tenancyId", tenancyId)
                .executeUpdate();

        return deleted > 0;
    }

    // ===== Snooze =====

    @Override
    @Transactional
    public Snooze activateSnooze(SnoozeInput input) {
        SnoozeEntity entity = SnoozeEntity.fromInput(input);
        // Upsert via merge — replaces existing snooze for this user
        SnoozeEntity merged = entityManager.merge(entity);
        return merged.toSnooze();
    }

    @Override
    public Optional<Snooze> activeSnooze(String userId, String tenancyId) {
        SnoozeEntity.SnoozePK pk = new SnoozeEntity.SnoozePK(userId, tenancyId);
        SnoozeEntity entity = entityManager.find(SnoozeEntity.class, pk);

        if (entity == null) {
            return Optional.empty();
        }

        // Filter expired
        Instant now = Instant.now();
        if (now.isAfter(entity.until)) {
            return Optional.empty();
        }

        return Optional.of(entity.toSnooze());
    }

    @Override
    @Transactional
    public boolean cancelSnooze(String userId, String tenancyId) {
        int deleted = entityManager.createQuery(
                        "DELETE FROM SnoozeEntity s WHERE s.userId = :userId AND s.tenancyId = :tenancyId")
                .setParameter("userId", userId)
                .setParameter("tenancyId", tenancyId)
                .executeUpdate();

        entityManager.flush();
        entityManager.clear();
        return deleted > 0;
    }
}
