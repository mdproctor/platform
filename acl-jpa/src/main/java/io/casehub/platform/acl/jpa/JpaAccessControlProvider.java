package io.casehub.platform.acl.jpa;

import io.casehub.platform.api.acl.AccessControlProvider;
import io.casehub.platform.api.acl.AclAction;
import io.casehub.platform.api.acl.AclEntryRequest;
import io.casehub.platform.api.acl.ResourceId;
import io.casehub.platform.api.acl.AclPage;
import io.casehub.platform.api.acl.AclQuery;
import io.casehub.platform.api.identity.CurrentPrincipal;
import io.casehub.platform.api.identity.GroupMembershipProvider;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@ApplicationScoped
public class JpaAccessControlProvider implements AccessControlProvider {

    @Inject
    GroupMembershipProvider groupMembership;

    @Inject
    CurrentPrincipal principal;

    @Inject
    jakarta.persistence.EntityManager entityManager;

    @Override
    public boolean canAccess(String actorId, ResourceId resourceId, AclAction action) {
        Set<String> candidates = buildCandidateSet(actorId);
        return resolveAccess(candidates, resourceId, action, 0);
    }

    @Override
    @Transactional
    public void grant(String actorId, ResourceId resourceId, AclAction action, Instant expires) {
        upsertEntry(actorId, resourceId, action, expires, "ALLOW", "GRANT");
    }

    @Override
    @Transactional
    public void deny(String actorId, ResourceId resourceId, AclAction action, Instant expires) {
        upsertEntry(actorId, resourceId, action, expires, "DENY", "DENY");
    }

    @Override
    @Transactional
    public void grantBatch(java.util.Collection<AclEntryRequest> requests) {
        requests.forEach(r -> grant(r.actorId(), r.resourceId(), r.action(), r.expiresAt()));
    }

    @Override
    @Transactional
    public void denyBatch(java.util.Collection<AclEntryRequest> requests) {
        requests.forEach(r -> deny(r.actorId(), r.resourceId(), r.action(), r.expiresAt()));
    }

    @Override
    @Transactional
    public void revoke(String actorId, ResourceId resourceId, AclAction action) {
        removeEntry(actorId, resourceId, action, "ALLOW", "REVOKE");
    }

    @Override
    @Transactional
    public void removeDeny(String actorId, ResourceId resourceId, AclAction action) {
        removeEntry(actorId, resourceId, action, "DENY", "REVOKE_DENY");
    }

    @Override
    @Transactional
    public void revokeBatch(java.util.Collection<AclEntryRequest> requests) {
        requests.forEach(r -> revoke(r.actorId(), r.resourceId(), r.action()));
    }

    @Override
    @Transactional
    public void removeDenyBatch(java.util.Collection<AclEntryRequest> requests) {
        requests.forEach(r -> removeDeny(r.actorId(), r.resourceId(), r.action()));
    }

    @Override
    @Transactional
    public void revokeAll(String actorId, ResourceId resourceId) {
        Instant now       = Instant.now();
        String  tenancyId = principal.tenancyId();
        String  resIdStr  = resourceId.toString();
        List<AclEntryEntity> entries = entityManager.createQuery(
                "from AclEntryEntity where actorId = ?1 and resourceId = ?2 and tenancyId = ?3",
                AclEntryEntity.class)
                .setParameter(1, actorId).setParameter(2, resIdStr).setParameter(3, tenancyId)
                .getResultList();
        for (AclEntryEntity entry : entries) {
            AclAuditLogEntity log = new AclAuditLogEntity();
            log.actorId     = actorId;
            log.resourceId  = resIdStr;
            log.action      = entry.action;
            log.operation   = "ALLOW".equals(entry.entryType) ? "REVOKE" : "REVOKE_DENY";
            log.performedBy = principal.actorId();
            log.performedAt = now;
            log.tenancyId   = tenancyId;
            entityManager.persist(log);
        }
        entityManager.createQuery("delete from AclEntryEntity where actorId = ?1 and resourceId = ?2 and tenancyId = ?3")
                .setParameter(1, actorId).setParameter(2, resIdStr).setParameter(3, tenancyId)
                .executeUpdate();
    }

    @Override
    @Transactional
    public void registerParent(ResourceId childResourceId, ResourceId parentResourceId) {
        String tenancyId = principal.tenancyId();
        String childStr  = childResourceId.toString();
        ResourceParentEntity existing = entityManager.find(ResourceParentEntity.class,
                new ResourceParentKey(childStr, tenancyId));
        if (existing == null) {
            ResourceParentEntity rp = new ResourceParentEntity();
            rp.childResourceId  = childStr;
            rp.parentResourceId = parentResourceId.toString();
            rp.tenancyId        = tenancyId;
            entityManager.persist(rp);
        } else {
            existing.parentResourceId = parentResourceId.toString();
            entityManager.merge(existing);
        }
    }

    @Override
    public List<ResourceId> accessibleResources(String actorId, String resourceType, AclAction action) {
        Set<String>  candidates        = buildCandidateSet(actorId);
        String       escaped           = resourceType.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
        String       prefix            = escaped + ":%";
        List<String> satisfyingActions = action.satisfiedBy().stream().map(Enum::name).toList();
        List<String> deniedByActions   = action.deniedBy().stream().map(Enum::name).toList();

        List<String> granted;
        if (shouldFilterByTenant()) {
            granted = entityManager.createQuery(
                    "select distinct e.resourceId from AclEntryEntity e " +
                    "where e.entryType = 'ALLOW' and e.action in ?1 " +
                    "and (e.expiresAt is null or e.expiresAt > ?2) " +
                    "and e.actorId in ?3 and e.resourceId like ?4 escape '\\' " +
                    "and e.tenancyId = ?5", String.class)
                    .setParameter(1, satisfyingActions).setParameter(2, Instant.now())
                    .setParameter(3, candidates).setParameter(4, prefix)
                    .setParameter(5, principal.tenancyId()).getResultList();
        } else {
            granted = entityManager.createQuery(
                    "select distinct e.resourceId from AclEntryEntity e " +
                    "where e.entryType = 'ALLOW' and e.action in ?1 " +
                    "and (e.expiresAt is null or e.expiresAt > ?2) " +
                    "and e.actorId in ?3 and e.resourceId like ?4 escape '\\'", String.class)
                    .setParameter(1, satisfyingActions).setParameter(2, Instant.now())
                    .setParameter(3, candidates).setParameter(4, prefix).getResultList();
        }

        Set<String> denied = fetchDeniedResources(candidates, deniedByActions, prefix);

        java.util.LinkedHashSet<String> result = new java.util.LinkedHashSet<>(granted);
        result.removeAll(denied);

        return result.stream().map(ResourceId::parse).collect(java.util.stream.Collectors.toCollection(java.util.ArrayList::new));
    }

    @Override
    public AclPage accessibleResources(AclQuery query) {
        Set<String>  candidates        = buildCandidateSet(query.actorId());
        String       escaped           = query.resourceType().replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
        String       prefix            = escaped + ":%";
        List<String> satisfyingActions = query.action().satisfiedBy().stream().map(Enum::name).toList();
        List<String> deniedByActions   = query.action().deniedBy().stream().map(Enum::name).toList();
        int          fetchLimit        = query.limit() + 1;

        Set<String> denied = fetchDeniedResources(candidates, deniedByActions, prefix);

        List<String> results;
        if (query.cursor() != null) {
            if (shouldFilterByTenant()) {
                results = entityManager.createQuery(
                        "select distinct e.resourceId from AclEntryEntity e " +
                        "where e.entryType = 'ALLOW' and e.action in ?1 " +
                        "and (e.expiresAt is null or e.expiresAt > ?2) " +
                        "and e.actorId in ?3 and e.resourceId like ?4 escape '\\' " +
                        "and e.tenancyId = ?5 and e.resourceId > ?6 order by e.resourceId", String.class)
                        .setParameter(1, satisfyingActions).setParameter(2, Instant.now())
                        .setParameter(3, candidates).setParameter(4, prefix)
                        .setParameter(5, principal.tenancyId()).setParameter(6, query.cursor())
                        .setMaxResults(fetchLimit).getResultList();
            } else {
                results = entityManager.createQuery(
                        "select distinct e.resourceId from AclEntryEntity e " +
                        "where e.entryType = 'ALLOW' and e.action in ?1 " +
                        "and (e.expiresAt is null or e.expiresAt > ?2) " +
                        "and e.actorId in ?3 and e.resourceId like ?4 escape '\\' " +
                        "and e.resourceId > ?5 order by e.resourceId", String.class)
                        .setParameter(1, satisfyingActions).setParameter(2, Instant.now())
                        .setParameter(3, candidates).setParameter(4, prefix)
                        .setParameter(5, query.cursor())
                        .setMaxResults(fetchLimit).getResultList();
            }
        } else {
            if (shouldFilterByTenant()) {
                results = entityManager.createQuery(
                        "select distinct e.resourceId from AclEntryEntity e " +
                        "where e.entryType = 'ALLOW' and e.action in ?1 " +
                        "and (e.expiresAt is null or e.expiresAt > ?2) " +
                        "and e.actorId in ?3 and e.resourceId like ?4 escape '\\' " +
                        "and e.tenancyId = ?5 order by e.resourceId", String.class)
                        .setParameter(1, satisfyingActions).setParameter(2, Instant.now())
                        .setParameter(3, candidates).setParameter(4, prefix)
                        .setParameter(5, principal.tenancyId())
                        .setMaxResults(fetchLimit).getResultList();
            } else {
                results = entityManager.createQuery(
                        "select distinct e.resourceId from AclEntryEntity e " +
                        "where e.entryType = 'ALLOW' and e.action in ?1 " +
                        "and (e.expiresAt is null or e.expiresAt > ?2) " +
                        "and e.actorId in ?3 and e.resourceId like ?4 escape '\\' " +
                        "order by e.resourceId", String.class)
                        .setParameter(1, satisfyingActions).setParameter(2, Instant.now())
                        .setParameter(3, candidates).setParameter(4, prefix)
                        .setMaxResults(fetchLimit).getResultList();
            }
        }

        results = new java.util.ArrayList<>(results);
        results.removeAll(denied);

        if (results.size() > query.limit()) {
            List<String> page = results.subList(0, query.limit());
            return new AclPage(page.stream().map(ResourceId::parse).toList(), page.getLast());
        }
        return new AclPage(results.stream().map(ResourceId::parse).toList(), null);
    }

    @Override
    public List<ResourceId> accessibleResourcesIncludingInherited(String actorId, String resourceType, AclAction action) {
        Set<String>  candidates        = buildCandidateSet(actorId);
        String       escaped           = resourceType.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
        List<String> satisfyingActions = action.satisfiedBy().stream().map(Enum::name).toList();
        List<String> deniedByActions   = action.deniedBy().stream().map(Enum::name).toList();
        String       prefix            = escaped + ":%";

        List<ResourceId> directResources = accessibleResources(actorId, resourceType, action);

        Set<String> allGrantedResources = new java.util.LinkedHashSet<>();
        if (shouldFilterByTenant()) {
            List<String> tenantFilteredGrants = entityManager.createQuery(
                    "select distinct e.resourceId from AclEntryEntity e " +
                    "where e.entryType = 'ALLOW' and e.action in ?1 " +
                    "and (e.expiresAt is null or e.expiresAt > ?2) " +
                    "and e.actorId in ?3 and e.tenancyId = ?4", String.class)
                    .setParameter(1, satisfyingActions).setParameter(2, Instant.now())
                    .setParameter(3, candidates).setParameter(4, principal.tenancyId())
                    .getResultList();
            allGrantedResources.addAll(tenantFilteredGrants);
        } else {
            List<String> allGrants = entityManager.createQuery(
                    "select distinct e.resourceId from AclEntryEntity e " +
                    "where e.entryType = 'ALLOW' and e.action in ?1 " +
                    "and (e.expiresAt is null or e.expiresAt > ?2) " +
                    "and e.actorId in ?3", String.class)
                    .setParameter(1, satisfyingActions).setParameter(2, Instant.now())
                    .setParameter(3, candidates)
                    .getResultList();
            allGrantedResources.addAll(allGrants);
        }

        if (allGrantedResources.isEmpty()) {
            return directResources;
        }

        String nativeSql =
                "WITH RECURSIVE children AS (" +
                "  SELECT rp.child_resource_id FROM resource_parent rp " +
                "  WHERE rp.parent_resource_id IN (:grantedResources)" +
                (shouldFilterByTenant() ? " AND rp.tenancy_id = :tenancyId" : "") +
                "  UNION " +
                "  SELECT rp2.child_resource_id FROM resource_parent rp2 " +
                "  JOIN children c ON rp2.parent_resource_id = c.child_resource_id" +
                (shouldFilterByTenant() ? " WHERE rp2.tenancy_id = :tenancyId" : "") +
                ") " +
                "SELECT child_resource_id FROM children " +
                "WHERE child_resource_id LIKE :prefix";

        @SuppressWarnings("unchecked")
        jakarta.persistence.Query query = entityManager.createNativeQuery(nativeSql);
        query.setParameter("grantedResources", allGrantedResources);
        query.setParameter("prefix", escaped + ":%");
        if (shouldFilterByTenant()) {
            query.setParameter("tenancyId", principal.tenancyId());
        }

        List<String> inheritedChildren = query.getResultList();

        Set<String> denied = fetchDeniedResources(candidates, deniedByActions, prefix);

        Set<ResourceId> result = new java.util.LinkedHashSet<>(directResources);
        for (String child : inheritedChildren) {
            if (!denied.contains(child)) {
                result.add(ResourceId.parse(child));
            }
        }
        return new java.util.ArrayList<>(result);
    }

    private void upsertEntry(String actorId, ResourceId resourceId, AclAction action, Instant expires,
                             String entryType, String auditOp) {
        Instant now       = Instant.now();
        String  tenancyId = principal.tenancyId();
        String  resIdStr  = resourceId.toString();

        List<AclEntryEntity> existing = entityManager.createQuery(
                "from AclEntryEntity where actorId = ?1 and resourceId = ?2 and action = ?3 and tenancyId = ?4 and entryType = ?5",
                AclEntryEntity.class)
                .setParameter(1, actorId).setParameter(2, resIdStr).setParameter(3, action.name())
                .setParameter(4, tenancyId).setParameter(5, entryType)
                .getResultList();
        if (!existing.isEmpty()) {
            AclEntryEntity entry = existing.getFirst();
            entry.expiresAt = expires;
            entry.grantedAt = now;
            entityManager.persist(entry);
        } else {
            AclEntryEntity entry = new AclEntryEntity();
            entry.actorId    = actorId;
            entry.resourceId = resIdStr;
            entry.action     = action.name();
            entry.entryType  = entryType;
            entry.grantedAt  = now;
            entry.expiresAt  = expires;
            entry.tenancyId  = tenancyId;
            entityManager.persist(entry);
        }

        AclAuditLogEntity log = new AclAuditLogEntity();
        log.actorId     = actorId;
        log.resourceId  = resIdStr;
        log.action      = action.name();
        log.operation   = auditOp;
        log.performedBy = principal.actorId();
        log.performedAt = now;
        log.expiresAt   = expires;
        log.tenancyId   = tenancyId;
        entityManager.persist(log);
    }

    private void removeEntry(String actorId, ResourceId resourceId, AclAction action,
                             String entryType, String auditOp) {
        String tenancyId = principal.tenancyId();
        String resIdStr  = resourceId.toString();
        long count = entityManager.createQuery("delete from AclEntryEntity where actorId = ?1 and resourceId = ?2 and action = ?3 and tenancyId = ?4 and entryType = ?5")
                .setParameter(1, actorId).setParameter(2, resIdStr).setParameter(3, action.name())
                .setParameter(4, tenancyId).setParameter(5, entryType)
                .executeUpdate();
        if (count > 0) {
            AclAuditLogEntity log = new AclAuditLogEntity();
            log.actorId     = actorId;
            log.resourceId  = resIdStr;
            log.action      = action.name();
            log.operation   = auditOp;
            log.performedBy = principal.actorId();
            log.performedAt = Instant.now();
            log.tenancyId   = tenancyId;
            entityManager.persist(log);
        }
    }

    private Set<String> buildCandidateSet(String actorId) {
        Set<String> candidates = new HashSet<>();
        candidates.add(actorId);
        for (String group : groupMembership.groupsOf(actorId, principal.tenancyId())) {
            candidates.add("group:" + group);
        }
        return candidates;
    }

    private boolean resolveAccess(Set<String> candidates, ResourceId resourceId,
                                  AclAction action, int depth) {
        if (depth > 20) {return false;}

        int resolution = resolveAt(candidates, resourceId, action);
        if (resolution != 0) {return resolution > 0;}

        ResourceParentEntity parent = entityManager.find(ResourceParentEntity.class,
                new ResourceParentKey(resourceId.toString(), principal.tenancyId()));
        if (parent != null) {
            return resolveAccess(candidates, ResourceId.parse(parent.parentResourceId), action, depth + 1);
        }
        return false;
    }

    private int resolveAt(Set<String> candidates, ResourceId resourceId, AclAction action) {
        List<String> deniedByActions   = action.deniedBy().stream().map(Enum::name).toList();
        List<String> satisfyingActions = action.satisfiedBy().stream().map(Enum::name).toList();
        String       resIdStr          = resourceId.toString();

        // 1. Instance deny
        if (hasEntry(candidates, resIdStr, deniedByActions, "DENY")) {return -1;}
        // 2. Instance grant
        if (hasEntry(candidates, resIdStr, satisfyingActions, "ALLOW")) {return 1;}

        // 3-4. Wildcard
        String wildcardStr = new ResourceId(resourceId.type(), "*").toString();
        if (!wildcardStr.equals(resIdStr)) {
            if (hasEntry(candidates, wildcardStr, deniedByActions, "DENY")) {return -1;}
            if (hasEntry(candidates, wildcardStr, satisfyingActions, "ALLOW")) {return 1;}
        }

        return 0;
    }

    private boolean hasEntry(Set<String> candidates, String resourceId, List<String> actions, String entryType) {
        if (shouldFilterByTenant()) {
            long count = entityManager.createQuery(
                    "select count(e) from AclEntryEntity e where e.actorId in ?1 and e.resourceId = ?2 and e.action in ?3 " +
                    "and e.entryType = ?4 and (e.expiresAt is null or e.expiresAt > ?5) and e.tenancyId = ?6", Long.class)
                    .setParameter(1, candidates).setParameter(2, resourceId).setParameter(3, actions)
                    .setParameter(4, entryType).setParameter(5, Instant.now()).setParameter(6, principal.tenancyId())
                    .getSingleResult();
            return count > 0;
        }
        long count = entityManager.createQuery(
                "select count(e) from AclEntryEntity e where e.actorId in ?1 and e.resourceId = ?2 and e.action in ?3 " +
                "and e.entryType = ?4 and (e.expiresAt is null or e.expiresAt > ?5)", Long.class)
                .setParameter(1, candidates).setParameter(2, resourceId).setParameter(3, actions)
                .setParameter(4, entryType).setParameter(5, Instant.now())
                .getSingleResult();
        return count > 0;
    }

    private Set<String> fetchDeniedResources(Set<String> candidates, List<String> deniedByActions, String prefix) {
        List<String> deniedList;
        if (shouldFilterByTenant()) {
            deniedList = entityManager.createQuery(
                    "select distinct e.resourceId from AclEntryEntity e " +
                    "where e.entryType = 'DENY' and e.action in ?1 " +
                    "and (e.expiresAt is null or e.expiresAt > ?2) " +
                    "and e.actorId in ?3 and e.resourceId like ?4 escape '\\' " +
                    "and e.tenancyId = ?5", String.class)
                    .setParameter(1, deniedByActions).setParameter(2, Instant.now())
                    .setParameter(3, candidates).setParameter(4, prefix)
                    .setParameter(5, principal.tenancyId()).getResultList();
        } else {
            deniedList = entityManager.createQuery(
                    "select distinct e.resourceId from AclEntryEntity e " +
                    "where e.entryType = 'DENY' and e.action in ?1 " +
                    "and (e.expiresAt is null or e.expiresAt > ?2) " +
                    "and e.actorId in ?3 and e.resourceId like ?4 escape '\\'", String.class)
                    .setParameter(1, deniedByActions).setParameter(2, Instant.now())
                    .setParameter(3, candidates).setParameter(4, prefix).getResultList();
        }
        return new java.util.HashSet<>(deniedList);
    }

    private boolean shouldFilterByTenant() {
        return !principal.isCrossTenantAdmin();
    }
}
