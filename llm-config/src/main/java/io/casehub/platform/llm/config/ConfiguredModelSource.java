package io.casehub.platform.llm.config;

import io.casehub.platform.api.credentials.LlmCredentialStore;
import io.casehub.platform.api.model.ModelDescriptor;
import io.casehub.platform.api.model.ModelSource;
import org.jboss.logging.Logger;
import java.util.List;
import java.util.Map;

class ConfiguredModelSource implements ModelSource {

    private static final Logger LOG = Logger.getLogger(ConfiguredModelSource.class);

    private final String sourceId;
    private final String tenancyId;
    private final String vendorKey;
    private final String credentialRef;
    private final VendorClient client;
    private final LlmCredentialStore credentialStore;
    private volatile List<ModelDescriptor> lastKnownModels;

    ConfiguredModelSource(String tenancyId, String vendorKey, String credentialRef,
                          VendorClient client, LlmCredentialStore credentialStore) {
        this.sourceId = "configured:" + vendorKey + ":" + tenancyId;
        this.tenancyId = tenancyId;
        this.vendorKey = vendorKey;
        this.credentialRef = credentialRef;
        this.client = client;
        this.credentialStore = credentialStore;
    }

    @Override
    public String sourceId() { return sourceId; }

    @Override
    public int priority() { return 10; }

    @Override
    public List<ModelDescriptor> refresh() {
        Map<String, String> creds = credentialStore.resolve(tenancyId, credentialRef);
        if (creds.isEmpty()) {
            LOG.warnf("Credentials missing for %s — returning last known models", sourceId);
            return lastKnownModels != null ? lastKnownModels : List.of();
        }
        var result = client.listModels(creds);
        if (result.valid()) {
            lastKnownModels = toTenantScoped(result.models());
            return lastKnownModels;
        }
        return lastKnownModels != null ? lastKnownModels : List.of();
    }

    List<ModelDescriptor> toTenantScoped(List<ModelDescriptor> vendorModels) {
        return vendorModels.stream()
            .map(d -> new ModelDescriptor(
                vendorKey + ":" + tenancyId + ":" + d.apiModelId(),
                d.apiModelId(),
                d.backendKey(), null, d.vendor(), d.family(), d.displayName(),
                d.tier(), d.capabilities(), d.contextWindow(), d.maxOutput(),
                d.locality(), d.costTier(), d.authMethod(), d.properties()))
            .toList();
    }

    String tenancyId() { return tenancyId; }
    String vendorKey() { return vendorKey; }
    String credentialRef() { return credentialRef; }
}
