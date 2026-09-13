package io.casehub.platform.llm.config;

import io.casehub.platform.api.model.CostTier;
import io.casehub.platform.api.model.ModelDescriptor;
import io.casehub.platform.api.model.ModelLocality;
import io.casehub.platform.api.model.ModelTier;
import io.casehub.platform.model.InMemoryModelRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import java.util.Set;
import static org.assertj.core.api.Assertions.assertThat;

class ConfiguredModelSourceManagerTest {

    private InMemoryModelRegistry registry;
    private InMemoryLlmCredentialStore credentialStore;
    private ConfiguredModelSourceManager manager;

    @BeforeEach
    void setUp() {
        registry = new InMemoryModelRegistry();
        credentialStore = new InMemoryLlmCredentialStore();
        manager = new ConfiguredModelSourceManager(registry, credentialStore);
    }

    @Test
    void configureRegistersModelsInRegistry() {
        var models = List.of(testDescriptor("claude-sonnet-5"));
        manager.configure("tenant-1", "anthropic", "anthropic-tenant-1", stubClient(), models);

        assertThat(registry.resolveById("anthropic:tenant-1:claude-sonnet-5")).isPresent();
        var resolved = registry.resolveById("anthropic:tenant-1:claude-sonnet-5").get();
        assertThat(resolved.apiModelId()).isEqualTo("claude-sonnet-5");
        assertThat(resolved.backendKey()).isEqualTo("claude");
    }

    @Test
    void unconfigureRemovesModelsFromRegistry() {
        manager.configure("tenant-1", "anthropic", "ref-1", stubClient(), List.of(testDescriptor("claude-sonnet-5")));
        manager.unconfigure("tenant-1", "anthropic");
        assertThat(registry.resolveById("anthropic:tenant-1:claude-sonnet-5")).isEmpty();
    }

    @Test
    void tenantIsolationInRegistry() {
        manager.configure("t1", "anthropic", "ref-t1", stubClient(), List.of(testDescriptor("claude-sonnet-5")));
        manager.configure("t2", "anthropic", "ref-t2", stubClient(), List.of(testDescriptor("claude-sonnet-5")));

        assertThat(registry.resolveById("anthropic:t1:claude-sonnet-5")).isPresent();
        assertThat(registry.resolveById("anthropic:t2:claude-sonnet-5")).isPresent();
        assertThat(registry.resolveById("anthropic:t1:claude-sonnet-5").get().id())
            .isNotEqualTo(registry.resolveById("anthropic:t2:claude-sonnet-5").get().id());
    }

    @Test
    void isConfiguredReflectsState() {
        assertThat(manager.isConfigured("t1", "anthropic")).isFalse();
        manager.configure("t1", "anthropic", "ref-1", stubClient(), List.of(testDescriptor("claude-sonnet-5")));
        assertThat(manager.isConfigured("t1", "anthropic")).isTrue();
        manager.unconfigure("t1", "anthropic");
        assertThat(manager.isConfigured("t1", "anthropic")).isFalse();
    }

    @Test
    void unconfigureNonexistentIsNoOp() {
        manager.unconfigure("t1", "nonexistent");
    }

    @Test
    void multipleVendorsSameTenantsAreIndependent() {
        manager.configure("t1", "anthropic", "ref-a", stubClient(), List.of(testDescriptor("claude-sonnet-5")));
        manager.configure("t1", "openai", "ref-o", stubClient(), List.of(testDescriptor("gpt-4.1")));

        assertThat(registry.resolveById("anthropic:t1:claude-sonnet-5")).isPresent();
        assertThat(registry.resolveById("openai:t1:gpt-4.1")).isPresent();

        manager.unconfigure("t1", "anthropic");
        assertThat(registry.resolveById("anthropic:t1:claude-sonnet-5")).isEmpty();
        assertThat(registry.resolveById("openai:t1:gpt-4.1")).isPresent();
    }

    private ModelDescriptor testDescriptor(String id) {
        return new ModelDescriptor(id, id, "claude", null, "anthropic", "claude",
            "Test Model", ModelTier.STANDARD, Set.of("text"), 200000, 16384,
            ModelLocality.CLOUD, CostTier.HIGH, "api-key", Map.of());
    }

    private VendorClient stubClient() {
        return new VendorClient() {
            @Override public String vendorKey() { return "anthropic"; }
            @Override public String backendKey() { return "claude"; }
            @Override public String displayName() { return "Anthropic"; }
            @Override public String authMethod() { return "api-key"; }
            @Override public List<String> requiredFields() { return List.of("api-key"); }
            @Override public ValidationResult listModels(Map<String, String> credentials) {
                return ValidationResult.success(List.of());
            }
        };
    }
}
