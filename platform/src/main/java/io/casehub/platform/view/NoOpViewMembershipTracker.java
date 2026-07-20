package io.casehub.platform.view;

import io.casehub.platform.api.view.ViewMembershipTracker;
import io.quarkus.arc.DefaultBean;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.Map;
import java.util.Set;
import java.util.UUID;

@DefaultBean
@ApplicationScoped
public class NoOpViewMembershipTracker implements ViewMembershipTracker {

    @Override
    public Map<UUID, String> getLastKnownMembership(UUID subjectId) {
        return Map.of();
    }

    @Override
    public Map<UUID, Map<UUID, String>> getLastKnownMembership(Set<UUID> subjectIds) {
        return Map.of();
    }


    @Override
    public void updateMembership(UUID subjectId, Map<UUID, String> viewIdToName) {
    }

    @Override
    public void removeMembership(UUID subjectId) {
    }

    @Override
    public Set<UUID> getSubjectsByView(UUID viewId) {
        return Set.of();
    }

    @Override
    public void removeMembershipByView(UUID viewId) {
    }

}
