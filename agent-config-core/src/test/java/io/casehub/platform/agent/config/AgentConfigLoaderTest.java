package io.casehub.platform.agent.config;

import io.casehub.platform.api.credentials.LlmCredentialStore;
import io.casehub.platform.api.model.ModelDescriptor;
import io.casehub.platform.api.model.ModelQuery;
import io.casehub.platform.api.model.MutableModelRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class AgentConfigLoaderTest {

    @TempDir Path tempDir;

    @Test
    void load_processesManifestAndReturnsResult() throws IOException {
        Files.writeString(tempDir.resolve("agent-config.yaml"),
                "defaults:\n  backend: claude\naliases:\n  fast:\n    tier: FAST\n");

        var loader = new AgentConfigLoader(
                new NoOpCredentialStore(),
                new NoOpModelRegistry(),
                ref -> Map.of(),
                Map.of(),
                modelId -> {},
                null,
                tempDir,
                null, false);

        ManifestResult result = loader.load();

        assertThat(result).isNotNull();
        assertThat(result.defaultBackendKey()).isEqualTo("claude");
        assertThat(result.aliases()).containsKey("fast");
    }

    @Test
    void load_withNoManifest_returnsEmptyResult() {
        var loader = new AgentConfigLoader(
                new NoOpCredentialStore(),
                new NoOpModelRegistry(),
                ref -> Map.of(),
                Map.of(),
                modelId -> {},
                null,
                tempDir,
                null, false);

        ManifestResult result = loader.load();

        assertThat(result).isNotNull();
        assertThat(result.aliases()).isEmpty();
    }

    private static class NoOpCredentialStore implements LlmCredentialStore {
        @Override public void store(String tenantId, String ref, Map<String, String> credentials) {}
        @Override public Map<String, String> resolve(String tenantId, String ref) { return Map.of(); }
        @Override public void delete(String tenantId, String ref) {}
        @Override public List<String> listRefs(String tenantId) { return List.of(); }
    }

    private static class NoOpModelRegistry implements MutableModelRegistry {
        @Override public MutableModelRegistry.CatalogDelta replaceSource(String sourceId, int priority, List<ModelDescriptor> models) {
            return new MutableModelRegistry.CatalogDelta(Set.of(), Set.of(), Set.of());
        }
        @Override public Optional<ModelDescriptor> resolveById(String id) { return Optional.empty(); }
        @Override public List<ModelDescriptor> query(ModelQuery query) { return List.of(); }
        @Override public List<ModelDescriptor> all() { return List.of(); }
    }
}
