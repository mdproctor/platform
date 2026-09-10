package io.casehub.platform.agent.langchain4j;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.ModelProvider;
import dev.langchain4j.model.chat.listener.ChatModelListener;
import dev.langchain4j.model.chat.listener.ChatModelRequestContext;
import dev.langchain4j.model.chat.listener.ChatModelResponseContext;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.ResponseFormat;
import dev.langchain4j.model.chat.request.ResponseFormatType;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import dev.langchain4j.model.chat.request.json.JsonSchema;
import dev.langchain4j.model.chat.request.json.JsonStringSchema;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.CompleteToolCall;
import dev.langchain4j.model.chat.response.PartialThinking;
import dev.langchain4j.model.chat.response.PartialToolCall;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import dev.langchain4j.model.output.FinishReason;
import io.casehub.platform.agent.AgentEvent;
import io.casehub.platform.agent.AgentProvider;
import io.casehub.platform.agent.AgentSessionConfig;
import io.casehub.platform.agent.AgentTimeoutException;
import io.smallrye.mutiny.Multi;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AgentProviderChatModelTest {

    // ── Helpers ───────────────────────────────────────────────────────────────

    static List<ChatModelListener> emptyListeners() {
        return List.of();
    }

    static List<ChatModelListener> singleListener(ChatModelListener listener) {
        return List.of(listener);
    }

    static AgentLangchain4jProperties testProps() {
        return new AgentLangchain4jProperties() {
            @Override public Duration closeTimeout() { return Duration.ofSeconds(5); }
            @Override public int sessionMemoryWindowSize() { return 20; }
            @Override public int maxConcurrentSessions() { return 10; }
        };
    }

    static Multi<AgentEvent> textDeltas(String... texts) {
        List<AgentEvent> events = new ArrayList<>();
        for (String t : texts) events.add(new AgentEvent.TextDelta(t));
        return Multi.createFrom().iterable(events);
    }

    static AgentProvider fakeProvider(Multi<AgentEvent> invokeResult) {
        return new AgentProvider() {
            @Override public Multi<AgentEvent> invoke(AgentSessionConfig c) { return invokeResult; }
            @Override public io.casehub.platform.agent.AgentSession openSession(io.casehub.platform.agent.AgentSessionInit init) {
                throw new UnsupportedOperationException("openSession not used in invoke-based tests");
            }
        };
    }

    static ChatRequest single(String text) {
        return ChatRequest.builder().messages(List.of(UserMessage.from(text))).build();
    }

    static ChatRequest withSystem(String system, String user) {
        return ChatRequest.builder()
            .messages(List.of(SystemMessage.from(system), UserMessage.from(user)))
            .build();
    }

    AgentProviderChatModel model(AgentProvider provider) {
        return new AgentProviderChatModel(provider, emptyListeners(), testProps());
    }

    // ── Contract ──────────────────────────────────────────────────────────────

    @Test
    void provider_returnsOTHER() {
        assertThat(model(fakeProvider(Multi.createFrom().empty())).provider())
            .isEqualTo(ModelProvider.OTHER);
    }

    @Test
    void supportedCapabilities_isEmpty() {
        assertThat(model(fakeProvider(Multi.createFrom().empty())).supportedCapabilities())
            .isEmpty();
    }

    @Test
    void listeners_returnsInjectedListeners() {
        ChatModelListener mock = mock(ChatModelListener.class);
        AgentProviderChatModel m = new AgentProviderChatModel(
            fakeProvider(Multi.createFrom().empty()),
            singleListener(mock),
            testProps());
        assertThat(m.listeners()).containsExactly(mock);
    }

    // ── Blocking doChat ───────────────────────────────────────────────────────

    @Test
    void doChat_blocking_happyPath() {
        AgentProviderChatModel m = model(fakeProvider(textDeltas("Hello", " world")));
        ChatResponse response = m.chat(single("hi"));
        assertThat(response.aiMessage().text()).isEqualTo("Hello world");
        assertThat(response.finishReason()).isEqualTo(FinishReason.STOP);
    }

    @Test
    void doChat_blocking_systemPromptPassedToConfig() {
        List<String> capturedSystems = new ArrayList<>();
        AgentProvider provider = new AgentProvider() {
            @Override public Multi<AgentEvent> invoke(AgentSessionConfig c) {
                capturedSystems.add(c.systemPrompt());
                return textDeltas("ok");
            }
            @Override public io.casehub.platform.agent.AgentSession openSession(io.casehub.platform.agent.AgentSessionInit init) {
                throw new UnsupportedOperationException();
            }
        };
        model(provider).chat(withSystem("You are helpful", "query"));
        assertThat(capturedSystems).containsExactly("You are helpful");
    }

    @Test
    void doChat_blocking_noSystemMessage_usesEmptyPrompt() {
        List<String> capturedSystems = new ArrayList<>();
        AgentProvider provider = new AgentProvider() {
            @Override public Multi<AgentEvent> invoke(AgentSessionConfig c) {
                capturedSystems.add(c.systemPrompt());
                return textDeltas("ok");
            }
            @Override public io.casehub.platform.agent.AgentSession openSession(io.casehub.platform.agent.AgentSessionInit init) {
                throw new UnsupportedOperationException();
            }
        };
        model(provider).chat(single("just a user message"));
        assertThat(capturedSystems).containsExactly("");
    }

    @Test
    void doChat_blocking_agentTimeoutException_propagates() {
        AgentProvider provider = new AgentProvider() {
            @Override public Multi<AgentEvent> invoke(AgentSessionConfig c) {
                return Multi.createFrom().failure(new AgentTimeoutException(Duration.ofSeconds(1)));
            }
            @Override public io.casehub.platform.agent.AgentSession openSession(io.casehub.platform.agent.AgentSessionInit init) {
                throw new UnsupportedOperationException();
            }
        };
        assertThatThrownBy(() -> model(provider).chat(single("hi")))
            .isInstanceOf(AgentTimeoutException.class);
    }

    @Test
    void doChat_blocking_noUserMessage_throws() {
        AgentProviderChatModel m = model(fakeProvider(Multi.createFrom().empty()));
        ChatRequest request = ChatRequest.builder()
            .messages(List.of(SystemMessage.from("system only")))
            .build();
        assertThatThrownBy(() -> m.doChat(request)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void doChat_blocking_multimodalUserMessage_throws() {
        AgentProviderChatModel m = model(fakeProvider(Multi.createFrom().empty()));
        ChatRequest request = ChatRequest.builder()
            .messages(List.of(UserMessage.from(
                dev.langchain4j.data.message.TextContent.from("text1"),
                dev.langchain4j.data.message.TextContent.from("text2"))))
            .build();
        assertThatThrownBy(() -> m.doChat(request))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("multimodal");
    }

    @Test
    void doChat_blocking_aiMessagePresent_throws() {
        AgentProviderChatModel m = model(fakeProvider(Multi.createFrom().empty()));
        ChatRequest request = ChatRequest.builder()
            .messages(List.of(UserMessage.from("hi"), AiMessage.from("reply"), UserMessage.from("bye")))
            .build();
        assertThatThrownBy(() -> m.doChat(request)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void doChat_blocking_multipleUserMessages_throws() {
        AgentProviderChatModel m = model(fakeProvider(Multi.createFrom().empty()));
        ChatRequest request = ChatRequest.builder()
            .messages(List.of(UserMessage.from("one"), UserMessage.from("two")))
            .build();
        assertThatThrownBy(() -> m.doChat(request)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void doChat_blocking_jsonFormat_prependsSchema() {
        List<String> capturedUserPrompts = new ArrayList<>();
        AgentProvider provider = new AgentProvider() {
            @Override public Multi<AgentEvent> invoke(AgentSessionConfig c) {
                capturedUserPrompts.add(c.userPrompt());
                return textDeltas("{\"name\":\"test\"}");
            }
            @Override public io.casehub.platform.agent.AgentSession openSession(io.casehub.platform.agent.AgentSessionInit init) {
                throw new UnsupportedOperationException();
            }
        };
        JsonSchema schema = JsonSchema.builder()
            .name("Person")
            .rootElement(JsonObjectSchema.builder()
                .addStringProperty("name", "person's name")
                .required("name")
                .build())
            .build();
        ChatRequest request = ChatRequest.builder()
            .messages(List.of(UserMessage.from("give me a person")))
            .responseFormat(ResponseFormat.builder()
                .type(ResponseFormatType.JSON)
                .jsonSchema(schema)
                .build())
            .build();
        ChatResponse response = model(provider).chat(request);
        assertThat(capturedUserPrompts).hasSize(1);
        String prompt = capturedUserPrompts.get(0);
        assertThat(prompt).contains("Respond with JSON matching schema \"Person\"");
        assertThat(prompt).contains("\"name\": string (required)");
        assertThat(prompt).contains("give me a person");
        assertThat(response.aiMessage().text()).isEqualTo("{\"name\":\"test\"}");
    }

    // ── Streaming doChat ──────────────────────────────────────────────────────

    @Test
    void doChat_streaming_happyPath() {
        AgentProviderChatModel m = model(fakeProvider(textDeltas("a", "b", "c")));
        List<String> partials = new ArrayList<>();
        ChatResponse[] completed = {null};

        m.chat(single("q"), new StreamingChatResponseHandler() {
            @Override public void onPartialResponse(String t) { partials.add(t); }
            @Override public void onCompleteResponse(ChatResponse r) { completed[0] = r; }
            @Override public void onError(Throwable t) { throw new RuntimeException(t); }
        });

        await().untilAsserted(() -> assertThat(completed[0]).isNotNull());
        assertThat(partials).containsExactly("a", "b", "c");
        assertThat(completed[0].aiMessage().text()).isEqualTo("abc");
        assertThat(completed[0].finishReason()).isEqualTo(FinishReason.STOP);
    }

    @Test
    void doChat_streaming_errorRoutesToHandler() {
        AgentProvider provider = new AgentProvider() {
            @Override public Multi<AgentEvent> invoke(AgentSessionConfig c) {
                return Multi.createFrom().failure(new RuntimeException("error"));
            }
            @Override public io.casehub.platform.agent.AgentSession openSession(io.casehub.platform.agent.AgentSessionInit init) {
                throw new UnsupportedOperationException();
            }
        };
        AtomicBoolean errorReceived = new AtomicBoolean(false);
        model(provider).chat(single("q"), new StreamingChatResponseHandler() {
            @Override public void onPartialResponse(String t) {}
            @Override public void onCompleteResponse(ChatResponse r) {}
            @Override public void onError(Throwable t) { errorReceived.set(true); }
        });
        await().until(errorReceived::get);
    }

    @Test
    void doChat_streaming_jsonFormat_prependsSchema() {
        List<String> capturedUserPrompts = new ArrayList<>();
        AgentProvider provider = new AgentProvider() {
            @Override public Multi<AgentEvent> invoke(AgentSessionConfig c) {
                capturedUserPrompts.add(c.userPrompt());
                return textDeltas("{\"age\":", "42", "}");
            }
            @Override public io.casehub.platform.agent.AgentSession openSession(io.casehub.platform.agent.AgentSessionInit init) {
                throw new UnsupportedOperationException();
            }
        };
        JsonSchema schema = JsonSchema.builder()
            .name("Data")
            .rootElement(JsonObjectSchema.builder()
                .addNumberProperty("age", "person's age")
                .build())
            .build();
        ChatRequest request = ChatRequest.builder()
            .messages(List.of(UserMessage.from("give me data")))
            .responseFormat(ResponseFormat.builder()
                .type(ResponseFormatType.JSON)
                .jsonSchema(schema)
                .build())
            .build();
        ChatResponse[] completed = {null};
        model(provider).chat(request, new StreamingChatResponseHandler() {
            @Override public void onPartialResponse(String t) {}
            @Override public void onCompleteResponse(ChatResponse r) { completed[0] = r; }
            @Override public void onError(Throwable t) {}
        });
        await().untilAsserted(() -> assertThat(completed[0]).isNotNull());
        assertThat(capturedUserPrompts).hasSize(1);
        String prompt = capturedUserPrompts.get(0);
        assertThat(prompt).contains("Respond with JSON matching schema \"Data\"");
        assertThat(prompt).contains("\"age\": number");
        assertThat(prompt).contains("give me data");
        assertThat(completed[0].aiMessage().text()).isEqualTo("{\"age\":42}");
    }

    @Test
    void doChat_streaming_agentTimeoutException_routesToHandlerError() {
        AgentProvider provider = new AgentProvider() {
            @Override public Multi<AgentEvent> invoke(AgentSessionConfig c) {
                return Multi.createFrom().failure(new AgentTimeoutException(Duration.ofSeconds(1)));
            }
            @Override public io.casehub.platform.agent.AgentSession openSession(io.casehub.platform.agent.AgentSessionInit init) {
                throw new UnsupportedOperationException();
            }
        };
        AtomicReference<Throwable> capturedError = new AtomicReference<>();
        model(provider).chat(single("q"), new StreamingChatResponseHandler() {
            @Override public void onPartialResponse(String t) {}
            @Override public void onCompleteResponse(ChatResponse r) {}
            @Override public void onError(Throwable t) { capturedError.set(t); }
        });
        await().until(() -> capturedError.get() != null);
        assertThat(capturedError.get()).isInstanceOf(AgentTimeoutException.class);
    }

    @Test
    void doChat_streaming_aiMessagePresent_throwsSynchronously() {
        AgentProviderChatModel m = model(fakeProvider(Multi.createFrom().empty()));
        ChatRequest request = ChatRequest.builder()
            .messages(List.of(UserMessage.from("hi"), AiMessage.from("reply"), UserMessage.from("bye")))
            .build();
        assertThatThrownBy(() ->
            m.doChat(request, new StreamingChatResponseHandler() {
                @Override public void onPartialResponse(String t) {}
                @Override public void onCompleteResponse(ChatResponse r) {}
                @Override public void onError(Throwable t) {}
            })
        ).isInstanceOf(IllegalArgumentException.class);
    }

    // ── Listener telemetry ────────────────────────────────────────────────────

    @Test
    void doChat_listenerEmpty_noTelemetryFired() {
        AgentProviderChatModel m = new AgentProviderChatModel(
            fakeProvider(textDeltas("ok")),
            emptyListeners(),
            testProps());
        assertThatNoException().isThrownBy(() -> m.chat(single("hi")));
    }

    @Test
    void doChat_listenerPresent_onRequestAndOnResponseCalled() {
        ChatModelListener listener = mock(ChatModelListener.class);
        AgentProviderChatModel m = new AgentProviderChatModel(
            fakeProvider(textDeltas("response text")),
            singleListener(listener),
            testProps());
        m.chat(single("hello"));
        verify(listener).onRequest(any(ChatModelRequestContext.class));
        verify(listener).onResponse(any(ChatModelResponseContext.class));
        verifyNoMoreInteractions(listener);
    }

    @Test
    void doChat_streaming_forwardsThinkingDelta() {
        AgentProviderChatModel m = model(fakeProvider(
            Multi.createFrom().items(
                new AgentEvent.ThinkingDelta("step 1"),
                new AgentEvent.TextDelta("answer")
            )));
        List<String> thinkings = new ArrayList<>();
        ChatResponse[] completed = {null};

        m.chat(single("q"), new StreamingChatResponseHandler() {
            @Override public void onPartialResponse(String t) {}
            @Override public void onPartialThinking(PartialThinking t) { thinkings.add(t.text()); }
            @Override public void onCompleteResponse(ChatResponse r) { completed[0] = r; }
            @Override public void onError(Throwable t) { throw new RuntimeException(t); }
        });

        await().untilAsserted(() -> assertThat(completed[0]).isNotNull());
        assertThat(thinkings).containsExactly("step 1");
    }

    @Test
    void doChat_streaming_forwardsToolCallDelta() {
        AgentProviderChatModel m = model(fakeProvider(
            Multi.createFrom().items(
                new AgentEvent.ToolCallDelta(0, "call_1", "search", "{\"q"),
                new AgentEvent.TextDelta("done")
            )));
        List<PartialToolCall> partials = new ArrayList<>();
        ChatResponse[] completed = {null};

        m.chat(single("q"), new StreamingChatResponseHandler() {
            @Override public void onPartialResponse(String t) {}
            @Override public void onPartialToolCall(PartialToolCall p) { partials.add(p); }
            @Override public void onCompleteResponse(ChatResponse r) { completed[0] = r; }
            @Override public void onError(Throwable t) { throw new RuntimeException(t); }
        });

        await().untilAsserted(() -> assertThat(completed[0]).isNotNull());
        assertThat(partials).hasSize(1);
        assertThat(partials.get(0).index()).isZero();
        assertThat(partials.get(0).id()).isEqualTo("call_1");
        assertThat(partials.get(0).name()).isEqualTo("search");
        assertThat(partials.get(0).partialArguments()).isEqualTo("{\"q");
    }

    @Test
    void doChat_streaming_forwardsToolCallComplete() {
        AgentProviderChatModel m = model(fakeProvider(
            Multi.createFrom().items(
                new AgentEvent.ToolCallComplete(0, "call_1", "search", "{\"query\":\"test\"}"),
                new AgentEvent.TextDelta("done")
            )));
        List<CompleteToolCall> completes = new ArrayList<>();
        ChatResponse[] completed = {null};

        m.chat(single("q"), new StreamingChatResponseHandler() {
            @Override public void onPartialResponse(String t) {}
            @Override public void onCompleteToolCall(CompleteToolCall c) { completes.add(c); }
            @Override public void onCompleteResponse(ChatResponse r) { completed[0] = r; }
            @Override public void onError(Throwable t) { throw new RuntimeException(t); }
        });

        await().untilAsserted(() -> assertThat(completed[0]).isNotNull());
        assertThat(completes).hasSize(1);
        assertThat(completes.get(0).index()).isZero();
        assertThat(completes.get(0).toolExecutionRequest().id()).isEqualTo("call_1");
        assertThat(completes.get(0).toolExecutionRequest().name()).isEqualTo("search");
        assertThat(completes.get(0).toolExecutionRequest().arguments()).isEqualTo("{\"query\":\"test\"}");
    }

    @Test
    void doChat_streaming_toolResultSilentlyIgnored() {
        AgentProviderChatModel m = model(fakeProvider(
            Multi.createFrom().items(
                new AgentEvent.ToolResult("call_1", "output", false),
                new AgentEvent.TextDelta("text")
            )));
        List<String> partials = new ArrayList<>();
        ChatResponse[] completed = {null};

        m.chat(single("q"), new StreamingChatResponseHandler() {
            @Override public void onPartialResponse(String t) { partials.add(t); }
            @Override public void onCompleteResponse(ChatResponse r) { completed[0] = r; }
            @Override public void onError(Throwable t) { throw new RuntimeException(t); }
        });

        await().untilAsserted(() -> assertThat(completed[0]).isNotNull());
        assertThat(partials).containsExactly("text");
    }
}
