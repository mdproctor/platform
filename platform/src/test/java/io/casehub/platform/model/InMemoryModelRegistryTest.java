package io.casehub.platform.model;

import io.casehub.platform.api.model.CostTier;
import io.casehub.platform.api.model.ModelCapabilities;
import io.casehub.platform.api.model.ModelDescriptor;
import io.casehub.platform.api.model.ModelLocality;
import io.casehub.platform.api.model.ModelQuery;
import io.casehub.platform.api.model.ModelTier;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class InMemoryModelRegistryTest {

    private ModelDescriptor desc(String id, String vendor, String family, ModelTier tier,
            Set<String> caps, ModelLocality locality, CostTier cost, String authMethod) {
        return new ModelDescriptor(id, id, "backend", null, vendor, family, id, tier,
            caps, 200000, 16384, locality, cost, authMethod, Map.of());
    }

    private ModelDescriptor claudeSonnet() {
        return desc("claude-sonnet-5", "anthropic", "claude", ModelTier.STANDARD,
            Set.of(ModelCapabilities.TEXT, ModelCapabilities.VISION), ModelLocality.CLOUD,
            CostTier.HIGH, "api-key");
    }

    private ModelDescriptor gpt4() {
        return desc("gpt-4.1", "openai", "gpt-4", ModelTier.STANDARD,
            Set.of(ModelCapabilities.TEXT, ModelCapabilities.VISION, ModelCapabilities.CODE),
            ModelLocality.CLOUD, CostTier.MEDIUM, "api-key");
    }

    private ModelDescriptor llama() {
        return desc("llama-4-scout", "meta", "llama", ModelTier.STANDARD,
            Set.of(ModelCapabilities.TEXT), ModelLocality.LOCAL, CostTier.FREE, "local");
    }

    @Test
    void resolveById_returnsDescriptor() {
        var registry = new InMemoryModelRegistry();
        registry.replaceSource("src", 1, List.of(claudeSonnet()));
        assertThat(registry.resolveById("claude-sonnet-5")).isPresent();
        assertThat(registry.resolveById("claude-sonnet-5").get().vendor()).isEqualTo("anthropic");
    }

    @Test
    void resolveById_unknownReturnsEmpty() {
        var registry = new InMemoryModelRegistry();
        assertThat(registry.resolveById("nonexistent")).isEmpty();
    }

    @Test
    void query_filtersByVendor() {
        var registry = new InMemoryModelRegistry();
        registry.replaceSource("src", 1, List.of(claudeSonnet(), gpt4(), llama()));
        var results = registry.query(ModelQuery.builder().vendor("anthropic").build());
        assertThat(results).hasSize(1);
        assertThat(results.get(0).id()).isEqualTo("claude-sonnet-5");
    }

    @Test
    void query_filtersByFamily() {
        var registry = new InMemoryModelRegistry();
        registry.replaceSource("src", 1, List.of(claudeSonnet(), gpt4()));
        var results = registry.query(ModelQuery.builder().family("claude").build());
        assertThat(results).hasSize(1);
        assertThat(results.get(0).id()).isEqualTo("claude-sonnet-5");
    }

    @Test
    void query_filtersByCapabilities() {
        var registry = new InMemoryModelRegistry();
        registry.replaceSource("src", 1, List.of(claudeSonnet(), gpt4(), llama()));
        var results = registry.query(ModelQuery.builder()
            .requiredCapabilities(Set.of(ModelCapabilities.VISION)).build());
        assertThat(results).hasSize(2);
    }

    @Test
    void query_filtersByMaxCostTier() {
        var registry = new InMemoryModelRegistry();
        registry.replaceSource("src", 1, List.of(claudeSonnet(), gpt4(), llama()));
        var results = registry.query(ModelQuery.builder().maxCostTier(CostTier.MEDIUM).build());
        assertThat(results).extracting(ModelDescriptor::id)
            .containsExactlyInAnyOrder("gpt-4.1", "llama-4-scout");
    }

    @Test
    void query_nullCostTierExcludedFromCostConstrainedQuery() {
        var registry = new InMemoryModelRegistry();
        var noCost = desc("unknown-model", "vendor", "fam", ModelTier.STANDARD,
            Set.of(), ModelLocality.CLOUD, null, null);
        registry.replaceSource("src", 1, List.of(noCost, llama()));
        var results = registry.query(ModelQuery.builder().maxCostTier(CostTier.MEDIUM).build());
        assertThat(results).extracting(ModelDescriptor::id).containsExactly("llama-4-scout");
    }

    @Test
    void query_filtersByLocality() {
        var registry = new InMemoryModelRegistry();
        registry.replaceSource("src", 1, List.of(claudeSonnet(), llama()));
        var results = registry.query(ModelQuery.builder().locality(ModelLocality.LOCAL).build());
        assertThat(results).hasSize(1);
        assertThat(results.get(0).id()).isEqualTo("llama-4-scout");
    }

    @Test
    void query_filtersByAuthMethod() {
        var registry = new InMemoryModelRegistry();
        registry.replaceSource("src", 1, List.of(claudeSonnet(), llama()));
        var results = registry.query(ModelQuery.builder().authMethod("local").build());
        assertThat(results).hasSize(1);
        assertThat(results.get(0).id()).isEqualTo("llama-4-scout");
    }

    @Test
    void emptyRegistry_returnsEmpty() {
        var registry = new InMemoryModelRegistry();
        assertThat(registry.resolveById("any")).isEmpty();
        assertThat(registry.query(ModelQuery.all())).isEmpty();
        assertThat(registry.all()).isEmpty();
    }

    @Test
    void replaceSource_atomicPerSource() {
        var registry = new InMemoryModelRegistry();
        registry.replaceSource("src1", 1, List.of(claudeSonnet()));
        registry.replaceSource("src2", 1, List.of(gpt4()));
        assertThat(registry.all()).hasSize(2);
        registry.replaceSource("src1", 1, List.of());
        assertThat(registry.all()).hasSize(1);
        assertThat(registry.resolveById("gpt-4.1")).isPresent();
    }

    @Test
    void priorityResolution_higherPriorityWins() {
        var registry = new InMemoryModelRegistry();
        var seedModel = desc("claude-sonnet-5", "anthropic", "claude", ModelTier.STANDARD,
            Set.of(), ModelLocality.CLOUD, CostTier.MEDIUM, "api-key");
        var liveModel = desc("claude-sonnet-5", "anthropic", "claude", ModelTier.STANDARD,
            Set.of(ModelCapabilities.TEXT, ModelCapabilities.VISION), ModelLocality.CLOUD,
            CostTier.HIGH, "api-key");
        registry.replaceSource("seed", 0, List.of(seedModel));
        registry.replaceSource("live", 10, List.of(liveModel));
        var resolved = registry.resolveById("claude-sonnet-5").orElseThrow();
        assertThat(resolved.costTier()).isEqualTo(CostTier.HIGH);
        assertThat(resolved.capabilities()).contains(ModelCapabilities.VISION);
    }

    @Test
    void priorityShadowing_removingHigherExposesLower() {
        var registry = new InMemoryModelRegistry();
        var seedModel = desc("claude-sonnet-5", "anthropic", "claude", ModelTier.STANDARD,
            Set.of(), ModelLocality.CLOUD, CostTier.MEDIUM, "api-key");
        var liveModel = desc("claude-sonnet-5", "anthropic", "claude", ModelTier.STANDARD,
            Set.of(ModelCapabilities.VISION), ModelLocality.CLOUD, CostTier.HIGH, "api-key");
        registry.replaceSource("seed", 0, List.of(seedModel));
        registry.replaceSource("live", 10, List.of(liveModel));
        registry.replaceSource("live", 10, List.of());
        var resolved = registry.resolveById("claude-sonnet-5").orElseThrow();
        assertThat(resolved.costTier()).isEqualTo(CostTier.MEDIUM);
    }

    @Test
    void replaceSource_returnsDelta() {
        var registry = new InMemoryModelRegistry();
        var delta1 = registry.replaceSource("src", 1, List.of(claudeSonnet(), gpt4()));
        assertThat(delta1.addedIds()).containsExactlyInAnyOrder("claude-sonnet-5", "gpt-4.1");
        assertThat(delta1.removedIds()).isEmpty();

        var delta2 = registry.replaceSource("src", 1, List.of(claudeSonnet(), llama()));
        assertThat(delta2.addedIds()).containsExactly("llama-4-scout");
        assertThat(delta2.removedIds()).containsExactly("gpt-4.1");
    }
}
