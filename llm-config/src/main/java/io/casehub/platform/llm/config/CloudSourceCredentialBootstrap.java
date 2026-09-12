package io.casehub.platform.llm.config;

import io.casehub.platform.api.credentials.LlmCredentialStore;
import io.casehub.platform.api.identity.TenancyConstants;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;
import java.util.Map;
import java.util.function.Function;

@ApplicationScoped
public class CloudSourceCredentialBootstrap {

    private static final Logger LOG = Logger.getLogger(CloudSourceCredentialBootstrap.class);
    private static final String TENANT = TenancyConstants.PLATFORM_TENANT_ID;

    private final LlmCredentialStore credentialStore;
    private final Function<String, String> envLookup;

    @Inject
    CloudSourceCredentialBootstrap(LlmCredentialStore credentialStore) {
        this(credentialStore, System::getenv);
    }

    CloudSourceCredentialBootstrap(LlmCredentialStore credentialStore,
                                   Function<String, String> envLookup) {
        this.credentialStore = credentialStore;
        this.envLookup = envLookup;
    }

    public void detectAndSeed() {
        LOG.info("Cloud model sources credential bootstrap:");
        seedApiKey("ANTHROPIC_API_KEY", "cloud-anthropic", "anthropic");
        seedApiKey("OPENAI_API_KEY", "cloud-openai", "openai");
    }

    private void seedApiKey(String envVar, String credentialRef, String vendorName) {
        if (!credentialStore.resolve(TENANT, credentialRef).isEmpty()) {
            LOG.infof("  %s: credentials already configured", vendorName);
            return;
        }
        String value = envLookup.apply(envVar);
        if (value != null && !value.isBlank()) {
            credentialStore.store(TENANT, credentialRef, Map.of("api-key", value));
            LOG.infof("  %s: active (%s detected)", vendorName, envVar);
        } else {
            LOG.infof("  %s: inactive — set %s", vendorName, envVar);
        }
    }
}
