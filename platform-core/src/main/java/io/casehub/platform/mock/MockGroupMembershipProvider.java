package io.casehub.platform.mock;

import io.casehub.platform.api.identity.GroupMember;
import io.casehub.platform.api.identity.GroupMembershipProvider;

import java.util.Set;

public class MockGroupMembershipProvider implements GroupMembershipProvider {
    @Override public Set<GroupMember> membersOf(String groupName, String tenancyId) { return Set.of(); }
}
