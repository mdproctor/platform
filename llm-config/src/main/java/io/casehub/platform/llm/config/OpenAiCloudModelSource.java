package io.casehub.platform.llm.config;

import io.casehub.platform.api.credentials.LlmCredentialStore;
import io.casehub.platform.api.identity.TenancyConstants;
import io.casehub.platform.api.model.ModelDescriptor;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;
import java.util.List;
import java.util.Map;

@ApplicationScoped
public class OpenAiCloudModelSource implements CloudModelSource {

    private static final Logger LOG = Logger.getLogger(OpenAiCloudModelSource.class);
    private static final String SOURCE_ID = "cloud:openai";
    private static final String CREDENTIAL_REF = "cloud-openai";

    private final VendorClient client;
    private final LlmCredentialStore credentialStore;
    private volatile List<ModelDescriptor> lastKnownModels;
    private volatile CloudSourceStatus lastStatus;

    @Inject
    OpenAiCloudModelSource(OpenAiClient client, LlmCredentialStore credentialStore) {
        this((VendorClient) client, credentialStore);
    }

    OpenAiCloudModelSource(VendorClient client, LlmCredentialStore credentialStore) {
        this.client = client;
        this.credentialStore = credentialStore;
        this.lastStatus = CloudSourceStatus.inactive(SOURCE_ID, "OpenAI",
            "set OPENAI_API_KEY");
    }

    @Override
    public String sourceId() { return SOURCE_ID; }

    @Override
    public int priority() { return 5; }

    @Override
    public List<ModelDescriptor> refresh() {
        Map<String, String> creds = credentialStore.resolve(
            TenancyConstants.PLATFORM_TENANT_ID, CREDENTIAL_REF);
        if (creds.isEmpty()) {
            return lastKnownModels != null ? lastKnownModels : List.of();
        }
        var result = client.listModels(creds);
        if (result.valid()) {
            lastKnownModels = result.models();
            lastStatus = CloudSourceStatus.active(SOURCE_ID, "OpenAI",
                lastKnownModels.size());
            return lastKnownModels;
        }
        LOG.warnf("Cloud source %s refresh failed: %s", SOURCE_ID, result.errorMessage());
        lastStatus = CloudSourceStatus.error(SOURCE_ID, "OpenAI", result.errorMessage());
        return lastKnownModels != null ? lastKnownModels : List.of();
    }

    @Override
    public CloudSourceStatus status() {
        return lastStatus;
    }
}
