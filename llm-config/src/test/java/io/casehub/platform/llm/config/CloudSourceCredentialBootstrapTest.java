package io.casehub.platform.llm.config;

import io.casehub.platform.api.identity.TenancyConstants;
import java.util.Map;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CloudSourceCredentialBootstrapTest {

    @Test
    void detectsAnthropicApiKey() {
        var store = new InMemoryLlmCredentialStore();
        var bootstrap = new CloudSourceCredentialBootstrap(store,
            Map.of("ANTHROPIC_API_KEY", "sk-ant-123")::get);

        bootstrap.detectAndSeed();

        var creds = store.resolve(TenancyConstants.PLATFORM_TENANT_ID, "cloud-anthropic");
        assertThat(creds).containsEntry("api-key", "sk-ant-123");
    }

    @Test
    void detectsOpenAiApiKey() {
        var store = new InMemoryLlmCredentialStore();
        var bootstrap = new CloudSourceCredentialBootstrap(store,
            Map.of("OPENAI_API_KEY", "sk-oai-456")::get);

        bootstrap.detectAndSeed();

        var creds = store.resolve(TenancyConstants.PLATFORM_TENANT_ID, "cloud-openai");
        assertThat(creds).containsEntry("api-key", "sk-oai-456");
    }

    @Test
    void doesNotOverwriteExistingCredentials() {
        var store = new InMemoryLlmCredentialStore();
        store.store(TenancyConstants.PLATFORM_TENANT_ID, "cloud-anthropic",
            Map.of("api-key", "admin-configured-key"));
        var bootstrap = new CloudSourceCredentialBootstrap(store,
            Map.of("ANTHROPIC_API_KEY", "env-key")::get);

        bootstrap.detectAndSeed();

        var creds = store.resolve(TenancyConstants.PLATFORM_TENANT_ID, "cloud-anthropic");
        assertThat(creds).containsEntry("api-key", "admin-configured-key");
    }

    @Test
    void noEnvVars_storesNothing() {
        var store = new InMemoryLlmCredentialStore();
        var bootstrap = new CloudSourceCredentialBootstrap(store, k -> null);

        bootstrap.detectAndSeed();

        assertThat(store.resolve(TenancyConstants.PLATFORM_TENANT_ID, "cloud-anthropic")).isEmpty();
        assertThat(store.resolve(TenancyConstants.PLATFORM_TENANT_ID, "cloud-openai")).isEmpty();
    }

    @Test
    void detectsMultipleProviders() {
        var store = new InMemoryLlmCredentialStore();
        var env = Map.of("ANTHROPIC_API_KEY", "sk-ant", "OPENAI_API_KEY", "sk-oai");
        var bootstrap = new CloudSourceCredentialBootstrap(store, env::get);

        bootstrap.detectAndSeed();

        assertThat(store.resolve(TenancyConstants.PLATFORM_TENANT_ID, "cloud-anthropic"))
            .containsEntry("api-key", "sk-ant");
        assertThat(store.resolve(TenancyConstants.PLATFORM_TENANT_ID, "cloud-openai"))
            .containsEntry("api-key", "sk-oai");
    }

    @Test
    void blankApiKey_ignored() {
        var store = new InMemoryLlmCredentialStore();
        var bootstrap = new CloudSourceCredentialBootstrap(store,
            Map.of("ANTHROPIC_API_KEY", "  ")::get);

        bootstrap.detectAndSeed();

        assertThat(store.resolve(TenancyConstants.PLATFORM_TENANT_ID, "cloud-anthropic")).isEmpty();
    }
}
