package io.casehub.platform.view.inmem;

import io.casehub.platform.api.view.ViewMembershipTracker;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Alternative;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Alternative
@Priority(100)
@ApplicationScoped
public class InMemoryViewMembershipTracker implements ViewMembershipTracker {

    private final ConcurrentHashMap<UUID, Map<UUID, String>> state = new ConcurrentHashMap<>();

    @Override
    public Map<UUID, String> getLastKnownMembership(UUID subjectId) {
        var membership = state.get(subjectId);
        return membership != null ? new java.util.HashMap<>(membership) : Map.of();
    }

    @Override
    public Map<UUID, Map<UUID, String>> getLastKnownMembership(Set<UUID> subjectIds) {
        Map<UUID, Map<UUID, String>> result = new HashMap<>();
        for (UUID subjectId : subjectIds) {
            Map<UUID, String> membership = getLastKnownMembership(subjectId);
            if (!membership.isEmpty()) {
                result.put(subjectId, membership);
            }
        }
        return result;
    }


    @Override
    public void updateMembership(UUID subjectId, Map<UUID, String> viewIdToName) {
        state.put(subjectId, Map.copyOf(viewIdToName));
    }

    @Override
    public void removeMembership(UUID subjectId) {
        state.remove(subjectId);
    }

    @Override
    public Set<UUID> getSubjectsByView(UUID viewId) {
        Set<UUID> result = new java.util.HashSet<>();
        state.forEach((subjectId, membership) -> {
            if (membership.containsKey(viewId)) {
                result.add(subjectId);
            }
        });
        return result;
    }

    @Override
    public void removeMembershipByView(UUID viewId) {
        state.forEach((subjectId, membership) -> {
            if (membership.containsKey(viewId)) {
                Map<UUID, String> updated = new java.util.HashMap<>(membership);
                updated.remove(viewId);
                if (updated.isEmpty()) {
                    state.remove(subjectId);
                } else {
                    state.replace(subjectId, Map.copyOf(updated));
                }
            }
        });
    }

}
