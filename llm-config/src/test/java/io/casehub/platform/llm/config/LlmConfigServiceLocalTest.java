package io.casehub.platform.llm.config;

import io.casehub.platform.api.model.CostTier;
import io.casehub.platform.api.model.ModelDescriptor;
import io.casehub.platform.api.model.ModelLocality;
import io.casehub.platform.api.model.ModelTier;
import io.casehub.platform.model.InMemoryModelRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class LlmConfigServiceLocalTest {

    private LlmConfigService service;
    private OllamaModelSource ollamaSource;

    @BeforeEach
    void setUp() {
        var stubClient = new OllamaClient(q -> List.of()) {
            @Override
            public ValidationResult listModels(Map<String, String> credentials) {
                return ValidationResult.success(List.of(
                    new ModelDescriptor("llama3", "llama3", "ollama", null, "meta", "llama", "Llama 3",
                        ModelTier.STANDARD, Set.of("text"), 0, 0,
                        ModelLocality.LOCAL, CostTier.FREE, "local", Map.of())));
            }
        };
        ollamaSource = new OllamaModelSource(stubClient, true);
        ollamaSource.refresh();

        service = new LlmConfigService(
            new LlmConfigServiceTest.StubPrincipal("tenant-1"),
            new ConfiguredModelSourceManager(new InMemoryModelRegistry(), new InMemoryLlmCredentialStore()),
            new InMemoryLlmCredentialStore(),
            new LlmConfigServiceTest.StubPreferenceStore(),
            List.of(),
            List.of(),
            ollamaSource);
    }

    @Test
    void ollamaStatusReflectsOnlineState() {
        var status = service.ollamaStatus();
        assertThat(status).isNotNull();
        assertThat(status.state()).isEqualTo(OllamaSourceStatus.State.ONLINE);
    }

    @Test
    void ollamaStatusReturnsOfflineWhenNotConfigured() {
        var offlineService = new LlmConfigService(
            new LlmConfigServiceTest.StubPrincipal("tenant-1"),
            new ConfiguredModelSourceManager(new InMemoryModelRegistry(), new InMemoryLlmCredentialStore()),
            new InMemoryLlmCredentialStore(),
            new LlmConfigServiceTest.StubPreferenceStore(),
            List.of(),
            List.of(),
            null);
        var status = offlineService.ollamaStatus();
        assertThat(status.state()).isEqualTo(OllamaSourceStatus.State.OFFLINE);
    }

    @Test
    void pullModelReturnsOperationWithPullingStatus() {
        var op = service.pullModel(new PullRequest("llama3"));
        assertThat(op.operationId()).isNotNull();
        assertThat(op.modelRef()).isEqualTo("llama3");
        assertThat(op.status()).isEqualTo(PullOperation.PullStatus.PULLING);
    }

    @Test
    void pullStatusReturnsProgressForKnownOperation() {
        var op = service.pullModel(new PullRequest("llama3"));
        var progress = service.pullStatus(op.operationId());
        assertThat(progress).isNotNull();
        assertThat(progress.operationId()).isEqualTo(op.operationId());
        assertThat(progress.modelRef()).isEqualTo("llama3");
    }

    @Test
    void pullStatusReturnsNullForUnknownOperation() {
        assertThat(service.pullStatus("nonexistent")).isNull();
    }

    @Test
    void cancelPullUpdatesStatusToCancelled() {
        var op = service.pullModel(new PullRequest("llama3"));
        service.cancelPull(op.operationId());
        var progress = service.pullStatus(op.operationId());
        assertThat(progress.status()).isEqualTo(PullOperation.PullStatus.CANCELLED);
    }

    @Test
    void pullModelAcceptsHuggingFaceRef() {
        var op = service.pullModel(new PullRequest("hf.co/TheBloke/Llama-2-7B-GGUF:Q4_K_M"));
        assertThat(op.modelRef()).isEqualTo("hf.co/TheBloke/Llama-2-7B-GGUF:Q4_K_M");
        assertThat(op.status()).isEqualTo(PullOperation.PullStatus.PULLING);
    }
}
