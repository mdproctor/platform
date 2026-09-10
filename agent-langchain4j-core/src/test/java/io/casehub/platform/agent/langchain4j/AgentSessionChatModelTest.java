package io.casehub.platform.agent.langchain4j;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.ModelProvider;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.ResponseFormat;
import dev.langchain4j.model.chat.request.ResponseFormatType;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import dev.langchain4j.model.chat.request.json.JsonSchema;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.CompleteToolCall;
import dev.langchain4j.model.chat.response.PartialThinking;
import dev.langchain4j.model.chat.response.PartialToolCall;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import dev.langchain4j.model.output.FinishReason;
import io.casehub.platform.agent.AgentEvent;
import io.casehub.platform.agent.AgentSession;
import io.casehub.platform.agent.AgentTimeoutException;
import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.Uni;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.*;

class AgentSessionChatModelTest {

    // ── Helpers ──────────────────────────────────────────────────────────────

    /** Creates a fake AgentSession backed by a turn factory.
     *  Respects the IDLE/ACTIVE/CLOSED state machine: throws synchronously for CLOSED or ACTIVE. */
    static AgentSession fakeSession(Function<String, Multi<AgentEvent>> turnFactory) {
        return new AgentSession() {
            private enum State { IDLE, ACTIVE, CLOSED }
            private final AtomicReference<State> state = new AtomicReference<>(State.IDLE);

            @Override
            public Multi<AgentEvent> query(String prompt) {
                if (!state.compareAndSet(State.IDLE, State.ACTIVE)) {
                    State cur = state.get();
                    throw new IllegalStateException(cur == State.CLOSED
                        ? "session is closed"
                        : "a turn is already active");
                }
                return turnFactory.apply(prompt)
                    .onTermination().invoke(() -> state.compareAndSet(State.ACTIVE, State.IDLE));
            }

            @Override
            public Uni<Void> interrupt() { return Uni.createFrom().voidItem(); }

            @Override
            public void close(Duration maxWait) { state.set(State.CLOSED); }
        };
    }

    /** Creates a Multi that emits the given strings as TextDelta events then completes. */
    static Multi<AgentEvent> textDeltas(String... texts) {
        List<AgentEvent> events = new ArrayList<>();
        for (String t : texts) events.add(new AgentEvent.TextDelta(t));
        return Multi.createFrom().iterable(events);
    }

    /** Creates a ChatRequest with a single UserMessage. */
    static ChatRequest single(String userText) {
        return ChatRequest.builder().messages(List.of(UserMessage.from(userText))).build();
    }

    // ── Contract tests ────────────────────────────────────────────────────────

    @Test
    void wrap_returnsConcreteType() {
        AgentSessionChatModel model = AgentSessionChatModel.wrap(
            fakeSession(__ -> Multi.createFrom().empty()));
        assertThat(model).isInstanceOf(AgentSessionChatModel.class);
    }

    @Test
    void provider_returnsOTHER() {
        AgentSessionChatModel model = AgentSessionChatModel.wrap(
            fakeSession(__ -> Multi.createFrom().empty()));
        assertThat(model.provider()).isEqualTo(ModelProvider.OTHER);
    }

    @Test
    void supportedCapabilities_isEmpty() {
        AgentSessionChatModel model = AgentSessionChatModel.wrap(
            fakeSession(__ -> Multi.createFrom().empty()));
        assertThat(model.supportedCapabilities()).isEmpty();
    }

    @Test
    void listeners_returnsEmptyList() {
        AgentSessionChatModel model = AgentSessionChatModel.wrap(
            fakeSession(__ -> Multi.createFrom().empty()));
        assertThat(model.listeners()).isEmpty();
    }

    // ── Blocking doChat ───────────────────────────────────────────────────────

    @Test
    void doChat_blocking_happyPath_concatenatesDeltas() {
        AgentSessionChatModel model = AgentSessionChatModel.wrap(
            fakeSession(__ -> textDeltas("Hello", ", ", "world")));
        ChatResponse response = model.chat(single("hi"));
        assertThat(response.aiMessage().text()).isEqualTo("Hello, world");
        assertThat(response.finishReason()).isEqualTo(FinishReason.STOP);
    }

    @Test
    void doChat_blocking_singleDelta() {
        AgentSessionChatModel model = AgentSessionChatModel.wrap(
            fakeSession(__ -> textDeltas("single")));
        assertThat(model.chat(single("q")).aiMessage().text()).isEqualTo("single");
    }

    @Test
    void doChat_blocking_emptyResponse() {
        AgentSessionChatModel model = AgentSessionChatModel.wrap(
            fakeSession(__ -> Multi.createFrom().empty()));
        assertThat(model.chat(single("q")).aiMessage().text()).isEmpty();
    }

    @Test
    void doChat_blocking_agentTimeoutException_propagates() {
        AgentSession session = new AgentSession() {
            @Override public Multi<AgentEvent> query(String p) {
                return Multi.createFrom()
                    .failure(new AgentTimeoutException(Duration.ofSeconds(1)));
            }
            @Override public Uni<Void> interrupt() { return Uni.createFrom().voidItem(); }
            @Override public void close(Duration d) {}
        };
        AgentSessionChatModel model = AgentSessionChatModel.wrap(session);
        assertThatThrownBy(() -> model.chat(single("hi")))
            .isInstanceOf(AgentTimeoutException.class);
    }

    @Test
    void doChat_blocking_closedSession_throwsSynchronouslyBeforeAwait() {
        AgentSession session = fakeSession(__ -> Multi.createFrom().empty());
        session.close();
        AgentSessionChatModel model = AgentSessionChatModel.wrap(session);
        // Throws from session.query() — await() is never reached
        assertThatThrownBy(() -> model.doChat(single("hi")))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("session is closed");
    }

    @Test
    void doChat_blocking_multiTurn_sendsOnlyNewUserMessage() {
        List<String> received = new ArrayList<>();
        AgentSession session = new AgentSession() {
            @Override public Multi<AgentEvent> query(String p) {
                received.add(p);
                return Multi.createFrom().empty();
            }
            @Override public Uni<Void> interrupt() { return Uni.createFrom().voidItem(); }
            @Override public void close(Duration d) {}
        };
        AgentSessionChatModel model = AgentSessionChatModel.wrap(session);
        model.chat(single("first"));
        model.chat(single("second"));
        assertThat(received).containsExactly("first", "second");
    }

    // ── Validation ─────────────────────────────────────────────────────────────

    @Test
    void doChat_jsonFormat_prependsSchemaToUserMessage() {
        List<String> receivedPrompts = new ArrayList<>();
        AgentSessionChatModel model = AgentSessionChatModel.wrap(
            fakeSession(p -> { receivedPrompts.add(p); return textDeltas("{}"); }));
        ChatRequest request = ChatRequest.builder()
            .messages(List.of(UserMessage.from("hi")))
            .responseFormat(ResponseFormat.builder()
                .type(ResponseFormatType.JSON)
                .jsonSchema(JsonSchema.builder()
                    .name("TestSchema")
                    .rootElement(JsonObjectSchema.builder()
                        .addStringProperty("field", "a field")
                        .required("field")
                        .build())
                    .build())
                .build())
            .build();
        model.doChat(request);
        assertThat(receivedPrompts).hasSize(1);
        String prompt = receivedPrompts.get(0);
        assertThat(prompt).startsWith("Respond with JSON matching schema \"TestSchema\":");
        assertThat(prompt).contains("\"field\": string (required)");
        assertThat(prompt).endsWith("\n\nhi");
    }

    @Test
    void doChat_systemMessageWithUserMessage_throwsIllegalArgument() {
        AgentSessionChatModel model = AgentSessionChatModel.wrap(
            fakeSession(__ -> Multi.createFrom().empty()));
        ChatRequest request = ChatRequest.builder()
            .messages(List.of(SystemMessage.from("be helpful"), UserMessage.from("hi")))
            .build();
        assertThatThrownBy(() -> model.doChat(request))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("SystemMessage");
    }

    @Test
    void doChat_streaming_systemMessageWithUserMessage_throwsSynchronously() {
        AgentSessionChatModel model = AgentSessionChatModel.wrap(
            fakeSession(__ -> Multi.createFrom().empty()));
        ChatRequest request = ChatRequest.builder()
            .messages(List.of(SystemMessage.from("be helpful"), UserMessage.from("hi")))
            .build();
        assertThatThrownBy(() ->
            model.doChat(request, new StreamingChatResponseHandler() {
                @Override public void onPartialResponse(String t) {}
                @Override public void onCompleteResponse(ChatResponse r) {}
                @Override public void onError(Throwable t) {}
            })
        ).isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("SystemMessage");
    }

    @Test
    void doChat_aiMessagePresent_throwsIllegalArgument() {
        AgentSessionChatModel model = AgentSessionChatModel.wrap(
            fakeSession(__ -> Multi.createFrom().empty()));
        ChatRequest request = ChatRequest.builder()
            .messages(List.of(UserMessage.from("hello"), AiMessage.from("reply"), UserMessage.from("bye")))
            .build();
        assertThatThrownBy(() -> model.doChat(request))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("AiMessage");
    }

    @Test
    void doChat_multipleUserMessages_throwsIllegalArgument() {
        AgentSessionChatModel model = AgentSessionChatModel.wrap(
            fakeSession(__ -> Multi.createFrom().empty()));
        ChatRequest request = ChatRequest.builder()
            .messages(List.of(UserMessage.from("first"), UserMessage.from("second")))
            .build();
        assertThatThrownBy(() -> model.doChat(request))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("exactly one UserMessage");
    }

    @Test
    void doChat_noUserMessage_throwsIllegalArgument() {
        AgentSessionChatModel model = AgentSessionChatModel.wrap(
            fakeSession(__ -> Multi.createFrom().empty()));
        ChatRequest request = ChatRequest.builder()
            .messages(List.of(SystemMessage.from("system only")))
            .build();
        assertThatThrownBy(() -> model.doChat(request))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void doChat_multimodalUserMessage_throwsIllegalArgument() {
        AgentSessionChatModel model = AgentSessionChatModel.wrap(
            fakeSession(__ -> Multi.createFrom().empty()));
        // Two TextContent items — singleText() throws RuntimeException for non-single content
        ChatRequest request = ChatRequest.builder()
            .messages(List.of(UserMessage.from(
                dev.langchain4j.data.message.TextContent.from("text1"),
                dev.langchain4j.data.message.TextContent.from("text2"))))
            .build();
        assertThatThrownBy(() -> model.doChat(request))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("multimodal");
    }

    // ── Streaming doChat ──────────────────────────────────────────────────────

    @Test
    void doChat_streaming_happyPath() {
        AgentSessionChatModel model = AgentSessionChatModel.wrap(
            fakeSession(__ -> textDeltas("tok1", "tok2", "tok3")));

        List<String> partials = new ArrayList<>();
        ChatResponse[] completed = {null};

        model.chat(single("hi"), new StreamingChatResponseHandler() {
            @Override public void onPartialResponse(String t) { partials.add(t); }
            @Override public void onCompleteResponse(ChatResponse r) { completed[0] = r; }
            @Override public void onError(Throwable t) { throw new RuntimeException(t); }
        });

        await().untilAsserted(() -> assertThat(completed[0]).isNotNull());
        assertThat(partials).containsExactly("tok1", "tok2", "tok3");
        assertThat(completed[0].aiMessage().text()).isEqualTo("tok1tok2tok3");
        assertThat(completed[0].finishReason()).isEqualTo(FinishReason.STOP);
    }

    @Test
    void doChat_streaming_sessionNotClosed_afterCompletion() throws InterruptedException {
        AtomicBoolean closeCalled = new AtomicBoolean(false);
        AgentSession session = new AgentSession() {
            @Override public Multi<AgentEvent> query(String p) { return Multi.createFrom().empty(); }
            @Override public Uni<Void> interrupt() { return Uni.createFrom().voidItem(); }
            @Override public void close(Duration d) { closeCalled.set(true); }
        };
        AgentSessionChatModel model = AgentSessionChatModel.wrap(session);
        AtomicBoolean done = new AtomicBoolean(false);
        model.chat(single("hi"), new StreamingChatResponseHandler() {
            @Override public void onPartialResponse(String t) {}
            @Override public void onCompleteResponse(ChatResponse r) { done.set(true); }
            @Override public void onError(Throwable t) {}
        });
        await().until(done::get);
        // Adapter must never close a session it doesn't own
        assertThat(closeCalled.get()).isFalse();
    }

    @Test
    void doChat_streaming_closedSession_throwsSynchronously() {
        AgentSession session = fakeSession(__ -> Multi.createFrom().empty());
        session.close();
        AgentSessionChatModel model = AgentSessionChatModel.wrap(session);
        assertThatThrownBy(() ->
            model.doChat(single("hi"), new StreamingChatResponseHandler() {
                @Override public void onPartialResponse(String t) {}
                @Override public void onCompleteResponse(ChatResponse r) {}
                @Override public void onError(Throwable t) {}
            })
        ).isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("session is closed");
    }

    @Test
    void doChat_streaming_alreadyActive_throwsSynchronously() {
        // Session that never completes (holds ACTIVE state)
        AgentSession session = new AgentSession() {
            private final AtomicReference<Boolean> active = new AtomicReference<>(false);
            @Override public Multi<AgentEvent> query(String p) {
                if (!active.compareAndSet(false, true)) {
                    throw new IllegalStateException("a turn is already active");
                }
                return Multi.createFrom().nothing(); // never completes
            }
            @Override public Uni<Void> interrupt() { return Uni.createFrom().voidItem(); }
            @Override public void close(Duration d) {}
        };
        AgentSessionChatModel model = AgentSessionChatModel.wrap(session);
        // Start a turn that never completes
        model.chat(single("first"), new StreamingChatResponseHandler() {
            @Override public void onPartialResponse(String t) {}
            @Override public void onCompleteResponse(ChatResponse r) {}
            @Override public void onError(Throwable t) {}
        });
        // Second call throws synchronously
        assertThatThrownBy(() ->
            model.doChat(single("second"), new StreamingChatResponseHandler() {
                @Override public void onPartialResponse(String t) {}
                @Override public void onCompleteResponse(ChatResponse r) {}
                @Override public void onError(Throwable t) {}
            })
        ).isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("a turn is already active");
    }

    @Test
    void doChat_streaming_jsonFormat_prependsSchemaToUserMessage() {
        List<String> receivedPrompts = new ArrayList<>();
        AgentSessionChatModel model = AgentSessionChatModel.wrap(
            fakeSession(p -> { receivedPrompts.add(p); return textDeltas("{}"); }));
        ChatRequest request = ChatRequest.builder()
            .messages(List.of(UserMessage.from("hi")))
            .responseFormat(ResponseFormat.builder()
                .type(ResponseFormatType.JSON)
                .jsonSchema(JsonSchema.builder()
                    .name("TestSchema")
                    .rootElement(JsonObjectSchema.builder()
                        .addStringProperty("field", "a field")
                        .required("field")
                        .build())
                    .build())
                .build())
            .build();
        AtomicBoolean done = new AtomicBoolean(false);
        model.doChat(request, new StreamingChatResponseHandler() {
            @Override public void onPartialResponse(String t) {}
            @Override public void onCompleteResponse(ChatResponse r) { done.set(true); }
            @Override public void onError(Throwable t) {}
        });
        await().until(done::get);
        assertThat(receivedPrompts).hasSize(1);
        String prompt = receivedPrompts.get(0);
        assertThat(prompt).startsWith("Respond with JSON matching schema \"TestSchema\":");
        assertThat(prompt).contains("\"field\": string (required)");
        assertThat(prompt).endsWith("\n\nhi");
    }

    @Test
    void doChat_streaming_aiMessagePresent_throwsSynchronously() {
        AgentSessionChatModel model = AgentSessionChatModel.wrap(
            fakeSession(__ -> Multi.createFrom().empty()));
        ChatRequest request = ChatRequest.builder()
            .messages(List.of(UserMessage.from("hi"), AiMessage.from("reply"), UserMessage.from("bye")))
            .build();
        assertThatThrownBy(() ->
            model.doChat(request, new StreamingChatResponseHandler() {
                @Override public void onPartialResponse(String t) {}
                @Override public void onCompleteResponse(ChatResponse r) {}
                @Override public void onError(Throwable t) {}
            })
        ).isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("AiMessage");
    }

    @Test
    void doChat_streaming_multipleUserMessages_throwsSynchronously() {
        AgentSessionChatModel model = AgentSessionChatModel.wrap(
            fakeSession(__ -> Multi.createFrom().empty()));
        ChatRequest request = ChatRequest.builder()
            .messages(List.of(UserMessage.from("first"), UserMessage.from("second")))
            .build();
        assertThatThrownBy(() ->
            model.doChat(request, new StreamingChatResponseHandler() {
                @Override public void onPartialResponse(String t) {}
                @Override public void onCompleteResponse(ChatResponse r) {}
                @Override public void onError(Throwable t) {}
            })
        ).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void doChat_streaming_agentTimeoutException_routesToHandlerError() {
        AgentSession session = new AgentSession() {
            @Override public Multi<AgentEvent> query(String p) {
                return Multi.createFrom().failure(new AgentTimeoutException(Duration.ofSeconds(1)));
            }
            @Override public Uni<Void> interrupt() { return Uni.createFrom().voidItem(); }
            @Override public void close(Duration d) {}
        };
        AtomicReference<Throwable> capturedError = new AtomicReference<>();
        AgentSessionChatModel.wrap(session).chat(single("q"), new StreamingChatResponseHandler() {
            @Override public void onPartialResponse(String t) {}
            @Override public void onCompleteResponse(ChatResponse r) {}
            @Override public void onError(Throwable t) { capturedError.set(t); }
        });
        await().untilAsserted(() -> assertThat(capturedError.get()).isInstanceOf(AgentTimeoutException.class));
    }

    @Test
    void doChat_streaming_forwardsThinkingDelta() {
        AgentSessionChatModel model = AgentSessionChatModel.wrap(
            fakeSession(__ -> Multi.createFrom().items(
                new AgentEvent.ThinkingDelta("step 1"),
                new AgentEvent.TextDelta("answer")
            )));
        List<String> thinkings = new ArrayList<>();
        ChatResponse[] completed = {null};

        model.chat(single("q"), new StreamingChatResponseHandler() {
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
        AgentSessionChatModel model = AgentSessionChatModel.wrap(
            fakeSession(__ -> Multi.createFrom().items(
                new AgentEvent.ToolCallDelta(0, "call_1", "search", "{\"q"),
                new AgentEvent.TextDelta("done")
            )));
        List<PartialToolCall> partials = new ArrayList<>();
        ChatResponse[] completed = {null};

        model.chat(single("q"), new StreamingChatResponseHandler() {
            @Override public void onPartialResponse(String t) {}
            @Override public void onPartialToolCall(PartialToolCall p) { partials.add(p); }
            @Override public void onCompleteResponse(ChatResponse r) { completed[0] = r; }
            @Override public void onError(Throwable t) { throw new RuntimeException(t); }
        });

        await().untilAsserted(() -> assertThat(completed[0]).isNotNull());
        assertThat(partials).hasSize(1);
        assertThat(partials.get(0).name()).isEqualTo("search");
    }

    @Test
    void doChat_streaming_forwardsToolCallComplete() {
        AgentSessionChatModel model = AgentSessionChatModel.wrap(
            fakeSession(__ -> Multi.createFrom().items(
                new AgentEvent.ToolCallComplete(0, "call_1", "search", "{\"query\":\"test\"}"),
                new AgentEvent.TextDelta("done")
            )));
        List<CompleteToolCall> completes = new ArrayList<>();
        ChatResponse[] completed = {null};

        model.chat(single("q"), new StreamingChatResponseHandler() {
            @Override public void onPartialResponse(String t) {}
            @Override public void onCompleteToolCall(CompleteToolCall c) { completes.add(c); }
            @Override public void onCompleteResponse(ChatResponse r) { completed[0] = r; }
            @Override public void onError(Throwable t) { throw new RuntimeException(t); }
        });

        await().untilAsserted(() -> assertThat(completed[0]).isNotNull());
        assertThat(completes).hasSize(1);
        assertThat(completes.get(0).toolExecutionRequest().name()).isEqualTo("search");
    }

    @Test
    void doChat_streaming_toolResultSilentlyIgnored() {
        AgentSessionChatModel model = AgentSessionChatModel.wrap(
            fakeSession(__ -> Multi.createFrom().items(
                new AgentEvent.ToolResult("call_1", "output", false),
                new AgentEvent.TextDelta("text")
            )));
        List<String> partials = new ArrayList<>();
        ChatResponse[] completed = {null};

        model.chat(single("q"), new StreamingChatResponseHandler() {
            @Override public void onPartialResponse(String t) { partials.add(t); }
            @Override public void onCompleteResponse(ChatResponse r) { completed[0] = r; }
            @Override public void onError(Throwable t) { throw new RuntimeException(t); }
        });

        await().untilAsserted(() -> assertThat(completed[0]).isNotNull());
        assertThat(partials).containsExactly("text");
    }
}
