package io.casehub.platform.agent.langchain4j;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.memory.ChatMemory;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import io.casehub.platform.agent.AgentEvent;
import io.casehub.platform.agent.AgentSession;
import io.casehub.platform.agent.AgentSessionInit;
import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.Uni;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicReference;

class ChatModelAgentSession implements AgentSession {

    private enum State { IDLE, ACTIVE, CLOSED }

    private final ChatModel chatModel;
    private final StreamingChatModel streamingChatModel;
    private final String systemPrompt;
    private final ChatMemory memory;
    private final AtomicReference<State> state = new AtomicReference<>(State.IDLE);
    private final Semaphore semaphore;

    ChatModelAgentSession(ChatModel chatModel, StreamingChatModel streamingChatModel,
                          AgentSessionInit init, AgentLangchain4jProperties properties,
                          Semaphore semaphore) {
        this.chatModel = chatModel;
        this.streamingChatModel = streamingChatModel;
        this.systemPrompt = init.systemPrompt();
        this.memory = MessageWindowChatMemory.withMaxMessages(properties.sessionMemoryWindowSize());
        this.semaphore = semaphore;
    }

    @Override
    public Multi<AgentEvent> query(String prompt) {
        if (!state.compareAndSet(State.IDLE, State.ACTIVE)) {
            State current = state.get();
            throw new IllegalStateException(current == State.CLOSED
                ? "session is closed"
                : "a turn is already active — wait for it to complete or call interrupt()");
        }
        memory.add(UserMessage.from(prompt));
        List<ChatMessage> messages = new ArrayList<>();
        if (!systemPrompt.isEmpty()) {
            messages.add(SystemMessage.from(systemPrompt));
        }
        messages.addAll(memory.messages());
        ChatRequest request = ChatRequest.builder().messages(messages).build();

        Multi<AgentEvent> result;
        if (streamingChatModel != null) {
            StringBuilder buffer = new StringBuilder();
            result = AgentEventBridge.stream(streamingChatModel, request)
                .onItem().invoke(event -> {
                    if (event instanceof AgentEvent.TextDelta delta) {
                        buffer.append(delta.text());
                    }
                })
                .onCompletion().invoke(() ->
                    memory.add(AiMessage.from(buffer.toString())));
        } else {
            result = Multi.createFrom().item(() -> {
                ChatResponse response = chatModel.chat(request);
                String text = response.aiMessage().text();
                memory.add(AiMessage.from(text != null ? text : ""));
                return (AgentEvent) new AgentEvent.TextDelta(text != null ? text : "");
            });
        }

        return result.onTermination().invoke(
            () -> state.compareAndSet(State.ACTIVE, State.IDLE));
    }

    @Override
    public Uni<Void> interrupt() {
        return Uni.createFrom().voidItem();
    }

    @Override
    public void close(Duration maxWait) {
        State prev = state.getAndSet(State.CLOSED);
        if (prev != State.CLOSED) {
            semaphore.release();
        }
    }
}
