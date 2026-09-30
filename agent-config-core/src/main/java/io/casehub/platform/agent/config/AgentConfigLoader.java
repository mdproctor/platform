package io.casehub.platform.agent.config;

import io.casehub.platform.api.credentials.CredentialResolver;
import io.casehub.platform.api.credentials.LlmCredentialStore;
import io.casehub.platform.api.identity.DIDResolver;
import io.casehub.platform.api.model.MutableModelRegistry;
import org.jboss.logging.Logger;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

public class AgentConfigLoader {

    private static final Logger LOG = Logger.getLogger(AgentConfigLoader.class);

    private final LlmCredentialStore credentialStore;
    private final MutableModelRegistry modelRegistry;
    private final CredentialResolver credentialResolver;
    private final Map<String, List<String>> vendorRequirements;
    private final LocalModelReconciler reconciler;
    private final String profile;
    private final Path projectDir;
    private final DIDResolver didResolver;
    private final boolean allowInsecure;

    public AgentConfigLoader(LlmCredentialStore credentialStore,
                             MutableModelRegistry modelRegistry,
                             CredentialResolver credentialResolver,
                             Map<String, List<String>> vendorRequirements,
                             LocalModelReconciler reconciler,
                             String profile,
                             Path projectDir,
                             DIDResolver didResolver,
                             boolean allowInsecure) {
        this.credentialStore = credentialStore;
        this.modelRegistry = modelRegistry;
        this.credentialResolver = credentialResolver;
        this.vendorRequirements = vendorRequirements;
        this.reconciler = reconciler;
        this.profile = profile;
        this.projectDir = projectDir;
        this.didResolver = didResolver;
        this.allowInsecure = allowInsecure;
    }

    public ManifestResult load() {
        var resolver = new ManifestCredentialResolver(credentialResolver);
        var securityConfig = new ManifestSecurityConfig(allowInsecure);
        var loader = new ManifestLoader(resolver, didResolver, securityConfig);
        var manifest = loader.load(projectDir, profile);

        var processor = new ManifestProcessor(
                credentialStore, modelRegistry, resolver, vendorRequirements, reconciler);
        var result = processor.process(manifest);

        LOG.infof("Agent config manifest processed: %d aliases, default backend: %s",
                result.aliases().size(),
                result.defaultBackendKey() != null ? result.defaultBackendKey() : "(not set)");

        return result;
    }
}
