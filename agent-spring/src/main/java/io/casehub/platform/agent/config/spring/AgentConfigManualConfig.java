package io.casehub.platform.agent.config.spring;

import io.casehub.platform.agent.config.AgentConfigLoader;
import io.casehub.platform.agent.config.ManifestResult;
import io.casehub.platform.api.credentials.CredentialResolver;
import io.casehub.platform.api.credentials.LlmCredentialStore;
import io.casehub.platform.api.identity.DIDResolver;
import io.casehub.platform.api.model.MutableModelRegistry;
import io.casehub.platform.llm.config.LlmConfigApi;
import io.casehub.platform.llm.config.PullRequest;
import io.casehub.platform.llm.config.VendorClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.context.annotation.Bean;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@AutoConfiguration
@ConditionalOnClass(ManifestResult.class)
public class AgentConfigManualConfig {

    private static final Logger LOG = LoggerFactory.getLogger(AgentConfigManualConfig.class);

    @Bean
    public ManifestResult manifestResult(
            LlmCredentialStore credentialStore,
            MutableModelRegistry modelRegistry,
            CredentialResolver credentialResolver,
            ObjectProvider<VendorClient> vendorClients,
            ObjectProvider<LlmConfigApi> configApi,
            ObjectProvider<DIDResolver> didResolver,
            @Value("${casehub.agent.manifest.allow-insecure:false}") boolean allowInsecure) {

        var vendorRequirements = new HashMap<String, List<String>>();
        vendorClients.forEach(vc -> vendorRequirements.put(vc.vendorKey(), vc.requiredFields()));

        var reconciler = buildReconciler(configApi);
        var profile = resolveProfile();

        var loader = new AgentConfigLoader(
                credentialStore, modelRegistry, credentialResolver,
                Map.copyOf(vendorRequirements), reconciler,
                profile, Path.of(System.getProperty("user.dir")),
                didResolver.getIfAvailable(), allowInsecure);

        var result = loader.load();
        LOG.info("Agent config manifest processed: {} aliases, default backend: {}",
                result.aliases().size(),
                result.defaultBackendKey() != null ? result.defaultBackendKey() : "(not set)");
        return result;
    }

    private io.casehub.platform.agent.config.LocalModelReconciler buildReconciler(
            ObjectProvider<LlmConfigApi> configApi) {
        return modelId -> {
            var api = configApi.getIfAvailable();
            if (api != null) {
                try {
                    api.pullModel(new PullRequest(modelId));
                    LOG.info("Triggered pull for local model: {}", modelId);
                } catch (Exception e) {
                    LOG.warn("Failed to pull local model {}: {}", modelId, e.getMessage());
                }
            } else {
                LOG.warn("LlmConfigApi not available — cannot ensure local model: {}", modelId);
            }
        };
    }

    private String resolveProfile() {
        String profile = System.getenv("CASEHUB_AGENT_PROFILE");
        if (profile == null || profile.isBlank()) {
            profile = System.getenv("SPRING_PROFILES_ACTIVE");
        }
        if (profile != null) {
            LOG.info("Agent config profile: {}", profile);
        }
        return profile;
    }
}
