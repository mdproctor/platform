package io.casehub.platform.agent.ollama;

import io.casehub.platform.agent.AgentEvent;
import io.casehub.platform.agent.AgentSessionConfig;
import io.casehub.platform.agent.openai.AbstractOpenAiSdkBackend;
import io.smallrye.mutiny.Multi;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.time.Duration;
import java.util.function.Function;

@ApplicationScoped
public class OllamaAgentBackend extends AbstractOpenAiSdkBackend {

    private final OllamaAgentProperties properties;
    private final com.openai.client.OpenAIClient openAiClient;

    @Inject
    public OllamaAgentBackend(OllamaAgentProperties properties) {
        super(properties.maxConcurrentSessions(), null);
        this.properties = properties;
        this.openAiClient = com.openai.client.okhttp.OpenAIOkHttpClient.builder()
            .baseUrl(properties.host() + "/v1/")
            .apiKey("ollama")
            .build();
    }

    protected OllamaAgentBackend() {
        super();
        this.properties = null;
        this.openAiClient = null;
    }

    public OllamaAgentBackend(OllamaAgentProperties properties,
                              Function<AgentSessionConfig, Multi<AgentEvent>> streamFactory) {
        super(properties.maxConcurrentSessions(), streamFactory);
        this.properties = properties;
        this.openAiClient = null;
    }

    @Override public String key() { return "ollama"; }
    @Override protected com.openai.client.OpenAIClient openAiClient() { return openAiClient; }
    @Override protected Duration defaultTimeout() { return properties.defaultTimeout(); }
    @Override protected String defaultModel() { return properties.defaultModel(); }
}
