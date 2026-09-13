package io.casehub.platform.llm.config;

import io.casehub.platform.api.credentials.LlmCredentialStore;
import io.casehub.platform.api.model.ModelDescriptor;
import io.casehub.platform.api.model.ModelLocality;
import io.casehub.platform.api.model.ModelTier;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class OpenAiCloudModelSourceTest {

    private static final ModelDescriptor SAMPLE = new ModelDescriptor(
        "gpt-4.1", "gpt-4.1", "openai", null, "openai", "gpt-4",
        "GPT-4.1", ModelTier.FLAGSHIP, Set.of("text", "vision", "code"),
        128000, 16384, ModelLocality.CLOUD, null, "api-key", Map.of());

    @Test
    void refresh_withCredentials_returnsModels() {
        var source = source(ValidationResult.success(List.of(SAMPLE)),
            Map.of("api-key", "sk-test"));
        assertThat(source.refresh()).hasSize(1);
    }

    @Test
    void refresh_withoutCredentials_returnsEmpty() {
        var source = source(ValidationResult.success(List.of(SAMPLE)), Map.of());
        assertThat(source.refresh()).isEmpty();
    }

    @Test
    void refresh_afterFailure_returnsLastKnownGood() {
        var resultRef = new AtomicReference<>(ValidationResult.success(List.of(SAMPLE)));
        var client = clientWith(resultRef);
        var source = new OpenAiCloudModelSource(client, storeWith(Map.of("api-key", "sk")));

        source.refresh();
        resultRef.set(ValidationResult.failure("timeout"));

        assertThat(source.refresh()).hasSize(1);
    }

    @Test
    void sourceId_isCloudOpenai() {
        assertThat(source(ValidationResult.success(List.of()), Map.of()).sourceId())
            .isEqualTo("cloud:openai");
    }

    @Test
    void priority_isFive() {
        assertThat(source(ValidationResult.success(List.of()), Map.of()).priority())
            .isEqualTo(5);
    }

    @Test
    void status_active_showsModelCount() {
        var source = source(ValidationResult.success(List.of(SAMPLE)),
            Map.of("api-key", "sk"));
        source.refresh();
        assertThat(source.status().state()).isEqualTo(CloudSourceStatus.State.ACTIVE);
        assertThat(source.status().modelCount()).isEqualTo(1);
    }

    @Test
    void status_inactive_showsGuidance() {
        var source = source(ValidationResult.success(List.of()), Map.of());
        assertThat(source.status().state()).isEqualTo(CloudSourceStatus.State.INACTIVE);
        assertThat(source.status().message()).contains("OPENAI_API_KEY");
    }

    private OpenAiCloudModelSource source(ValidationResult result, Map<String, String> creds) {
        return new OpenAiCloudModelSource(stubClient(result), storeWith(creds));
    }

    private VendorClient stubClient(ValidationResult result) {
        return new VendorClient() {
            @Override public String vendorKey() { return "openai"; }
            @Override public String backendKey() { return "openai"; }
            @Override public String displayName() { return "OpenAI"; }
            @Override public String authMethod() { return "api-key"; }
            @Override public List<String> requiredFields() { return List.of("api-key"); }
            @Override public ValidationResult listModels(Map<String, String> c) { return result; }
        };
    }

    private VendorClient clientWith(AtomicReference<ValidationResult> ref) {
        return new VendorClient() {
            @Override public String vendorKey() { return "openai"; }
            @Override public String backendKey() { return "openai"; }
            @Override public String displayName() { return "OpenAI"; }
            @Override public String authMethod() { return "api-key"; }
            @Override public List<String> requiredFields() { return List.of("api-key"); }
            @Override public ValidationResult listModels(Map<String, String> c) { return ref.get(); }
        };
    }

    private LlmCredentialStore storeWith(Map<String, String> creds) {
        return new LlmCredentialStore() {
            @Override public void store(String t, String r, Map<String, String> c) {}
            @Override public Map<String, String> resolve(String t, String r) { return creds; }
            @Override public void delete(String t, String r) {}
            @Override public List<String> listRefs(String t) { return List.of(); }
        };
    }
}
