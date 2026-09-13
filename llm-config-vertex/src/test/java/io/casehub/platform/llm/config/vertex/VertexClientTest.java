package io.casehub.platform.llm.config.vertex;

import io.casehub.platform.api.model.ModelCapabilities;
import io.casehub.platform.api.model.ModelDescriptor;
import io.casehub.platform.api.model.ModelLocality;
import io.casehub.platform.api.model.ModelTier;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class VertexClientTest {

    private static final ModelDescriptor SEED_SONNET = new ModelDescriptor(
        "claude-sonnet-5", "claude-sonnet-5", "claude", null, "anthropic", "claude",
        "Claude Sonnet 5", ModelTier.FLAGSHIP, Set.of("text", "vision"),
        200000, 8192, ModelLocality.CLOUD, null, "api-key", Map.of());

    @Test
    void vendorKey() {
        var client = new VertexClient(q -> List.of());
        assertThat(client.vendorKey()).isEqualTo("vertex");
    }

    @Test
    void backendKey() {
        var client = new VertexClient(q -> List.of());
        assertThat(client.backendKey()).isEqualTo("claude");
    }

    @Test
    void authMethod() {
        var client = new VertexClient(q -> List.of());
        assertThat(client.authMethod()).isEqualTo("gcp-adc");
    }

    @Test
    void requiredFields() {
        var client = new VertexClient(q -> List.of());
        assertThat(client.requiredFields()).containsExactly("project-id", "region");
    }

    @Test
    void listModels_missingProjectId_fails() {
        var client = new VertexClient(q -> List.of());
        var result = client.listModels(Map.of("region", "us-central1"));
        assertThat(result.valid()).isFalse();
        assertThat(result.errorMessage()).contains("project-id");
    }

    @Test
    void parseModelsResponse_withSeedMatch_enrichesMetadata() {
        var client = new VertexClient(q -> List.of(SEED_SONNET));
        String json = """
            {"models": [{"name": "publishers/anthropic/models/claude-sonnet-5", "displayName": "Claude 3.5 Sonnet"}]}
            """;

        var models = client.parseModelsResponse(json);

        assertThat(models).hasSize(1);
        var model = models.get(0);
        assertThat(model.id()).isEqualTo("claude-sonnet-5");
        assertThat(model.tier()).isEqualTo(ModelTier.FLAGSHIP);
        assertThat(model.capabilities()).contains("vision");
        assertThat(model.contextWindow()).isEqualTo(200000);
    }

    @Test
    void parseModelsResponse_withoutSeedMatch_usesDefaults() {
        var client = new VertexClient(q -> List.of());
        String json = """
            {"models": [{"name": "publishers/anthropic/models/claude-new-model", "displayName": "Claude New"}]}
            """;

        var models = client.parseModelsResponse(json);

        assertThat(models).hasSize(1);
        var model = models.get(0);
        assertThat(model.id()).isEqualTo("claude-new-model");
        assertThat(model.tier()).isEqualTo(ModelTier.STANDARD);
        assertThat(model.contextWindow()).isEqualTo(0);
    }

    @Test
    void parseModelsResponse_emptyArray_returnsEmpty() {
        var client = new VertexClient(q -> List.of());
        assertThat(client.parseModelsResponse("{\"models\": []}")).isEmpty();
    }

    @Test
    void parseModelsResponse_invalidJson_returnsEmpty() {
        var client = new VertexClient(q -> List.of());
        assertThat(client.parseModelsResponse("not json")).isEmpty();
    }
}
