package io.casehub.platform.agent.langchain4j;

import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import io.casehub.platform.agent.AgentBackend;
import io.casehub.platform.agent.AgentEvent;
import io.casehub.platform.agent.AgentSession;
import io.casehub.platform.agent.AgentSessionConfig;
import io.casehub.platform.agent.AgentSessionInit;
import io.casehub.platform.agent.AgentSessionLimitException;
import io.casehub.platform.agent.AgentTimeoutException;
import io.smallrye.mutiny.Multi;
import org.jboss.logging.Logger;

import java.util.List;
import java.util.concurrent.Semaphore;

public class ChatModelAgentProvider implements AgentBackend {

    private static final Logger LOG = Logger.getLogger(ChatModelAgentProvider.class);

    private final ChatModel chatModel;
    private final StreamingChatModel streamingChatModel;
    private final AgentLangchain4jProperties properties;
    private final Semaphore semaphore;
    private final boolean disabled;

    public ChatModelAgentProvider(ChatModel chatModel,
                                   StreamingChatModel streamingChatModel,
                                   AgentLangchain4jProperties properties) {
        this.chatModel = chatModel;
        this.streamingChatModel = streamingChatModel;
        this.properties = properties;
        int maxSessions = properties.maxConcurrentSessions();
        if (maxSessions < 0) {
            throw new IllegalStateException(
                "casehub.platform.agent.langchain4j.max-concurrent-sessions must be >= 0, got " + maxSessions);
        }
        this.semaphore = new Semaphore(maxSessions == 0 ? Integer.MAX_VALUE : maxSessions);
        this.disabled = chatModel == null;
        if (disabled) {
            LOG.warn("ChatModelAgentProvider: no ChatModel available — " +
                     "add a langchain4j provider to activate. " +
                     "AgentProvider calls will fail until a ChatModel is present.");
        }
    }

    @Override
    public String key() {return "langchain4j";}

    int availablePermits() {
        return semaphore.availablePermits();
    }

    @Override
    public Multi<AgentEvent> invoke(AgentSessionConfig config) {
        if (disabled) {
            return Multi.createFrom().failure(new IllegalStateException(
                "ChatModelAgentProvider is inactive — no ChatModel bean available. " +
                "Add a langchain4j provider (e.g. quarkus-langchain4j-openai) " +
                "to the classpath."));
        }
        if (!semaphore.tryAcquire()) {
            LOG.warnf("ChatModelAgentProvider: session limit reached (%d/%d active sessions)",
                properties.maxConcurrentSessions() - semaphore.availablePermits(),
                properties.maxConcurrentSessions());
            return Multi.createFrom().failure(
                new AgentSessionLimitException(properties.maxConcurrentSessions()));
        }
        try {
            if (!config.mcpServers().isEmpty()) {
                LOG.warnf("ChatModelAgentProvider: mcpServers ignored — LangChain4j models " +
                          "do not support MCP. %d server(s) configured but unused.",
                          config.mcpServers().size());
            }

            ChatRequest request = ChatRequest.builder()
                .messages(config.systemPrompt().isEmpty()
                    ? List.of(UserMessage.from(config.userPrompt()))
                    : List.of(SystemMessage.from(config.systemPrompt()),
                              UserMessage.from(config.userPrompt())))
                .build();

            Multi<AgentEvent> result;
            if (streamingChatModel != null) {
                result = AgentEventBridge.stream(streamingChatModel, request);
            } else {
                result = Multi.createFrom().item(() -> {
                    ChatResponse response = chatModel.chat(request);
                    String text = response.aiMessage().text();
                    return (AgentEvent) new AgentEvent.TextDelta(text != null ? text : "");
                });
            }

            if (config.timeout() != null) {
                result = result.ifNoItem().after(config.timeout()).failWith(
                    () -> new AgentTimeoutException(config.timeout()));
            }
            return result
                .onCompletion().invoke(semaphore::release)
                .onFailure().invoke(t -> semaphore.release())
                .onCancellation().invoke(semaphore::release);
        } catch (Exception e) {
            semaphore.release();
            return Multi.createFrom().failure(e);
        }
    }

    @Override
    public AgentSession openSession(AgentSessionInit init) {
        if (disabled) {
            throw new IllegalStateException(
                "ChatModelAgentProvider is inactive — no ChatModel bean available.");
        }
        if (!semaphore.tryAcquire()) {
            LOG.warnf("ChatModelAgentProvider: session limit reached (%d/%d active sessions)",
                properties.maxConcurrentSessions() - semaphore.availablePermits(),
                properties.maxConcurrentSessions());
            throw new AgentSessionLimitException(properties.maxConcurrentSessions());
        }
        try {
            return new ChatModelAgentSession(chatModel, streamingChatModel, init, properties, semaphore);
        } catch (Exception e) {
            semaphore.release();
            throw e;
        }
    }
}
