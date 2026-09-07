package io.casehub.platform.acl.inmem.quarkus;

import io.casehub.platform.acl.inmem.InMemoryAccessControlProvider;
import io.casehub.platform.acl.inmem.InMemoryWorkerCredentialStore;
import io.casehub.platform.api.identity.CurrentPrincipal;
import io.casehub.platform.api.identity.GroupMembershipProvider;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Alternative;
import jakarta.enterprise.inject.Produces;

@ApplicationScoped
public class AclInmemBeans {

    @Produces
    @Alternative
    @Priority(10)
    @ApplicationScoped
    public InMemoryAccessControlProvider inMemoryAccessControlProvider(
            GroupMembershipProvider groupMembership, CurrentPrincipal principal) {
        return new InMemoryAccessControlProvider(groupMembership, principal);
    }

    @Produces
    @Alternative
    @Priority(10)
    @ApplicationScoped
    public InMemoryWorkerCredentialStore inMemoryWorkerCredentialStore() {
        return new InMemoryWorkerCredentialStore();
    }
}
