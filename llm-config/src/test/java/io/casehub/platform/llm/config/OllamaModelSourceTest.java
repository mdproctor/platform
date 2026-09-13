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

class OllamaModelSourceTest {

    private static final ModelDescriptor LLAMA3 = new ModelDescriptor(
        "llama3", "llama3", "ollama", null, "meta", "llama", "Llama 3",
        ModelTier.STANDARD, Set.of("text"), 0, 0,
        ModelLocality.LOCAL, CostTier.FREE, "local", Map.of());

    @Test
    void sourceIdAndPriority() {
        var source = new OllamaModelSource(stubClient(true, List.of(LLAMA3)));
        assertThat(source.sourceId()).isEqualTo("local:ollama");
        assertThat(source.priority()).isEqualTo(3);
    }

    @Test
    void refreshReturnsModelsWhenOllamaOnline() {
        var source = new OllamaModelSource(stubClient(true, List.of(LLAMA3)));
        var models = source.refresh();
        assertThat(models).hasSize(1);
        assertThat(models.get(0).id()).isEqualTo("llama3");
        assertThat(source.status().state()).isEqualTo(OllamaSourceStatus.State.ONLINE);
    }

    @Test
    void refreshReturnsEmptyWhenOllamaOffline() {
        var source = new OllamaModelSource(stubClient(false, List.of()));
        var models = source.refresh();
        assertThat(models).isEmpty();
        assertThat(source.status().state()).isEqualTo(OllamaSourceStatus.State.OFFLINE);
    }

    @Test
    void noCacheOnFailure() {
        var client = new ToggleableStubClient(List.of(LLAMA3));
        var source = new OllamaModelSource(client);

        assertThat(source.refresh()).hasSize(1);

        client.setOnline(false);
        assertThat(source.refresh()).isEmpty();
        assertThat(source.status().state()).isEqualTo(OllamaSourceStatus.State.OFFLINE);
    }

    @Test
    void statusReflectsInitialState() {
        var source = new OllamaModelSource(stubClient(true, List.of()));
        assertThat(source.status().state()).isEqualTo(OllamaSourceStatus.State.OFFLINE);
        assertThat(source.status().message()).isEqualTo("Not yet refreshed");
    }

    private static OllamaClient stubClient(boolean online, List<ModelDescriptor> models) {
        return new OllamaClient(q -> List.of()) {
            @Override
            public ValidationResult listModels(Map<String, String> credentials) {
                return online
                    ? ValidationResult.success(models)
                    : ValidationResult.failure("Connection refused");
            }
        };
    }

    private static class ToggleableStubClient extends OllamaClient {
        private final List<ModelDescriptor> models;
        private volatile boolean online = true;

        ToggleableStubClient(List<ModelDescriptor> models) {
            super(q -> List.of());
            this.models = models;
        }

        void setOnline(boolean online) { this.online = online; }

        @Override
        public ValidationResult listModels(Map<String, String> credentials) {
            return online
                ? ValidationResult.success(models)
                : ValidationResult.failure("Connection refused");
        }
    }
}
