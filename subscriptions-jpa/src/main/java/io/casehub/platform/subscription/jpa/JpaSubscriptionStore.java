package io.casehub.platform.subscription.jpa;

import com.fasterxml.jackson.databind.ObjectMapper;
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
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Event;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

@ApplicationScoped
public class JpaSubscriptionStore implements SubscriptionStore {

    @Inject
    EntityManager em;

    @Inject
    ObjectMapper mapper;

    @Inject
    Event<SubscriptionCreated> createdEvent;

    @Inject
    Event<SubscriptionUpdated> updatedEvent;

    @Inject
    Event<SubscriptionDeleted> deletedEvent;

    @Override
    @Transactional
    public Subscription store(SubscriptionInput input) {
        SubscriptionEntity entity = SubscriptionEntity.fromInput(input, mapper);
        em.persist(entity);
        em.flush();
        Subscription subscription = entity.toSubscription(mapper);
        createdEvent.fireAsync(new SubscriptionCreated(subscription));
        return subscription;
    }

    @Override
    public Optional<Subscription> findById(String id, String ownerId, String tenancyId) {
        SubscriptionEntity entity = em.createQuery(
                                              "FROM SubscriptionEntity WHERE id = :id AND tenancyId = :tenancyId " +
                                              "AND (ownerId = :ownerId OR scope = 'SYSTEM')",
                                              SubscriptionEntity.class)
                                      .setParameter("id", id)
                                      .setParameter("tenancyId", tenancyId)
                                      .setParameter("ownerId", ownerId)
                                      .getResultStream().findFirst().orElse(null);
        return entity == null ? Optional.empty() : Optional.of(entity.toSubscription(mapper));
    }

    @Override
    public SubscriptionPage find(SubscriptionQuery query) {
        var effectiveScope = query.scope() != null ? query.scope() : SubscriptionScope.USER;

        StringBuilder hql    = new StringBuilder("FROM SubscriptionEntity WHERE tenancyId = :tenancyId");
        var           params = new HashMap<String, Object>();
        params.put("tenancyId", query.tenancyId());

        if (effectiveScope == SubscriptionScope.SYSTEM) {
            hql.append(" AND scope = 'SYSTEM'");
        } else {
            hql.append(" AND ownerId = :ownerId AND scope = 'USER'");
            params.put("ownerId", query.ownerId());
        }

        if (query.enabled() != null) {
            hql.append(" AND enabled = :enabled");
            params.put("enabled", query.enabled());
        }

        if (query.cursor() != null) {
            CursorValue cursor = decodeCursor(query.cursor());
            if (cursor != null) {
                hql.append(" AND (createdAt < :cursorCreatedAt")
                   .append(" OR (createdAt = :cursorCreatedAt AND id < :cursorId))");
                params.put("cursorCreatedAt", cursor.createdAt);
                params.put("cursorId", cursor.id);
            }
        }

        hql.append(" ORDER BY createdAt DESC, id DESC");

        int fetchLimit = query.limit() + 1;
        var jpaQuery   = em.createQuery(hql.toString(), SubscriptionEntity.class);
        params.forEach(jpaQuery::setParameter);
        jpaQuery.setMaxResults(fetchLimit);

        List<SubscriptionEntity> entities = jpaQuery.getResultList();
        boolean                  hasMore  = entities.size() > query.limit();
        List<SubscriptionEntity> pageEntities = hasMore
                                                ? entities.subList(0, query.limit())
                                                : entities;

        List<Subscription> subscriptions = new ArrayList<>(pageEntities.size());
        for (SubscriptionEntity entity : pageEntities) {
            subscriptions.add(entity.toSubscription(mapper));
        }

        String nextCursor = null;
        if (hasMore && !pageEntities.isEmpty()) {
            SubscriptionEntity last = pageEntities.getLast();
            nextCursor = encodeCursor(last.createdAt, last.id);
        }
        return new SubscriptionPage(subscriptions, nextCursor);
    }

    @Override
    @Transactional
    public Optional<Subscription> update(String id, String ownerId, String tenancyId,
                                         SubscriptionUpdate update) {
        SubscriptionEntity entity = em.createQuery(
                                              "FROM SubscriptionEntity WHERE id = :id AND tenancyId = :tenancyId " +
                                              "AND (ownerId = :ownerId OR scope = 'SYSTEM')",
                                              SubscriptionEntity.class)
                                      .setParameter("id", id)
                                      .setParameter("tenancyId", tenancyId)
                                      .setParameter("ownerId", ownerId)
                                      .getResultStream().findFirst().orElse(null);

        if (entity == null) {
            return Optional.empty();
        }

        Subscription previous = entity.toSubscription(mapper);
        applyUpdate(entity, update);
        Subscription updated = entity.toSubscription(mapper);
        updatedEvent.fireAsync(new SubscriptionUpdated(updated, previous));
        return Optional.of(updated);
    }

    @Override
    @Transactional
    public boolean delete(String id, String ownerId, String tenancyId) {
        SubscriptionEntity entity = em.createQuery(
                                              "FROM SubscriptionEntity WHERE id = :id AND tenancyId = :tenancyId " +
                                              "AND (ownerId = :ownerId OR scope = 'SYSTEM')",
                                              SubscriptionEntity.class)
                                      .setParameter("id", id)
                                      .setParameter("tenancyId", tenancyId)
                                      .setParameter("ownerId", ownerId)
                                      .getResultStream().findFirst().orElse(null);

        if (entity == null) {
            return false;
        }

        Subscription subscription = entity.toSubscription(mapper);
        em.remove(entity);
        deletedEvent.fireAsync(new SubscriptionDeleted(subscription));
        return true;
    }

    @Override
    public Stream<Subscription> findAllEnabled() {
        return em.createQuery("FROM SubscriptionEntity WHERE enabled = true", SubscriptionEntity.class)
                 .getResultList()
                 .stream()
                 .map(entity -> entity.toSubscription(mapper));
    }

    private void applyUpdate(SubscriptionEntity entity, SubscriptionUpdate update) {
        if (update.name() != null) {
            entity.name = update.name();
        }
        if (update.eventType() != null) {
            entity.eventType = update.eventType();
        }
        if (update.filters() != null) {
            entity.filtersJson = SubscriptionEntity.serializeFilters(update.filters(), mapper);
        }
        if (update.targets() != null) {
            entity.targetsJson = SubscriptionEntity.serializeTargets(update.targets(), mapper);
        }
        if (update.includeActor() != null) {
            entity.includeActor = update.includeActor();
        }
        if (update.template() != null) {
            entity.templateJson = SubscriptionEntity.serializeTemplate(update.template(), mapper);
        }
        if (update.enabled() != null) {
            entity.enabled = update.enabled();
        }
        entity.updatedAt = Instant.now();
    }

    private static String encodeCursor(Instant createdAt, String id) {
        String raw = createdAt.toEpochMilli() + "|" + id;
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes());
    }

    private static CursorValue decodeCursor(String cursor) {
        try {
            String raw = new String(Base64.getUrlDecoder().decode(cursor));
            int    sep = raw.indexOf('|');
            if (sep == -1) {return null;}
            long   epochMillis = Long.parseLong(raw.substring(0, sep));
            String id          = raw.substring(sep + 1);
            return new CursorValue(Instant.ofEpochMilli(epochMillis), id);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private record CursorValue(Instant createdAt, String id) {}
}
