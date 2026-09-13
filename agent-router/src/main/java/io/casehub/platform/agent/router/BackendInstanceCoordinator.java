package io.casehub.platform.agent.router;

import io.casehub.platform.agent.AgentBackend;
import io.casehub.platform.agent.BackendInstance;
import io.casehub.platform.agent.BackendInstanceFactory;
import io.casehub.platform.agent.BackendInstanceRegistry;
import io.casehub.platform.api.credentials.LlmCredentialStore;
import io.casehub.platform.api.identity.TenancyConstants;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.inject.Any;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import io.quarkus.runtime.StartupEvent;
import org.jboss.logging.Logger;

import java.util.List;
import java.util.Map;

@ApplicationScoped
public class BackendInstanceCoordinator {

    private static final Logger LOG = Logger.getLogger(BackendInstanceCoordinator.class);

    private final BackendInstanceRegistry registry;
    private final Iterable<AgentBackend> cdiBackends;
    private final Iterable<BackendInstanceFactory> factories;
    private final LlmCredentialStore credentialStore;

    @Inject
    public BackendInstanceCoordinator(BackendInstanceRegistry registry,
                                      @Any Instance<AgentBackend> cdiBackends,
                                      @Any Instance<BackendInstanceFactory> factories,
                                      LlmCredentialStore credentialStore) {
        this.registry = registry;
        this.cdiBackends = cdiBackends;
        this.factories = factories;
        this.credentialStore = credentialStore;
    }

    BackendInstanceCoordinator(BackendInstanceRegistry registry,
                               Iterable<AgentBackend> cdiBackends,
                               Iterable<BackendInstanceFactory> factories,
                               LlmCredentialStore credentialStore) {
        this.registry = registry;
        this.cdiBackends = cdiBackends;
        this.factories = factories;
        this.credentialStore = credentialStore;
    }

    void onStartup(@Observes @Priority(75) StartupEvent event) {
        onStartup();
    }

    void onStartup() {
        for (AgentBackend backend : cdiBackends) {
            registry.register(backend);
            LOG.infof("Registered CDI backend: %s/%s", backend.key(), backend.instanceId());
        }

        List<String> refs = credentialStore.listRefs(TenancyConstants.PLATFORM_TENANT_ID);
        for (String ref : refs) {
            Map<String, String> creds = credentialStore.resolve(TenancyConstants.PLATFORM_TENANT_ID, ref);
            if (creds.isEmpty()) continue;
            for (BackendInstanceFactory factory : factories) {
                if (factory.handles(ref, creds)) {
                    BackendInstance instance = factory.create(ref, creds);
                    registry.register(new InstanceWrapper(
                            factory.backendKey(), instance.instanceId(), instance.backend()));
                    LOG.infof("Registered factory backend: %s/%s (from %s)",
                            factory.backendKey(), instance.instanceId(), ref);
                }
            }
        }
    }
}
