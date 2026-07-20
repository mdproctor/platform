package io.casehub.platform.api.view;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public interface ViewMembershipTracker {
    Map<UUID, String> getLastKnownMembership(UUID subjectId);

    default Map<UUID, Map<UUID, String>> getLastKnownMembership(Set<UUID> subjectIds) {
        Map<UUID, Map<UUID, String>> result = new HashMap<>();
        for (UUID subjectId : subjectIds) {
            Map<UUID, String> membership = getLastKnownMembership(subjectId);
            if (!membership.isEmpty()) {
                result.put(subjectId, membership);
            }
        }
        return result;
    }

    void updateMembership(UUID subjectId, Map<UUID, String> viewIdToName);
    void removeMembership(UUID subjectId);

    Set<UUID> getSubjectsByView(UUID viewId);

    void removeMembershipByView(UUID viewId);

}
