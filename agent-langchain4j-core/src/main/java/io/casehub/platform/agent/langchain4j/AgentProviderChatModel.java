package io.casehub.platform.agent.langchain4j;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.ModelProvider;
import dev.langchain4j.model.chat.Capability;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.listener.ChatModelListener;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.ChatRequestParameters;
import dev.langchain4j.model.chat.request.DefaultChatRequestParameters;
import dev.langchain4j.model.chat.request.ResponseFormat;
import dev.langchain4j.model.chat.request.ResponseFormatType;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import dev.langchain4j.model.chat.request.json.JsonSchema;
import dev.langchain4j.model.chat.request.json.JsonSchemaElement;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import dev.langchain4j.model.output.FinishReason;
import io.casehub.platform.agent.AgentEvent;
import io.casehub.platform.agent.AgentProvider;
import io.casehub.platform.agent.AgentSessionConfig;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;

public class AgentProviderChatModel implements ChatModel, StreamingChatModel {

    private final AgentProvider agentProvider;
    private final List<ChatModelListener> listeners;
    private final AgentLangchain4jProperties properties;

    public AgentProviderChatModel(AgentProvider agentProvider,
                                   List<ChatModelListener> listeners,
                                   AgentLangchain4jProperties properties) {
        this.agentProvider = agentProvider;
        this.listeners = listeners;
        this.properties = properties;
    }

    @Override
    public ModelProvider provider() {
        return ModelProvider.OTHER;
    }

    @Override
    public Set<Capability> supportedCapabilities() {
        return Set.of();
    }

    @Override
    public List<ChatModelListener> listeners() {
        return listeners;
    }

    @Override
    public ChatRequestParameters defaultRequestParameters() {
        return DefaultChatRequestParameters.EMPTY;
    }

    @Override
    public ChatResponse doChat(ChatRequest request) {
        String systemPrompt = extractSystemPrompt(request.messages());
        String userMessage = extractUserText(request.messages());
        String userWithSchema = prependSchema(request, userMessage);
        AgentSessionConfig config = AgentSessionConfig.of(systemPrompt, userWithSchema);
        String text = agentProvider.invoke(config)
            .filter(e -> e instanceof AgentEvent.TextDelta)
            .map(e -> ((AgentEvent.TextDelta) e).text())
            .collect().with(Collectors.joining())
            .await().atMost(properties.closeTimeout());
        return ChatResponse.builder()
            .aiMessage(AiMessage.from(text))
            .finishReason(FinishReason.STOP)
            .build();
    }

    @Override
    public void doChat(ChatRequest request, StreamingChatResponseHandler handler) {
        String systemPrompt = extractSystemPrompt(request.messages());
        String userMessage = extractUserText(request.messages());
        String userWithSchema = prependSchema(request, userMessage);
        AgentSessionConfig config = AgentSessionConfig.of(systemPrompt, userWithSchema);
        AgentEventBridge.dispatch(agentProvider.invoke(config), handler);
    }

    private static String extractSystemPrompt(List<ChatMessage> messages) {
        return SystemMessage.findFirst(messages).map(SystemMessage::text).orElse("");
    }

    static String extractUserText(List<ChatMessage> messages) {
        for (ChatMessage m : messages) {
            if (m instanceof AiMessage) {
                throw new IllegalArgumentException(
                    "AgentProvider-backed ChatModel does not accept AiMessage — " +
                    "session history is managed opaquely by the agent. " +
                    "Each call must supply only a SystemMessage + UserMessage.");
            }
        }
        List<UserMessage> userMessages = messages.stream()
            .filter(m -> m instanceof UserMessage)
            .map(m -> (UserMessage) m)
            .toList();
        if (userMessages.size() > 1) {
            throw new IllegalArgumentException(
                "AgentProvider-backed ChatModel accepts exactly one UserMessage per call.");
        }
        if (userMessages.isEmpty()) {
            throw new IllegalArgumentException("ChatRequest must contain at least one UserMessage");
        }
        try {
            return userMessages.get(0).singleText();
        } catch (RuntimeException e) {
            throw new IllegalArgumentException(
                "This adapter supports text-only UserMessage; " +
                "multimodal content is not supported.", e);
        }
    }

    static String prependSchema(ChatRequest request, String userText) {
        ResponseFormat format = request.responseFormat();
        if (format == null || format.type() != ResponseFormatType.JSON || format.jsonSchema() == null) {
            return userText;
        }
        return serializeSchema(format.jsonSchema()) + "\n\n" + userText;
    }

    static String serializeSchema(JsonSchema schema) {
        StringBuilder sb = new StringBuilder();
        sb.append("Respond with JSON matching schema \"").append(schema.name()).append("\":\n{\n");
        JsonSchemaElement root = schema.rootElement();
        if (root instanceof JsonObjectSchema obj) {
            Map<String, JsonSchemaElement> props = obj.properties();
            List<String> required = obj.required() != null ? obj.required() : List.of();
            new TreeMap<>(props).forEach((name, element) -> {
                String typeName = element.getClass().getSimpleName()
                    .replace("Json", "").replace("Schema", "").toLowerCase();
                String reqLabel = required.contains(name) ? " (required)" : "";
                sb.append("  \"").append(name).append("\": ").append(typeName).append(reqLabel).append(",\n");
            });
            if (!props.isEmpty()) {
                sb.setLength(sb.length() - 2);
                sb.append('\n');
            }
        }
        sb.append('}');
        return sb.toString();
    }
}
