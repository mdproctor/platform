package io.casehub.platform.view.jpa;

import io.casehub.platform.api.view.ViewMembershipTracker;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@ApplicationScoped
public class JpaViewMembershipTracker implements ViewMembershipTracker {

    @Inject
    EntityManager em;

    @Override
    public Map<UUID, String> getLastKnownMembership(UUID subjectId) {
        return em.createQuery(
                         "SELECT e FROM ViewMembershipEntity e WHERE e.subjectId = :sid",
                         ViewMembershipEntity.class)
                 .setParameter("sid", subjectId)
                 .getResultList()
                 .stream()
                 .collect(Collectors.toMap(e -> e.viewId, e -> e.viewName));
    }

    @Override
    public Map<UUID, Map<UUID, String>> getLastKnownMembership(Set<UUID> subjectIds) {
        if (subjectIds.isEmpty()) {return Map.of();}
        return em.createQuery(
                         "SELECT e FROM ViewMembershipEntity e WHERE e.subjectId IN :sids",
                         ViewMembershipEntity.class)
                 .setParameter("sids", subjectIds)
                 .getResultList()
                 .stream()
                 .collect(Collectors.groupingBy(
                         e -> e.subjectId,
                         Collectors.toMap(e -> e.viewId, e -> e.viewName)));
    }


    @Override
    @Transactional
    public void updateMembership(UUID subjectId, Map<UUID, String> viewIdToName) {
        em.createQuery(
                  "SELECT e FROM ViewMembershipEntity e WHERE e.subjectId = :sid",
                  ViewMembershipEntity.class)
          .setParameter("sid", subjectId)
          .getResultList()
          .forEach(em::remove);
        em.flush();

        viewIdToName.forEach((viewId, viewName) -> {
            var entity = new ViewMembershipEntity();
            entity.subjectId = subjectId;
            entity.viewId    = viewId;
            entity.viewName  = viewName;
            em.persist(entity);
        });
    }

    @Override
    @Transactional
    public void removeMembership(UUID subjectId) {
        em.createQuery("DELETE FROM ViewMembershipEntity e WHERE e.subjectId = :sid")
          .setParameter("sid", subjectId)
          .executeUpdate();
    }

    @Override
    public Set<UUID> getSubjectsByView(UUID viewId) {
        return new java.util.HashSet<>(em.createQuery(
                                                 "SELECT DISTINCT e.subjectId FROM ViewMembershipEntity e WHERE e.viewId = :vid",
                                                 UUID.class)
                                         .setParameter("vid", viewId)
                                         .getResultList());
    }

    @Override
    @Transactional
    public void removeMembershipByView(UUID viewId) {
        em.createQuery("DELETE FROM ViewMembershipEntity e WHERE e.viewId = :vid")
          .setParameter("vid", viewId)
          .executeUpdate();
    }

}
