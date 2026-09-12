package io.casehub.platform.llm.config;

import io.casehub.platform.api.credentials.LlmCredentialStore;
import io.casehub.platform.api.identity.TenancyConstants;
import io.casehub.platform.api.model.ModelDescriptor;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Any;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;
import java.util.List;
import java.util.Map;

@ApplicationScoped
public class VertexCloudModelSource implements CloudModelSource {

    private static final Logger LOG = Logger.getLogger(VertexCloudModelSource.class);
    private static final String SOURCE_ID = "cloud:vertex";
    private static final String CREDENTIAL_REF = "cloud-vertex";
    private static final String VENDOR_KEY = "vertex";

    private final VendorClient client;
    private final LlmCredentialStore credentialStore;
    private volatile List<ModelDescriptor> lastKnownModels;
    private volatile CloudSourceStatus lastStatus;

    @Inject
    VertexCloudModelSource(@Any Instance<VendorClient> vendorClients,
                            LlmCredentialStore credentialStore) {
        VendorClient resolved = null;
        for (VendorClient vc : vendorClients) {
            if (VENDOR_KEY.equals(vc.vendorKey())) {
                resolved = vc;
                break;
            }
        }
        this.client = resolved;
        this.credentialStore = credentialStore;
        this.lastStatus = CloudSourceStatus.inactive(SOURCE_ID, "Vertex AI (Anthropic)",
            "set GOOGLE_APPLICATION_CREDENTIALS + GOOGLE_CLOUD_PROJECT");
    }

    VertexCloudModelSource(VendorClient client, LlmCredentialStore credentialStore) {
        this.client = client;
        this.credentialStore = credentialStore;
        this.lastStatus = CloudSourceStatus.inactive(SOURCE_ID, "Vertex AI (Anthropic)",
            "set GOOGLE_APPLICATION_CREDENTIALS + GOOGLE_CLOUD_PROJECT");
    }

    @Override
    public String sourceId() { return SOURCE_ID; }

    @Override
    public int priority() { return 5; }

    @Override
    public List<ModelDescriptor> refresh() {
        if (client == null) {
            return lastKnownModels != null ? lastKnownModels : List.of();
        }
        Map<String, String> creds = credentialStore.resolve(
            TenancyConstants.PLATFORM_TENANT_ID, CREDENTIAL_REF);
        if (creds.isEmpty()) {
            return lastKnownModels != null ? lastKnownModels : List.of();
        }
        var result = client.listModels(creds);
        if (result.valid()) {
            lastKnownModels = result.models();
            lastStatus = CloudSourceStatus.active(SOURCE_ID, "Vertex AI (Anthropic)",
                lastKnownModels.size());
            return lastKnownModels;
        }
        LOG.warnf("Cloud source %s refresh failed: %s", SOURCE_ID, result.errorMessage());
        lastStatus = CloudSourceStatus.error(SOURCE_ID, "Vertex AI (Anthropic)", result.errorMessage());
        return lastKnownModels != null ? lastKnownModels : List.of();
    }

    @Override
    public CloudSourceStatus status() {
        return lastStatus;
    }
}
