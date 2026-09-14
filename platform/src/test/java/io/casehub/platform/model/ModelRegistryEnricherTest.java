package io.casehub.platform.model;

import io.casehub.platform.api.mcp.McpDomain;
import io.casehub.platform.api.model.CostTier;
import io.casehub.platform.api.model.ModelDescriptor;
import io.casehub.platform.api.model.ModelLocality;
import io.casehub.platform.api.model.ModelTier;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class ModelRegistryEnricherTest {

    @Test
    void has_McpDomain_models_annotation() {
        McpDomain ann = ModelRegistryEnricher.class.getAnnotation(McpDomain.class);
        assertThat(ann).isNotNull();
        assertThat(ann.value()).isEqualTo("models");
    }

    @Test
    void summary_is_non_empty() {
        var enricher = new ModelRegistryEnricher();
        enricher.registry = new InMemoryModelRegistry();
        assertThat(enricher.summary()).isNotBlank();
    }

    @Test
    @SuppressWarnings("unchecked")
    void state_contains_modelCount_and_vendors() {
        var registry = new InMemoryModelRegistry();
        registry.replaceSource("seed", 0, List.of(
            new ModelDescriptor("m1", "m1", "openai", null, "openai", "gpt-4", "GPT-4",
                ModelTier.FLAGSHIP, Set.of(), 128000, 4096, ModelLocality.CLOUD, CostTier.HIGH, null, Map.of()),
            new ModelDescriptor("m2", "m2", "claude", null, "anthropic", "claude", "Claude",
                ModelTier.STANDARD, Set.of(), 200000, 8192, ModelLocality.CLOUD, CostTier.MEDIUM, null, Map.of())
        ));

        var enricher = new ModelRegistryEnricher();
        enricher.registry = registry;

        Map<String, Object> state = enricher.state();
        assertThat(state.get("modelCount")).isEqualTo(2);
        assertThat((List<String>) state.get("vendors")).containsExactly("anthropic", "openai");
    }

    @Test
    void state_empty_registry() {
        var enricher = new ModelRegistryEnricher();
        enricher.registry = new InMemoryModelRegistry();

        Map<String, Object> state = enricher.state();
        assertThat(state.get("modelCount")).isEqualTo(0);
        assertThat((List<?>) state.get("vendors")).isEmpty();
    }
}
