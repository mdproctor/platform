package io.casehub.platform.llm.config;

import io.casehub.platform.api.credentials.LlmCredentialStore;
import io.casehub.platform.api.identity.CurrentPrincipal;
import io.casehub.platform.api.model.CostTier;
import io.casehub.platform.api.model.ModelDescriptor;
import io.casehub.platform.api.model.ModelLocality;
import io.casehub.platform.api.model.ModelTier;
import io.casehub.platform.api.path.Path;
import io.casehub.platform.api.preferences.PreferenceQuery;
import io.casehub.platform.api.preferences.PreferenceRecord;
import io.casehub.platform.api.preferences.PreferenceStore;
import io.casehub.platform.model.InMemoryModelRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LlmConfigServiceTest {

    private InMemoryModelRegistry registry;
    private InMemoryLlmCredentialStore credentialStore;
    private StubPreferenceStore preferenceStore;
    private StubPrincipal principal;
    private LlmConfigService service;

    @BeforeEach
    void setUp() {
        registry = new InMemoryModelRegistry();
        credentialStore = new InMemoryLlmCredentialStore();
        preferenceStore = new StubPreferenceStore();
        principal = new StubPrincipal("tenant-1");

        var manager = new ConfiguredModelSourceManager(registry, credentialStore);
        var stubClient = new StubVendorClient("test-vendor", "test-backend");

        service = new LlmConfigService(principal, manager, credentialStore,
            preferenceStore, List.of(stubClient), List.of(), null);
    }

    @Test
    void vendorsDerivedFromDiscoveredClients() {
        List<VendorInfo> vendors = service.vendors();
        assertThat(vendors).hasSize(1);
        assertThat(vendors.get(0).vendorKey()).isEqualTo("test-vendor");
        assertThat(vendors.get(0).backendKey()).isEqualTo("test-backend");
        assertThat(vendors.get(0).displayName()).isEqualTo("Test Vendor");
    }

    @Test
    void configureValidatesAndRegistersModels() {
        var result = service.configure(new ConfigureRequest("test-vendor",
            Map.of("api-key", "valid-key"), null));

        assertThat(result.modelsRegistered()).isEqualTo(1);
        assertThat(result.providerId()).isEqualTo("test-vendor-tenant-1");
        assertThat(result.modelIds()).containsExactly("test-vendor:tenant-1:test-model");
    }

    @Test
    void configuredModelsQueryableInRegistry() {
        service.configure(new ConfigureRequest("test-vendor",
            Map.of("api-key", "valid-key"), null));

        assertThat(registry.resolveById("test-vendor:tenant-1:test-model")).isPresent();
        var model = registry.resolveById("test-vendor:tenant-1:test-model").get();
        assertThat(model.apiModelId()).isEqualTo("test-model");
        assertThat(model.backendKey()).isEqualTo("test-backend");
    }

    @Test
    void configuredReturnsConfiguredProviders() {
        assertThat(service.configured()).isEmpty();

        service.configure(new ConfigureRequest("test-vendor",
            Map.of("api-key", "valid-key"), null));

        List<ProviderConfig> configs = service.configured();
        assertThat(configs).hasSize(1);
        assertThat(configs.get(0).vendorKey()).isEqualTo("test-vendor");
        assertThat(configs.get(0).backendKey()).isEqualTo("test-backend");
    }

    @Test
    void unconfigureRemovesModelsAndCredentials() {
        var result = service.configure(new ConfigureRequest("test-vendor",
            Map.of("api-key", "valid-key"), null));

        service.unconfigure(result.providerId());

        assertThat(service.configured()).isEmpty();
        assertThat(registry.resolveById("test-vendor:tenant-1:test-model")).isEmpty();
        assertThat(credentialStore.resolve("tenant-1", "test-vendor-tenant-1")).isEmpty();
    }

    @Test
    void validateDryRun() {
        var result = service.validate(new ValidateRequest("test-vendor",
            Map.of("api-key", "valid-key")));
        assertThat(result.valid()).isTrue();
        assertThat(result.models()).hasSize(1);
    }

    @Test
    void validateUnknownVendorReturnsFailure() {
        var result = service.validate(new ValidateRequest("unknown",
            Map.of("api-key", "key")));
        assertThat(result.valid()).isFalse();
        assertThat(result.errorMessage()).contains("Unknown vendor");
    }

    @Test
    void configureUnknownVendorThrows() {
        assertThatThrownBy(() -> service.configure(
            new ConfigureRequest("unknown", Map.of("api-key", "key"), null)))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Unknown vendor");
    }

    @Test
    void configureInvalidCredentialsThrows() {
        assertThatThrownBy(() -> service.configure(
            new ConfigureRequest("test-vendor", Map.of("api-key", "invalid"), null)))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("validation failed");
    }

    @Test
    void fullLifecycle() {
        var result = service.configure(new ConfigureRequest("test-vendor",
            Map.of("api-key", "valid-key"), "My Provider"));
        assertThat(result.modelsRegistered()).isEqualTo(1);

        assertThat(service.configured()).hasSize(1);

        for (String modelId : result.modelIds()) {
            assertThat(registry.resolveById(modelId)).isPresent();
        }

        service.unconfigure(result.providerId());
        assertThat(service.configured()).isEmpty();
        for (String modelId : result.modelIds()) {
            assertThat(registry.resolveById(modelId)).isEmpty();
        }
    }

    @Test
    void tenantIsolation() {
        service.configure(new ConfigureRequest("test-vendor",
            Map.of("api-key", "key-1"), null));

        principal.setTenancyId("tenant-2");
        assertThat(service.configured()).isEmpty();
        assertThat(registry.resolveById("test-vendor:tenant-2:test-model")).isEmpty();
        assertThat(registry.resolveById("test-vendor:tenant-1:test-model")).isPresent();
    }

    static class StubVendorClient implements VendorClient {
        private final String vendorKey;
        private final String backendKey;

        StubVendorClient(String vendorKey, String backendKey) {
            this.vendorKey = vendorKey;
            this.backendKey = backendKey;
        }

        @Override public String vendorKey() { return vendorKey; }
        @Override public String backendKey() { return backendKey; }
        @Override public String displayName() { return "Test Vendor"; }
        @Override public String authMethod() { return "api-key"; }
        @Override public List<String> requiredFields() { return List.of("api-key"); }

        @Override
        public ValidationResult listModels(Map<String, String> credentials) {
            if ("invalid".equals(credentials.get("api-key"))) {
                return ValidationResult.failure("Invalid key");
            }
            var model = new ModelDescriptor("test-model", "test-model", backendKey,
                null, vendorKey, vendorKey, "Test Model",
                ModelTier.STANDARD, Set.of("text"), 200000, 16384,
                ModelLocality.CLOUD, CostTier.MEDIUM, "api-key", Map.of());
            return ValidationResult.success(List.of(model));
        }
    }

    static class StubPrincipal implements CurrentPrincipal {
        private String tenancyId;

        StubPrincipal(String tenancyId) { this.tenancyId = tenancyId; }

        void setTenancyId(String tenancyId) { this.tenancyId = tenancyId; }

        @Override public String tenancyId() { return tenancyId; }
        @Override public String actorId() { return "admin-1"; }
        @Override public Set<String> groups() { return Set.of(); }
        @Override public Set<String> roles() { return Set.of("platform-admin"); }
        @Override public boolean isCrossTenantAdmin() { return false; }
    }

    static class StubPreferenceStore implements PreferenceStore {
        private final ConcurrentHashMap<String, String> store = new ConcurrentHashMap<>();

        @Override
        public void set(String tenancyId, Path scope, String namespace, String name,
                        String subKey, String value) {
            store.put(tenancyId + ":" + namespace + ":" + name + ":" + subKey, value);
        }

        @Override
        public void delete(String tenancyId, Path scope, String namespace, String name, String subKey) {
            store.remove(tenancyId + ":" + namespace + ":" + name + ":" + subKey);
        }

        @Override
        public List<PreferenceRecord> list(PreferenceQuery query) {
            return List.of();
        }

        @Override
        public void deleteAll(String tenancyId, Path scope, String namespace) {
            store.entrySet().removeIf(e -> e.getKey().startsWith(tenancyId + ":" + namespace + ":"));
        }
    }
}
