package io.casehub.platform.model;

import io.casehub.platform.api.model.ModelCapabilities;
import io.casehub.platform.api.model.ModelLocality;
import io.casehub.platform.api.model.ModelTier;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class SeedCatalogModelSourceTest {

    private final SeedCatalogModelSource source = new SeedCatalogModelSource();

    @Test
    void sourceId() {
        assertThat(source.sourceId()).isEqualTo("seed-catalog");
    }

    @Test
    void priority_isLowest() {
        assertThat(source.priority()).isEqualTo(0);
    }

    @Test
    void refresh_returnsModels() {
        var models = source.refresh();
        assertThat(models).isNotEmpty();
    }

    @Test
    void refresh_noDuplicateIds() {
        var models = source.refresh();
        var ids = models.stream().map(m -> m.id()).toList();
        assertThat(ids).doesNotHaveDuplicates();
    }

    @Test
    void refresh_claudeSonnet5Present() {
        var models = source.refresh();
        var sonnet = models.stream()
            .filter(m -> m.id().equals("claude-sonnet-5"))
            .findFirst().orElseThrow();
        assertThat(sonnet.vendor()).isEqualTo("anthropic");
        assertThat(sonnet.family()).isEqualTo("claude");
        assertThat(sonnet.backendKey()).isEqualTo("claude");
        assertThat(sonnet.tier()).isEqualTo(ModelTier.STANDARD);
        assertThat(sonnet.capabilities()).contains(ModelCapabilities.TEXT, ModelCapabilities.VISION);
        assertThat(sonnet.locality()).isEqualTo(ModelLocality.CLOUD);
    }

    @Test
    void refresh_localModelPresent() {
        var models = source.refresh();
        var local = models.stream()
            .filter(m -> m.locality() == ModelLocality.LOCAL)
            .findFirst().orElseThrow();
        assertThat(local.vendor()).isEqualTo("meta");
    }

    @Test
    void integration_registryResolvesFromSeed() {
        var registry = new InMemoryModelRegistry();
        var models = source.refresh();
        registry.replaceSource(source.sourceId(), source.priority(), models);
        assertThat(registry.resolveById("claude-sonnet-5")).isPresent();
        assertThat(registry.resolveById("gpt-4.1")).isPresent();
        assertThat(registry.resolveById("llama-4-scout")).isPresent();
    }

    @Test
    void refresh_vertexSonnetPresent() {
        var models = source.refresh();
        var vertexSonnet = models.stream()
                                 .filter(m -> m.id().equals("claude-sonnet-5-vertex"))
                                 .findFirst().orElseThrow();
        assertThat(vertexSonnet.apiModelId()).isEqualTo("claude-sonnet-5");
        assertThat(vertexSonnet.backendInstanceId()).isEqualTo("vertex");
        assertThat(vertexSonnet.backendKey()).isEqualTo("claude");
        assertThat(vertexSonnet.authMethod()).isEqualTo("gcp-adc");
        assertThat(vertexSonnet.vendor()).isEqualTo("anthropic");
    }

    @Test
    void refresh_existingModelsRetainDefaults() {
        var models = source.refresh();
        var directSonnet = models.stream()
                                 .filter(m -> m.id().equals("claude-sonnet-5"))
                                 .findFirst().orElseThrow();
        assertThat(directSonnet.apiModelId()).isEqualTo("claude-sonnet-5");
        assertThat(directSonnet.backendInstanceId()).isNull();
    }

}
