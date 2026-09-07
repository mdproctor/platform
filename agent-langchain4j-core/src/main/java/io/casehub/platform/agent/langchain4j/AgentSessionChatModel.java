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
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import dev.langchain4j.model.output.FinishReason;
import io.casehub.platform.agent.AgentEvent;
import io.casehub.platform.agent.AgentSession;

import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

public final class AgentSessionChatModel implements ChatModel, StreamingChatModel {

    private final AgentSession session;

    public AgentSessionChatModel(AgentSession session) {
        this.session = Objects.requireNonNull(session, "session");
    }

    public static AgentSessionChatModel wrap(AgentSession session) {
        return new AgentSessionChatModel(session);
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
        return List.of();
    }

    @Override
    public ChatRequestParameters defaultRequestParameters() {
        return DefaultChatRequestParameters.EMPTY;
    }

    @Override
    public ChatResponse doChat(ChatRequest request) {
        String userMessage = extractLastUserText(request.messages());
        userMessage = AgentProviderChatModel.prependSchema(request, userMessage);
        String text = session.query(userMessage)
            .filter(e -> e instanceof AgentEvent.TextDelta)
            .map(e -> ((AgentEvent.TextDelta) e).text())
            .collect().with(Collectors.joining())
            .await().indefinitely();
        return ChatResponse.builder()
            .aiMessage(AiMessage.from(text))
            .finishReason(FinishReason.STOP)
            .build();
    }

    @Override
    public void doChat(ChatRequest request, StreamingChatResponseHandler handler) {
        String userMessage = extractLastUserText(request.messages());
        userMessage = AgentProviderChatModel.prependSchema(request, userMessage);
        AgentEventBridge.dispatch(session.query(userMessage), handler);
    }

    private static String extractLastUserText(List<ChatMessage> messages) {
        for (ChatMessage m : messages) {
            if (m instanceof AiMessage) {
                throw new IllegalArgumentException(
                    "AgentSession-backed ChatModel adapters do not accept AiMessage — " +
                    "session history lives in the subprocess. " +
                    "Each call must supply only a single new UserMessage.");
            }
            if (m instanceof SystemMessage) {
                throw new IllegalArgumentException(
                    "AgentSession-backed ChatModel adapters do not accept SystemMessage — " +
                    "the session system prompt is fixed at open time via AgentSessionInit. " +
                    "Per-call system prompts are not supported.");
            }
        }
        List<UserMessage> userMessages = messages.stream()
            .filter(m -> m instanceof UserMessage)
            .map(m -> (UserMessage) m)
            .toList();
        if (userMessages.size() > 1) {
            throw new IllegalArgumentException(
                "AgentSession-backed ChatModel adapters accept exactly one UserMessage per call — " +
                "multiple UserMessage elements indicate caller confusion about session state.");
        }
        if (userMessages.isEmpty()) {
            throw new IllegalArgumentException("ChatRequest must contain at least one UserMessage");
        }
        try {
            return userMessages.get(0).singleText();
        } catch (RuntimeException e) {
            throw new IllegalArgumentException(
                "This adapter supports text-only UserMessage; " +
                "multimodal content is not supported by the subprocess.", e);
        }
    }
}
