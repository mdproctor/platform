package io.casehub.platform.agent.openai;

import com.openai.client.okhttp.OpenAIOkHttpClient;
import io.casehub.platform.agent.BackendInstance;
import io.casehub.platform.agent.BackendInstanceFactory;
import jakarta.enterprise.context.ApplicationScoped;

import java.time.Duration;
import java.util.Map;

@ApplicationScoped
public class OpenAiDirectBackendFactory implements BackendInstanceFactory {

    @Override
    public String backendKey() { return "openai"; }

    @Override
    public boolean handles(String credentialRef, Map<String, String> credentials) {
        return credentialRef.contains("openai") && credentials.containsKey("api-key");
    }

    @Override
    public BackendInstance create(String credentialRef, Map<String, String> credentials) {
        String apiKey = credentials.get("api-key");
        String instanceId = deriveInstanceId(credentialRef);

        var client = OpenAIOkHttpClient.builder()
                .apiKey(apiKey)
                .build();

        var backend = new OpenAiAgentBackend(client, Duration.ofSeconds(30), null, 4);
        return new BackendInstance(instanceId, backend);
    }

    String deriveInstanceId(String credentialRef) {
        if ("cloud-openai".equals(credentialRef)) return "default";
        return credentialRef.replace("-openai", "");
    }
}
