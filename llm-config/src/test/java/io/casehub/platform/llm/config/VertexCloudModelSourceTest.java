package io.casehub.platform.llm.config;

import io.casehub.platform.api.credentials.LlmCredentialStore;
import io.casehub.platform.api.model.ModelDescriptor;
import io.casehub.platform.api.model.ModelLocality;
import io.casehub.platform.api.model.ModelTier;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class VertexCloudModelSourceTest {

    private static final ModelDescriptor SAMPLE = new ModelDescriptor(
        "claude-sonnet-5", "claude-sonnet-5", "claude", null, "anthropic", "claude",
        "Claude Sonnet 5", ModelTier.FLAGSHIP, Set.of("text"),
        200000, 8192, ModelLocality.CLOUD, null, "gcp-adc", Map.of());

    @Test
    void refresh_withClientAndCredentials_returnsModels() {
        var source = new VertexCloudModelSource(
            stubClient(ValidationResult.success(List.of(SAMPLE))),
            storeWith(Map.of("project-id", "my-project", "region", "us-central1")));

        assertThat(source.refresh()).hasSize(1);
    }

    @Test
    void refresh_noClient_returnsEmpty() {
        var source = new VertexCloudModelSource(
            (VendorClient) null, storeWith(Map.of("project-id", "p")));

        assertThat(source.refresh()).isEmpty();
    }

    @Test
    void refresh_noCredentials_returnsEmpty() {
        var source = new VertexCloudModelSource(
            stubClient(ValidationResult.success(List.of(SAMPLE))),
            storeWith(Map.of()));

        assertThat(source.refresh()).isEmpty();
    }

    @Test
    void sourceId() {
        var source = new VertexCloudModelSource(
            stubClient(ValidationResult.success(List.of())), storeWith(Map.of()));
        assertThat(source.sourceId()).isEqualTo("cloud:vertex");
    }

    @Test
    void priority() {
        var source = new VertexCloudModelSource(
            stubClient(ValidationResult.success(List.of())), storeWith(Map.of()));
        assertThat(source.priority()).isEqualTo(5);
    }

    @Test
    void status_inactive_showsGuidance() {
        var source = new VertexCloudModelSource(
            stubClient(ValidationResult.success(List.of())), storeWith(Map.of()));
        assertThat(source.status().state()).isEqualTo(CloudSourceStatus.State.INACTIVE);
        assertThat(source.status().message()).contains("GOOGLE");
    }

    private VendorClient stubClient(ValidationResult result) {
        return new VendorClient() {
            @Override public String vendorKey() { return "vertex"; }
            @Override public String backendKey() { return "claude"; }
            @Override public String displayName() { return "Vertex AI"; }
            @Override public String authMethod() { return "gcp-adc"; }
            @Override public List<String> requiredFields() { return List.of("project-id", "region"); }
            @Override public ValidationResult listModels(Map<String, String> c) { return result; }
        };
    }

    private LlmCredentialStore storeWith(Map<String, String> creds) {
        return new LlmCredentialStore() {
            @Override public void store(String t, String r, Map<String, String> c) {}
            @Override public Map<String, String> resolve(String t, String r) { return creds; }
            @Override public void delete(String t, String r) {}
            @Override public List<String> listRefs(String t) { return List.of(); }
        };
    }
}
