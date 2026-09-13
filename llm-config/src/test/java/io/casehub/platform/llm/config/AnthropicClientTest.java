package io.casehub.platform.llm.config;

import io.casehub.platform.api.model.CostTier;
import io.casehub.platform.api.model.ModelDescriptor;
import io.casehub.platform.api.model.ModelLocality;
import io.casehub.platform.api.model.ModelTier;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import java.util.Set;
import static org.assertj.core.api.Assertions.assertThat;

class AnthropicClientTest {

    @Test
    void vendorMetadata() {
        var client = new AnthropicClient(q -> List.of());
        assertThat(client.vendorKey()).isEqualTo("anthropic");
        assertThat(client.backendKey()).isEqualTo("claude");
        assertThat(client.authMethod()).isEqualTo("api-key");
        assertThat(client.requiredFields()).containsExactly("api-key");
    }

    @Test
    void parsesKnownModelWithSeedCatalogMerge() {
        String json = """
            {"data": [
                {"id": "claude-sonnet-5", "type": "model", "display_name": "Claude Sonnet 5", "created_at": "2025-01-01T00:00:00Z"}
            ]}
            """;

        var seedSonnet = new ModelDescriptor("claude-sonnet-5", "claude-sonnet-5",
            "claude", null, "anthropic", "claude", "Claude Sonnet 5",
            ModelTier.STANDARD, Set.of("text", "vision", "tool-use", "code", "reasoning"),
            200000, 16384, ModelLocality.CLOUD, CostTier.HIGH, "api-key", Map.of());

        var client = new AnthropicClient(q -> List.of(seedSonnet));
        List<ModelDescriptor> models = client.parseModelsResponse(json);

        assertThat(models).hasSize(1);
        var sonnet = models.get(0);
        assertThat(sonnet.apiModelId()).isEqualTo("claude-sonnet-5");
        assertThat(sonnet.tier()).isEqualTo(ModelTier.STANDARD);
        assertThat(sonnet.capabilities()).contains("vision", "tool-use");
        assertThat(sonnet.contextWindow()).isEqualTo(200000);
        assertThat(sonnet.maxOutput()).isEqualTo(16384);
        assertThat(sonnet.costTier()).isEqualTo(CostTier.HIGH);
    }

    @Test
    void parsesUnknownModelWithDefaults() {
        String json = """
            {"data": [
                {"id": "claude-future-model", "type": "model", "display_name": "Claude Future", "created_at": "2026-01-01T00:00:00Z"}
            ]}
            """;

        var client = new AnthropicClient(q -> List.of());
        List<ModelDescriptor> models = client.parseModelsResponse(json);

        assertThat(models).hasSize(1);
        var model = models.get(0);
        assertThat(model.apiModelId()).isEqualTo("claude-future-model");
        assertThat(model.tier()).isEqualTo(ModelTier.STANDARD);
        assertThat(model.capabilities()).containsExactly("text");
        assertThat(model.contextWindow()).isEqualTo(0);
        assertThat(model.costTier()).isNull();
        assertThat(model.backendKey()).isEqualTo("claude");
    }

    @Test
    void parsesMultipleModels() {
        String json = """
            {"data": [
                {"id": "claude-sonnet-5", "type": "model", "display_name": "Claude Sonnet 5"},
                {"id": "claude-haiku-4-5", "type": "model", "display_name": "Claude Haiku 4.5"}
            ]}
            """;

        var client = new AnthropicClient(q -> List.of());
        List<ModelDescriptor> models = client.parseModelsResponse(json);
        assertThat(models).hasSize(2);
    }

    @Test
    void emptyDataArrayReturnsEmpty() {
        var client = new AnthropicClient(q -> List.of());
        assertThat(client.parseModelsResponse("{\"data\": []}")).isEmpty();
    }

    @Test
    void invalidJsonReturnsEmpty() {
        var client = new AnthropicClient(q -> List.of());
        assertThat(client.parseModelsResponse("not json")).isEmpty();
    }

    @Test
    void missingApiKeyReturnsFailure() {
        var client = new AnthropicClient(q -> List.of());
        var result = client.listModels(Map.of());
        assertThat(result.valid()).isFalse();
        assertThat(result.errorMessage()).contains("api-key");
    }
}
