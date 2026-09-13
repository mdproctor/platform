package io.casehub.platform.agent.openai;

import io.casehub.platform.agent.AgentEvent;
import io.casehub.platform.agent.AgentSessionConfig;
import io.smallrye.mutiny.Multi;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.time.Duration;
import java.util.function.Function;

@ApplicationScoped
public class OpenAiAgentBackend extends AbstractOpenAiSdkBackend {

    private final OpenAiAgentProperties properties;
    private final com.openai.client.OpenAIClient openAiClient;
    private       Duration                       factoryTimeout;
    private       String                         factoryModel;


    @Inject
    public OpenAiAgentBackend(OpenAiAgentProperties properties) {
        super(properties.maxConcurrentSessions(), null);
        this.properties = properties;
        com.openai.client.okhttp.OpenAIOkHttpClient.Builder clientBuilder =
            com.openai.client.okhttp.OpenAIOkHttpClient.builder();
        properties.apiKey().ifPresent(clientBuilder::apiKey);
        this.openAiClient = clientBuilder.build();
    }

    protected OpenAiAgentBackend() {
        super();
        this.properties = null;
        this.openAiClient = null;
    }

    public OpenAiAgentBackend(OpenAiAgentProperties properties,
                              Function<AgentSessionConfig, Multi<AgentEvent>> streamFactory) {
        super(properties.maxConcurrentSessions(), streamFactory);
        this.properties = properties;
        this.openAiClient = null;
    }

    OpenAiAgentBackend(com.openai.client.OpenAIClient client,
                       Duration defaultTimeout, String defaultModel,
                       int maxConcurrentSessions) {
        super(maxConcurrentSessions, null);
        this.properties     = null;
        this.openAiClient   = client;
        this.factoryTimeout = defaultTimeout;
        this.factoryModel   = defaultModel;
    }


    @Override public String key() { return "openai"; }
    @Override protected com.openai.client.OpenAIClient openAiClient() { return openAiClient; }

    @Override
    protected Duration defaultTimeout() {return properties != null ? properties.defaultTimeout() : factoryTimeout;}

    @Override
    protected String defaultModel() {return properties != null ? properties.defaultModel() : factoryModel;}
}
