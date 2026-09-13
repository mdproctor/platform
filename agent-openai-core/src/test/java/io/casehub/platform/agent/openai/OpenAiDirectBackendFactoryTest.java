package io.casehub.platform.agent.openai;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class OpenAiDirectBackendFactoryTest {

    private final OpenAiDirectBackendFactory factory = new OpenAiDirectBackendFactory();

    @Test
    void backendKeyIsOpenai() {
        assertThat(factory.backendKey()).isEqualTo("openai");
    }

    @Test
    void handlesOpenaiCredentialRefWithApiKey() {
        assertThat(factory.handles("cloud-openai", Map.of("api-key", "sk-test"))).isTrue();
    }

    @Test
    void doesNotHandleAnthropicRef() {
        assertThat(factory.handles("cloud-anthropic", Map.of("api-key", "sk-ant"))).isFalse();
    }

    @Test
    void doesNotHandleRefWithoutApiKey() {
        assertThat(factory.handles("cloud-openai", Map.of("region", "us-east-1"))).isFalse();
    }

    @Test
    void createProducesBackendWithCorrectInstanceId() {
        var instance = factory.create("cloud-openai", Map.of("api-key", "sk-test"));
        assertThat(instance.instanceId()).isEqualTo("default");
        assertThat(instance.backend().key()).isEqualTo("openai");
    }

    @Test
    void createDerivesNonDefaultInstanceId() {
        var instance = factory.create("extra-openai", Map.of("api-key", "sk-extra"));
        assertThat(instance.instanceId()).isEqualTo("extra");
    }

    @Test
    void createDerivesTenantInstanceId() {
        var instance = factory.create("tenant-42-openai", Map.of("api-key", "sk-t42"));
        assertThat(instance.instanceId()).isEqualTo("tenant-42");
    }

    @Test
    void deriveInstanceIdMapsCloudOpenaiToDefault() {
        assertThat(factory.deriveInstanceId("cloud-openai")).isEqualTo("default");
    }

    @Test
    void deriveInstanceIdStripsOpenaiSuffix() {
        assertThat(factory.deriveInstanceId("extra-openai")).isEqualTo("extra");
        assertThat(factory.deriveInstanceId("tenant-42-openai")).isEqualTo("tenant-42");
    }
}
