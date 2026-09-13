package io.casehub.platform.llm.config;

import io.casehub.platform.api.credentials.LlmCredentialStore;
import io.casehub.platform.api.identity.TenancyConstants;
import io.casehub.platform.api.model.ModelDescriptor;
import io.casehub.platform.api.model.ModelLocality;
import io.casehub.platform.api.model.ModelTier;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AnthropicCloudModelSourceTest {

    private static final ModelDescriptor SAMPLE = new ModelDescriptor(
        "claude-sonnet-5", "claude-sonnet-5", "claude", null, "anthropic", "claude",
        "Claude Sonnet 5", ModelTier.FLAGSHIP, Set.of("text", "vision"),
        200000, 8192, ModelLocality.CLOUD, null, "api-key", Map.of());

    @Test
    void refresh_withCredentials_returnsModels() {
        var source = source(ValidationResult.success(List.of(SAMPLE)),
            Map.of("api-key", "sk-test"));

        var result = source.refresh();

        assertThat(result).hasSize(1);
        assertThat(result.get(0).id()).isEqualTo("claude-sonnet-5");
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
        var source = new AnthropicCloudModelSource(client, storeWith(Map.of("api-key", "sk-test")));

        source.refresh();
        resultRef.set(ValidationResult.failure("timeout"));
        var result = source.refresh();

        assertThat(result).hasSize(1);
    }

    @Test
    void refresh_credentialsAbsentWithCache_returnsCache() {
        var credsRef = new AtomicReference<>(Map.of("api-key", "sk-test"));
        var store = new LlmCredentialStore() {
            @Override public void store(String t, String r, Map<String, String> c) {}
            @Override public Map<String, String> resolve(String t, String r) { return credsRef.get(); }
            @Override public void delete(String t, String r) {}
            @Override public List<String> listRefs(String t) { return List.of(); }
        };
        var source = new AnthropicCloudModelSource(
            stubClient(ValidationResult.success(List.of(SAMPLE))), store);

        source.refresh();
        credsRef.set(Map.of());
        var result = source.refresh();

        assertThat(result).hasSize(1);
    }

    @Test
    void sourceId_isCloudAnthropicPrefix() {
        assertThat(source(ValidationResult.success(List.of()), Map.of()).sourceId())
            .isEqualTo("cloud:anthropic");
    }

    @Test
    void priority_isFive() {
        assertThat(source(ValidationResult.success(List.of()), Map.of()).priority())
            .isEqualTo(5);
    }

    @Test
    void status_active_showsModelCount() {
        var source = source(ValidationResult.success(List.of(SAMPLE)),
            Map.of("api-key", "sk-test"));
        source.refresh();

        var status = source.status();
        assertThat(status.state()).isEqualTo(CloudSourceStatus.State.ACTIVE);
        assertThat(status.modelCount()).isEqualTo(1);
    }

    @Test
    void status_inactive_showsGuidance() {
        var source = source(ValidationResult.success(List.of()), Map.of());

        var status = source.status();
        assertThat(status.state()).isEqualTo(CloudSourceStatus.State.INACTIVE);
        assertThat(status.message()).contains("ANTHROPIC_API_KEY");
    }

    @Test
    void status_error_afterApiFail() {
        var source = source(ValidationResult.failure("rate limited"),
            Map.of("api-key", "sk-test"));
        source.refresh();

        var status = source.status();
        assertThat(status.state()).isEqualTo(CloudSourceStatus.State.ERROR);
        assertThat(status.message()).contains("rate limited");
    }

    private AnthropicCloudModelSource source(ValidationResult result, Map<String, String> creds) {
        return new AnthropicCloudModelSource(stubClient(result), storeWith(creds));
    }

    private VendorClient stubClient(ValidationResult result) {
        return new VendorClient() {
            @Override public String vendorKey() { return "anthropic"; }
            @Override public String backendKey() { return "claude"; }
            @Override public String displayName() { return "Anthropic"; }
            @Override public String authMethod() { return "api-key"; }
            @Override public List<String> requiredFields() { return List.of("api-key"); }
            @Override public ValidationResult listModels(Map<String, String> c) { return result; }
        };
    }

    private VendorClient clientWith(AtomicReference<ValidationResult> ref) {
        return new VendorClient() {
            @Override public String vendorKey() { return "anthropic"; }
            @Override public String backendKey() { return "claude"; }
            @Override public String displayName() { return "Anthropic"; }
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
