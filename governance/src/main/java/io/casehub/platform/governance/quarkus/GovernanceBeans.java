package io.casehub.platform.governance.quarkus;

import io.casehub.platform.governance.DefaultPolicyEnforcer;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;

@ApplicationScoped
public class GovernanceBeans {

    private DefaultPolicyEnforcer enforcer;

    @Produces
    @ApplicationScoped
    public DefaultPolicyEnforcer defaultPolicyEnforcer() {
        enforcer = new DefaultPolicyEnforcer();
        return enforcer;
    }

    @PreDestroy
    void shutdown() {
        if (enforcer != null) {
            enforcer.close();
        }
    }
}
