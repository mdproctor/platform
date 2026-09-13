package io.casehub.platform.llm.config.bedrock;

import io.casehub.platform.api.model.ModelCapabilities;
import io.casehub.platform.api.model.ModelDescriptor;
import io.casehub.platform.api.model.ModelLocality;
import io.casehub.platform.api.model.ModelTier;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class BedrockClientTest {

    private static final ModelDescriptor SEED_SONNET = new ModelDescriptor(
        "claude-sonnet-5", "claude-sonnet-5", "claude", null, "anthropic", "claude",
        "Claude Sonnet 5", ModelTier.FLAGSHIP, Set.of("text", "vision"),
        200000, 8192, ModelLocality.CLOUD, null, "api-key", Map.of());

    @Test
    void vendorKey() {
        var client = new BedrockClient(q -> List.of());
        assertThat(client.vendorKey()).isEqualTo("bedrock");
    }

    @Test
    void backendKey() {
        var client = new BedrockClient(q -> List.of());
        assertThat(client.backendKey()).isEqualTo("claude");
    }

    @Test
    void authMethod() {
        var client = new BedrockClient(q -> List.of());
        assertThat(client.authMethod()).isEqualTo("aws-sigv4");
    }

    @Test
    void requiredFields() {
        var client = new BedrockClient(q -> List.of());
        assertThat(client.requiredFields()).containsExactly("region");
    }

    @Test
    void parseModelsResponse_filtersToAnthropic() {
        var client = new BedrockClient(q -> List.of());
        String json = """
            {"modelSummaries": [
                {"modelId": "claude-sonnet-5", "modelName": "Claude Sonnet 5", "providerName": "Anthropic"},
                {"modelId": "titan-embed-v2", "modelName": "Titan Embeddings V2", "providerName": "Amazon"},
                {"modelId": "llama-3", "modelName": "Llama 3", "providerName": "Meta"}
            ]}
            """;

        var models = client.parseModelsResponse(json);

        assertThat(models).hasSize(1);
        assertThat(models.get(0).id()).isEqualTo("claude-sonnet-5");
    }

    @Test
    void parseModelsResponse_withSeedMatch_enrichesMetadata() {
        var client = new BedrockClient(q -> List.of(SEED_SONNET));
        String json = """
            {"modelSummaries": [
                {"modelId": "claude-sonnet-5", "modelName": "Claude 3.5 Sonnet", "providerName": "Anthropic"}
            ]}
            """;

        var models = client.parseModelsResponse(json);

        assertThat(models).hasSize(1);
        var model = models.get(0);
        assertThat(model.tier()).isEqualTo(ModelTier.FLAGSHIP);
        assertThat(model.capabilities()).contains("vision");
        assertThat(model.contextWindow()).isEqualTo(200000);
    }

    @Test
    void parseModelsResponse_withoutSeedMatch_usesDefaults() {
        var client = new BedrockClient(q -> List.of());
        String json = """
            {"modelSummaries": [
                {"modelId": "claude-new", "modelName": "Claude New", "providerName": "Anthropic"}
            ]}
            """;

        var models = client.parseModelsResponse(json);

        assertThat(models).hasSize(1);
        assertThat(models.get(0).tier()).isEqualTo(ModelTier.STANDARD);
        assertThat(models.get(0).contextWindow()).isEqualTo(0);
    }

    @Test
    void parseModelsResponse_emptyArray_returnsEmpty() {
        var client = new BedrockClient(q -> List.of());
        assertThat(client.parseModelsResponse("{\"modelSummaries\": []}")).isEmpty();
    }

    @Test
    void parseModelsResponse_invalidJson_returnsEmpty() {
        var client = new BedrockClient(q -> List.of());
        assertThat(client.parseModelsResponse("not json")).isEmpty();
    }

    @Test
    void parseModelsResponse_noAnthropicModels_returnsEmpty() {
        var client = new BedrockClient(q -> List.of());
        String json = """
            {"modelSummaries": [
                {"modelId": "titan-embed", "modelName": "Titan", "providerName": "Amazon"}
            ]}
            """;
        assertThat(client.parseModelsResponse(json)).isEmpty();
    }
}
