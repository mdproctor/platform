package io.casehub.platform.agent.langchain4j;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.ModelProvider;
import dev.langchain4j.model.chat.Capability;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.listener.ChatModelListener;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.ChatRequestParameters;
import dev.langchain4j.model.chat.request.DefaultChatRequestParameters;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.PartialThinking;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import dev.langchain4j.model.output.FinishReason;
import io.casehub.platform.agent.AgentEvent;
import io.casehub.platform.agent.AgentMcpServer;
import io.casehub.platform.agent.AgentSession;
import io.casehub.platform.agent.AgentSessionConfig;
import io.casehub.platform.agent.AgentSessionInit;
import io.casehub.platform.agent.AgentSessionLimitException;
import io.casehub.platform.agent.AgentTimeoutException;
import io.smallrye.mutiny.Multi;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ChatModelAgentProviderTest {

    static AgentLangchain4jProperties props(int maxSessions) {
        return new AgentLangchain4jProperties() {
            @Override public Duration closeTimeout() { return Duration.ofSeconds(30); }
            @Override public int sessionMemoryWindowSize() { return 20; }
            @Override public int maxConcurrentSessions() { return maxSessions; }
        };
    }

    static ChatModelAgentProvider create(ChatModel chatModel, int maxSessions) {
        StreamingChatModel streaming = (chatModel instanceof StreamingChatModel s) ? s : null;
        return new ChatModelAgentProvider(chatModel, streaming, props(maxSessions));
    }

    static ChatModelAgentProvider createDisabled(int maxSessions) {
        return new ChatModelAgentProvider(null, null, props(maxSessions));
    }

    @Test
    void invoke_happyPath_emitsTextDelta() {
        ChatModel chatModel = mock(ChatModel.class);
        when(chatModel.chat(any(ChatRequest.class))).thenReturn(
            ChatResponse.builder().aiMessage(AiMessage.from("Hello, World!")).build());
        var provider = create(chatModel, 10);

        List<AgentEvent> events = provider.invoke(AgentSessionConfig.of("", "test prompt"))
            .collect().asList().await().indefinitely();
        assertThat(events).hasSize(1);
        assertThat(((AgentEvent.TextDelta) events.get(0)).text()).isEqualTo("Hello, World!");
    }

    @Test
    void invoke_withSystemPrompt_includesSystemMessage() {
        ChatModel chatModel = mock(ChatModel.class);
        when(chatModel.chat(any(ChatRequest.class))).thenReturn(
            ChatResponse.builder().aiMessage(AiMessage.from("response")).build());
        var provider = create(chatModel, 10);

        provider.invoke(AgentSessionConfig.of("You are helpful", "What is 2+2?"))
            .collect().asList().await().indefinitely();
        verify(chatModel).chat(any(ChatRequest.class));
    }

    @Test
    void invoke_whenDisabled_returnsFailedMulti() {
        var provider = createDisabled(10);
        Multi<AgentEvent> result = provider.invoke(AgentSessionConfig.of("", "test"));
        assertThatThrownBy(() -> result.collect().asList().await().indefinitely())
            .hasMessageContaining("ChatModelAgentProvider is inactive");
    }

    @Test
    void openSession_returnsChatModelAgentSession() {
        ChatModel chatModel = mock(ChatModel.class);
        var provider = create(chatModel, 10);
        AgentSession session = provider.openSession(AgentSessionInit.of("system"));
        assertThat(session).isInstanceOf(ChatModelAgentSession.class);
        session.close(Duration.ofSeconds(1));
    }

    @Test
    void openSession_whenDisabled_throwsIllegalStateException() {
        var provider = createDisabled(10);
        assertThatThrownBy(() -> provider.openSession(AgentSessionInit.of("system")))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("ChatModelAgentProvider is inactive");
    }

    @Test
    void init_withZeroMaxConcurrentSessions_createsAlwaysPermitSemaphore() {
        ChatModel chatModel = mock(ChatModel.class);
        var provider = create(chatModel, 0);
        assertThat(provider.availablePermits()).isEqualTo(Integer.MAX_VALUE);
    }

    @Test
    void init_withNegativeMaxConcurrentSessions_throws() {
        ChatModel chatModel = mock(ChatModel.class);
        assertThatThrownBy(() -> create(chatModel, -1))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("max-concurrent-sessions must be >= 0");
    }

    @Test
    void invoke_withTimeout_failsWithAgentTimeoutException() {
        ChatModel slowModel = mock(ChatModel.class);
        when(slowModel.chat(any(ChatRequest.class))).thenAnswer(inv -> {
            Thread.sleep(500);
            return ChatResponse.builder().aiMessage(AiMessage.from("response")).build();
        });
        var provider = create(slowModel, 10);

        Multi<AgentEvent> result = provider.invoke(AgentSessionConfig.of("", "test", Duration.ofMillis(100)));
        assertThatThrownBy(() -> result.collect().asList().await().indefinitely())
            .isInstanceOf(AgentTimeoutException.class)
            .hasMessageContaining("PT0.1S");
    }

    @Test
    void invoke_withMcpServers_logsWarning() {
        ChatModel chatModel = mock(ChatModel.class);
        when(chatModel.chat(any(ChatRequest.class))).thenReturn(
            ChatResponse.builder().aiMessage(AiMessage.from("response")).build());
        var provider = create(chatModel, 10);

        AgentMcpServer.Stdio mcpServer = new AgentMcpServer.Stdio("npx", List.of("-y", "test"));
        AgentSessionConfig config = new AgentSessionConfig("", "test", List.of(mcpServer), null, null, null);
        provider.invoke(config).collect().asList().await().indefinitely();
    }

    @Test
    void invoke_withStreamingModel_emitsMultipleTextDeltas() {
        ChatModel dualModel = dualModel((request, handler) -> {
            handler.onPartialResponse("Hello");
            handler.onPartialResponse(" World");
            handler.onCompleteResponse(ChatResponse.builder()
                .aiMessage(AiMessage.from("Hello World"))
                .finishReason(FinishReason.STOP).build());
        });
        var provider = create(dualModel, 10);

        List<AgentEvent> events = provider.invoke(AgentSessionConfig.of("", "test"))
            .collect().asList().await().atMost(Duration.ofSeconds(5));
        assertThat(events).hasSize(2);
        assertThat(((AgentEvent.TextDelta) events.get(0)).text()).isEqualTo("Hello");
        assertThat(((AgentEvent.TextDelta) events.get(1)).text()).isEqualTo(" World");
    }

    @Test
    void invoke_withStreamingModel_emitsThinkingDelta() {
        ChatModel dualModel = dualModel((request, handler) -> {
            handler.onPartialThinking(new PartialThinking("thinking..."));
            handler.onPartialResponse("answer");
            handler.onCompleteResponse(ChatResponse.builder()
                .aiMessage(AiMessage.from("answer"))
                .finishReason(FinishReason.STOP).build());
        });
        var provider = create(dualModel, 10);

        List<AgentEvent> events = provider.invoke(AgentSessionConfig.of("", "test"))
            .collect().asList().await().atMost(Duration.ofSeconds(5));
        assertThat(events).hasSize(2);
        assertThat(events.get(0)).isInstanceOf(AgentEvent.ThinkingDelta.class);
        assertThat(events.get(1)).isInstanceOf(AgentEvent.TextDelta.class);
    }

    @Test
    void invoke_releasesPermitOnCompletion() {
        ChatModel chatModel = mock(ChatModel.class);
        when(chatModel.chat(any(ChatRequest.class))).thenReturn(
            ChatResponse.builder().aiMessage(AiMessage.from("response")).build());
        var provider = create(chatModel, 1);

        assertThat(provider.availablePermits()).isEqualTo(1);
        provider.invoke(AgentSessionConfig.of("", "test"))
            .collect().asList().await().indefinitely();
        assertThat(provider.availablePermits()).isEqualTo(1);
    }

    @Test
    void invoke_releasesPermitOnFailure() {
        ChatModel chatModel = mock(ChatModel.class);
        when(chatModel.chat(any(ChatRequest.class)))
            .thenThrow(new RuntimeException("model error"));
        var provider = create(chatModel, 1);

        assertThatThrownBy(() ->
            provider.invoke(AgentSessionConfig.of("", "test"))
                .collect().asList().await().indefinitely())
            .hasMessageContaining("model error");
        assertThat(provider.availablePermits()).isEqualTo(1);
    }

    @Test
    void openSession_close_releasesPermit() {
        ChatModel chatModel = mock(ChatModel.class);
        var provider = create(chatModel, 1);

        AgentSession session = provider.openSession(AgentSessionInit.of("system"));
        assertThat(provider.availablePermits()).isEqualTo(0);

        session.close(Duration.ofSeconds(1));
        assertThat(provider.availablePermits()).isEqualTo(1);

        AgentSession session2 = provider.openSession(AgentSessionInit.of("system"));
        session2.close(Duration.ofSeconds(1));
    }

    @FunctionalInterface
    interface StreamingDoChat {
        void accept(ChatRequest request, StreamingChatResponseHandler handler);
    }

    private static ChatModel dualModel(StreamingDoChat streaming) {
        return new DualModelImpl(streaming);
    }

    private static class DualModelImpl implements ChatModel, StreamingChatModel {
        private final StreamingDoChat streaming;
        DualModelImpl(StreamingDoChat streaming) { this.streaming = streaming; }

        @Override public ChatResponse doChat(ChatRequest request) {
            return ChatResponse.builder().aiMessage(AiMessage.from("")).build();
        }
        @Override public void doChat(ChatRequest request, StreamingChatResponseHandler handler) {
            streaming.accept(request, handler);
        }
        @Override public ModelProvider provider() { return ModelProvider.OTHER; }
        @Override public java.util.Set<Capability> supportedCapabilities() { return java.util.Set.of(); }
        @Override public ChatRequestParameters defaultRequestParameters() { return DefaultChatRequestParameters.EMPTY; }
        @Override public java.util.List<ChatModelListener> listeners() { return java.util.List.of(); }
    }
}
