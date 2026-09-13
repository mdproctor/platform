package io.casehub.platform.llm.config;

import io.casehub.platform.api.credentials.LlmCredentialStore;
import io.casehub.platform.api.identity.CurrentPrincipal;
import io.casehub.platform.api.identity.TenancyConstants;
import io.casehub.platform.api.model.MutableModelRegistry;
import io.casehub.platform.api.path.Path;
import io.casehub.platform.api.preferences.PreferenceStore;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Any;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@ApplicationScoped
public class LlmConfigService implements LlmConfigApi {

    private static final Logger LOG = Logger.getLogger(LlmConfigService.class);
    private static final String PROVIDER_NAMESPACE = "llm";
    private static final String INDEX_NAMESPACE = "llm";

    private final CurrentPrincipal principal;
    private final ConfiguredModelSourceManager sourceManager;
    private final LlmCredentialStore credentialStore;
    private final PreferenceStore preferenceStore;
    private final Map<String, VendorClient> clientsByVendor;
    private final List<CloudModelSource>    cloudSources;
    private final OllamaModelSource ollamaSource;
    private final ConcurrentHashMap<String, PullProgress> pullOperations = new ConcurrentHashMap<>();


    @Inject
    LlmConfigService(CurrentPrincipal principal,
                      MutableModelRegistry registry,
                      LlmCredentialStore credentialStore,
                      PreferenceStore preferenceStore,
                      @Any Instance<VendorClient> vendorClients,
                      @Any Instance<CloudModelSource> cloudModelSources,
                      OllamaModelSource ollamaSource) {
        this.principal = principal;
        this.credentialStore = credentialStore;
        this.preferenceStore = preferenceStore;
        this.sourceManager = new ConfiguredModelSourceManager(registry, credentialStore);
        this.clientsByVendor = new HashMap<>();
        for (VendorClient client : vendorClients) {
            clientsByVendor.put(client.vendorKey(), client);
        }
        this.cloudSources = new ArrayList<>();
        for (CloudModelSource cs : cloudModelSources) {
            this.cloudSources.add(cs);
        }
        this.ollamaSource = ollamaSource;
    }

    LlmConfigService(CurrentPrincipal principal,
                      ConfiguredModelSourceManager sourceManager,
                      LlmCredentialStore credentialStore,
                      PreferenceStore preferenceStore,
                      List<VendorClient> vendorClients,
                      List<CloudModelSource> cloudModelSources,
                      OllamaModelSource ollamaSource) {
        this.principal = principal;
        this.sourceManager = sourceManager;
        this.credentialStore = credentialStore;
        this.preferenceStore = preferenceStore;
        this.clientsByVendor = new HashMap<>();
        for (VendorClient client : vendorClients) {
            clientsByVendor.put(client.vendorKey(), client);
        }
        this.cloudSources = cloudModelSources != null ? new ArrayList<>(cloudModelSources) : new ArrayList<>();
        this.ollamaSource = ollamaSource;
    }

    @Override
    public List<VendorInfo> vendors() {
        return clientsByVendor.values().stream()
            .map(c -> new VendorInfo(c.vendorKey(), c.backendKey(), c.displayName(),
                c.authMethod(), c.requiredFields()))
            .toList();
    }

    @Override
    public List<ProviderConfig> configured() {
        String tenancyId = principal.tenancyId();
        var results = new ArrayList<ProviderConfig>();
        for (var client : clientsByVendor.values()) {
            if (sourceManager.isConfigured(tenancyId, client.vendorKey())) {
                var source = sourceManager.getSource(tenancyId, client.vendorKey());
                if (source != null) {
                    results.add(new ProviderConfig(
                        client.vendorKey() + "-" + tenancyId,
                        client.vendorKey(), client.backendKey(),
                        client.displayName(), 0, Instant.now()));
                }
            }
        }
        return results;
    }

    @Override
    public ValidationResult validate(ValidateRequest request) {
        VendorClient client = clientsByVendor.get(request.vendorKey());
        if (client == null) {
            return ValidationResult.failure("Unknown vendor: " + request.vendorKey());
        }
        return client.listModels(request.credentials());
    }

    @Override
    public ConfigureResult configure(ConfigureRequest request) {
        String tenancyId = principal.tenancyId();
        VendorClient client = clientsByVendor.get(request.vendorKey());
        if (client == null) {
            throw new IllegalArgumentException("Unknown vendor: " + request.vendorKey());
        }

        var validation = client.listModels(request.credentials());
        if (!validation.valid()) {
            throw new IllegalArgumentException("Credential validation failed: " + validation.errorMessage());
        }

        String credentialRef = request.vendorKey() + "-" + tenancyId;
        credentialStore.store(tenancyId, credentialRef, request.credentials());

        sourceManager.configure(tenancyId, request.vendorKey(), credentialRef,
            client, validation.models());

        persistProviderConfig(tenancyId, request.vendorKey(), credentialRef, request.displayName());
        persistProviderIndex(tenancyId, request.vendorKey());

        List<String> modelIds = validation.models().stream()
            .map(d -> request.vendorKey() + ":" + tenancyId + ":" + d.apiModelId())
            .toList();

        String providerId = request.vendorKey() + "-" + tenancyId;
        LOG.infof("Configured LLM provider: %s (%d models)", providerId, modelIds.size());
        return new ConfigureResult(providerId, modelIds.size(), modelIds);
    }

    @Override
    public void unconfigure(String providerId) {
        String tenancyId = principal.tenancyId();
        String suffix = "-" + tenancyId;
        String vendorKey = providerId.endsWith(suffix)
            ? providerId.substring(0, providerId.length() - suffix.length())
            : providerId;

        String credentialRef = vendorKey + "-" + tenancyId;
        sourceManager.unconfigure(tenancyId, vendorKey);
        credentialStore.delete(tenancyId, credentialRef);
        removeProviderConfig(tenancyId, vendorKey);
        removeProviderIndex(tenancyId, vendorKey);
        LOG.infof("Unconfigured LLM provider: %s", providerId);
    }

    @Override
    public List<CloudSourceStatus> cloudSourceStatus() {
        return cloudSources.stream().map(CloudModelSource::status).toList();
    }

    @Override
    public OllamaSourceStatus ollamaStatus() {
        return ollamaSource != null ? ollamaSource.status() : OllamaSourceStatus.offline("Ollama not configured");
    }

    @Override
    public PullOperation pullModel(PullRequest request) {
        String operationId = UUID.randomUUID().toString();
        var progress = new PullProgress(operationId, request.modelRef(),
            PullOperation.PullStatus.PULLING, 0, 0, null, null);
        pullOperations.put(operationId, progress);
        return new PullOperation(operationId, request.modelRef(), PullOperation.PullStatus.PULLING);
    }

    @Override
    public PullProgress pullStatus(String operationId) {
        return pullOperations.get(operationId);
    }

    @Override
    public void cancelPull(String operationId) {
        var current = pullOperations.get(operationId);
        if (current != null) {
            pullOperations.put(operationId, new PullProgress(
                operationId, current.modelRef(), PullOperation.PullStatus.CANCELLED,
                current.totalBytes(), current.completedBytes(), current.digest(), null));
        }
    }

    @Override
    public void deleteModel(String modelName) {
        LOG.infof("Delete model requested: %s", modelName);
    }

    private void persistProviderConfig(String tenancyId, String vendorKey,
                                       String credentialRef, String displayName) {
        preferenceStore.set(tenancyId, Path.root(), PROVIDER_NAMESPACE,
            "provider." + vendorKey, "enabled", "true");
        preferenceStore.set(tenancyId, Path.root(), PROVIDER_NAMESPACE,
            "provider." + vendorKey, "credential-ref", credentialRef);
        preferenceStore.set(tenancyId, Path.root(), PROVIDER_NAMESPACE,
            "provider." + vendorKey, "configured-at", Instant.now().toString());
        if (displayName != null && !displayName.isBlank()) {
            preferenceStore.set(tenancyId, Path.root(), PROVIDER_NAMESPACE,
                "provider." + vendorKey, "display-name", displayName);
        }
    }

    private void removeProviderConfig(String tenancyId, String vendorKey) {
        preferenceStore.delete(tenancyId, Path.root(), PROVIDER_NAMESPACE,
            "provider." + vendorKey, "enabled");
        preferenceStore.delete(tenancyId, Path.root(), PROVIDER_NAMESPACE,
            "provider." + vendorKey, "credential-ref");
        preferenceStore.delete(tenancyId, Path.root(), PROVIDER_NAMESPACE,
            "provider." + vendorKey, "configured-at");
        preferenceStore.delete(tenancyId, Path.root(), PROVIDER_NAMESPACE,
            "provider." + vendorKey, "display-name");
    }

    private void persistProviderIndex(String tenancyId, String vendorKey) {
        preferenceStore.set(TenancyConstants.PLATFORM_TENANT_ID, Path.root(),
            INDEX_NAMESPACE, "provider-index", tenancyId + ":" + vendorKey, "true");
    }

    private void removeProviderIndex(String tenancyId, String vendorKey) {
        preferenceStore.delete(TenancyConstants.PLATFORM_TENANT_ID, Path.root(),
            INDEX_NAMESPACE, "provider-index", tenancyId + ":" + vendorKey);
    }
}
