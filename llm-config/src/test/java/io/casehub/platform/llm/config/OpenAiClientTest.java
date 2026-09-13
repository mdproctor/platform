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

class OpenAiClientTest {

    @Test
    void vendorMetadata() {
        var client = new OpenAiClient(q -> List.of());
        assertThat(client.vendorKey()).isEqualTo("openai");
        assertThat(client.backendKey()).isEqualTo("openai");
        assertThat(client.authMethod()).isEqualTo("api-key");
    }

    @Test
    void parsesKnownModelWithSeedCatalogMerge() {
        String json = """
            {"data": [
                {"id": "gpt-4.1", "created": 1700000000, "owned_by": "openai"}
            ]}
            """;

        var seedGpt = new ModelDescriptor("gpt-4.1", "gpt-4.1",
            "openai", null, "openai", "gpt-4", "GPT-4.1",
            ModelTier.STANDARD, Set.of("text", "vision", "tool-use", "code", "reasoning"),
            1048576, 32768, ModelLocality.CLOUD, CostTier.MEDIUM, "api-key", Map.of());

        var client = new OpenAiClient(q -> List.of(seedGpt));
        List<ModelDescriptor> models = client.parseModelsResponse(json);

        assertThat(models).hasSize(1);
        var gpt = models.get(0);
        assertThat(gpt.apiModelId()).isEqualTo("gpt-4.1");
        assertThat(gpt.tier()).isEqualTo(ModelTier.STANDARD);
        assertThat(gpt.contextWindow()).isEqualTo(1048576);
        assertThat(gpt.family()).isEqualTo("gpt-4");
    }

    @Test
    void parsesUnknownModelWithDefaults() {
        String json = """
            {"data": [
                {"id": "gpt-5", "created": 1700000000, "owned_by": "openai"}
            ]}
            """;

        var client = new OpenAiClient(q -> List.of());
        var models = client.parseModelsResponse(json);

        assertThat(models).hasSize(1);
        assertThat(models.get(0).apiModelId()).isEqualTo("gpt-5");
        assertThat(models.get(0).contextWindow()).isEqualTo(0);
        assertThat(models.get(0).costTier()).isNull();
    }

    @Test
    void missingApiKeyReturnsFailure() {
        var client = new OpenAiClient(q -> List.of());
        var result = client.listModels(Map.of());
        assertThat(result.valid()).isFalse();
    }
}
