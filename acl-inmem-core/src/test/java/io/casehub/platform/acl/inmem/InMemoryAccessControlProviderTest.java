package io.casehub.platform.acl.inmem;

import io.casehub.platform.api.acl.AccessControlProvider;
import io.casehub.platform.api.acl.AccessControlProviderContractTest;
import io.casehub.platform.api.identity.CurrentPrincipal;
import io.casehub.platform.api.identity.GroupMember;
import io.casehub.platform.api.identity.GroupMembershipProvider;

import java.util.List;
import java.util.Set;

class InMemoryAccessControlProviderTest extends AccessControlProviderContractTest {

    private String currentTenancyId = "test-tenant";

    private final GroupMembershipProvider groupMembership = new GroupMembershipProvider() {
        @Override
        public Set<GroupMember> membersOf(String groupName, String tenancyId) {
            return Set.of();
        }

        @Override
        public List<String> groupsOf(String actorId, String tenancyId) {
            if ("actor1".equals(actorId)) {return List.of("managers");}
            return List.of();
        }
    };

    private final CurrentPrincipal testPrincipal = new CurrentPrincipal() {
        @Override
        public String actorId()             {return "system";}

        @Override
        public Set<String> groups()         {return Set.of();}

        @Override
        public boolean isSystem()           {return true;}

        @Override
        public boolean isAuthenticated()    {return true;}

        @Override
        public String tenancyId()           {return currentTenancyId;}

        @Override
        public boolean isCrossTenantAdmin() {return false;}
    };

    private InMemoryAccessControlProvider provider;

    @Override
    protected AccessControlProvider provider() {
        return provider;
    }

    @Override
    protected GroupMembershipProvider groupMembership() {
        return groupMembership;
    }

    @Override
    protected String tenancyId() {
        return "test-tenant";
    }

    @Override
    protected void setTenancyId(String tenancyId) {
        this.currentTenancyId = tenancyId;
    }

    @Override
    protected void clearState() {
        currentTenancyId = "test-tenant";
        provider         = new InMemoryAccessControlProvider(groupMembership, testPrincipal);
    }
}
