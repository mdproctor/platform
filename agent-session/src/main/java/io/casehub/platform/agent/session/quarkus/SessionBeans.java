package io.casehub.platform.agent.session.quarkus;

import io.casehub.platform.agent.BackendInstanceRegistry;
import io.casehub.platform.agent.config.ManifestResult;
import io.casehub.platform.agent.session.SessionLifecycleManager;
import io.casehub.platform.agent.session.SessionPoolRegistry;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Any;
import jakarta.enterprise.inject.Instance;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Inject;

import java.util.List;

@ApplicationScoped
public class SessionBeans {

    @Inject
    BackendInstanceRegistry backends;

    @Inject @Any
    Instance<ManifestResult> manifestResult;

    private volatile SessionPoolRegistry registry;

    @Produces
    @ApplicationScoped
    public SessionPoolRegistry sessionPoolRegistry() {
        var pools = manifestResult.isResolvable()
                ? manifestResult.get().pools()
                : List.<io.casehub.platform.agent.config.PoolDeclaration>of();
        registry = new SessionPoolRegistry(pools, backends);
        return registry;
    }

    @Produces
    @ApplicationScoped
    public SessionLifecycleManager sessionLifecycleManager(SessionPoolRegistry poolRegistry) {
        return new SessionLifecycleManager(poolRegistry);
    }


    @PreDestroy
    void shutdown() {
        if (registry != null) {
            registry.shutdown();
        }
    }
}
