package io.casehub.platform.model;

import io.casehub.platform.api.model.CostTier;
import io.casehub.platform.api.model.ModelDescriptor;
import io.casehub.platform.api.model.ModelLocality;
import io.casehub.platform.api.model.ModelTier;
import io.casehub.platform.api.model.RefreshResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ModelRegistryServiceTest {

    private InMemoryModelRegistry registry;
    private StubRefresher refresher;
    private ModelRegistryService service;

    @BeforeEach
    void setUp() {
        registry = new InMemoryModelRegistry();
        registry.replaceSource("seed", 0, List.of(
            new ModelDescriptor("claude-sonnet", "claude-sonnet-4-20250514", "claude", null,
                "anthropic", "claude", "Claude Sonnet",
                ModelTier.STANDARD, Set.of("text", "vision"), 200000, 8192,
                ModelLocality.CLOUD, CostTier.MEDIUM, null, Map.of()),
            new ModelDescriptor("gpt-4o", "gpt-4o", "openai", null,
                "openai", "gpt-4", "GPT-4o",
                ModelTier.FLAGSHIP, Set.of("text", "vision"), 128000, 4096,
                ModelLocality.CLOUD, CostTier.HIGH, null, Map.of()),
            new ModelDescriptor("llama3", "llama3:8b", "ollama", null,
                "meta", "llama", "Llama 3 8B",
                ModelTier.FAST, Set.of("text"), 8192, 2048,
                ModelLocality.LOCAL, CostTier.FREE, null, Map.of())
        ));
        refresher = new StubRefresher();
        service = new ModelRegistryService(registry, refresher);
    }

    @Test
    void listModels_noFilters_returnsAll() {
        var result = service.listModels(null, null, null, null, null);
        assertThat(result).hasSize(3);
    }

    @Test
    void listModels_filterByVendor() {
        var result = service.listModels("anthropic", null, null, null, null);
        assertThat(result).hasSize(1);
        assertThat(result.get(0).id()).isEqualTo("claude-sonnet");
    }

    @Test
    void listModels_filterByTier() {
        var result = service.listModels(null, null, "FLAGSHIP", null, null);
        assertThat(result).hasSize(1);
        assertThat(result.get(0).id()).isEqualTo("gpt-4o");
    }

    @Test
    void listModels_filterByLocality() {
        var result = service.listModels(null, null, null, "LOCAL", null);
        assertThat(result).hasSize(1);
        assertThat(result.get(0).id()).isEqualTo("llama3");
    }

    @Test
    void listModels_filterByMaxCostTier() {
        var result = service.listModels(null, null, null, null, "LOW");
        assertThat(result).hasSize(1);
        assertThat(result.get(0).id()).isEqualTo("llama3");
    }

    @Test
    void listModels_caseInsensitiveEnums() {
        var result = service.listModels(null, null, "flagship", null, null);
        assertThat(result).hasSize(1);
    }

    @Test
    void listModels_invalidTier_throws() {
        assertThatThrownBy(() -> service.listModels(null, null, "BOGUS", null, null))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void listModels_blankParamsTreatedAsNull() {
        var result = service.listModels("", " ", "", "", "");
        assertThat(result).hasSize(3);
    }

    @Test
    void getModel_found() {
        var result = service.getModel("claude-sonnet");
        assertThat(result.displayName()).isEqualTo("Claude Sonnet");
    }

    @Test
    void getModel_notFound_throws() {
        assertThatThrownBy(() -> service.getModel("nonexistent"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Unknown model: nonexistent");
    }

    @Test
    void refreshRegistry_delegatesToRefresher() {
        refresher.result = new RefreshResult(2, 5, 1, 0, 0);
        var result = service.refreshRegistry();
        assertThat(result.sourcesRefreshed()).isEqualTo(2);
        assertThat(result.totalModels()).isEqualTo(5);
        assertThat(refresher.called).isTrue();
    }

    static class StubRefresher extends ModelRegistryRefresher {
        RefreshResult result = new RefreshResult(0, 0, 0, 0, 0);
        boolean called = false;

        @Override
        RefreshResult refreshAllWithResult() {
            called = true;
            return result;
        }
    }
}
