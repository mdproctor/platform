package io.casehub.platform.agent.langchain4j;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.CompleteToolCall;
import dev.langchain4j.model.chat.response.PartialThinking;
import dev.langchain4j.model.chat.response.PartialToolCall;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import dev.langchain4j.model.chat.response.StreamingHandle;
import dev.langchain4j.model.chat.response.PartialResponse;
import dev.langchain4j.model.chat.response.PartialResponseContext;
import dev.langchain4j.model.output.FinishReason;
import io.casehub.platform.agent.AgentEvent;
import io.smallrye.mutiny.Multi;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AgentEventBridgeTest {

    private static final ChatRequest SIMPLE_REQUEST = ChatRequest.builder()
        .messages(UserMessage.from("hello")).build();

    @Test
    void stream_textDelta_mapsToTextDeltaEvents() {
        StreamingChatModel model = streamingModel((request, handler) -> {
            handler.onPartialResponse("Hello");
            handler.onPartialResponse(" World");
            handler.onCompleteResponse(ChatResponse.builder()
                .aiMessage(AiMessage.from("Hello World"))
                .finishReason(FinishReason.STOP).build());
        });

        List<AgentEvent> events = AgentEventBridge.stream(model, SIMPLE_REQUEST)
            .collect().asList().await().atMost(Duration.ofSeconds(5));

        assertThat(events).hasSize(2);
        assertThat(events.get(0)).isEqualTo(new AgentEvent.TextDelta("Hello"));
        assertThat(events.get(1)).isEqualTo(new AgentEvent.TextDelta(" World"));
    }

    @Test
    void stream_thinkingDelta_mapsToThinkingDeltaEvents() {
        StreamingChatModel model = streamingModel((request, handler) -> {
            handler.onPartialThinking(new PartialThinking("Let me think"));
            handler.onPartialResponse("Answer");
            handler.onCompleteResponse(ChatResponse.builder()
                .aiMessage(AiMessage.from("Answer"))
                .finishReason(FinishReason.STOP).build());
        });

        List<AgentEvent> events = AgentEventBridge.stream(model, SIMPLE_REQUEST)
            .collect().asList().await().atMost(Duration.ofSeconds(5));

        assertThat(events).hasSize(2);
        assertThat(events.get(0)).isEqualTo(new AgentEvent.ThinkingDelta("Let me think"));
        assertThat(events.get(1)).isEqualTo(new AgentEvent.TextDelta("Answer"));
    }

    @Test
    void stream_toolCallDelta_mapsToToolCallDeltaEvent() {
        StreamingChatModel model = streamingModel((request, handler) -> {
            handler.onPartialToolCall(PartialToolCall.builder()
                .index(0).id("call_1").name("get_weather")
                .partialArguments("{\"city\"").build());
            handler.onCompleteToolCall(new CompleteToolCall(0,
                ToolExecutionRequest.builder()
                    .id("call_1").name("get_weather")
                    .arguments("{\"city\":\"Munich\"}").build()));
            handler.onCompleteResponse(ChatResponse.builder()
                .aiMessage(AiMessage.from("")).finishReason(FinishReason.STOP).build());
        });

        List<AgentEvent> events = AgentEventBridge.stream(model, SIMPLE_REQUEST)
            .collect().asList().await().atMost(Duration.ofSeconds(5));

        assertThat(events).hasSize(2);
        assertThat(events.get(0)).isEqualTo(
            new AgentEvent.ToolCallDelta(0, "call_1", "get_weather", "{\"city\""));
        assertThat(events.get(1)).isEqualTo(
            new AgentEvent.ToolCallComplete(0, "call_1", "get_weather", "{\"city\":\"Munich\"}"));
    }

    @Test
    void stream_error_failsMulti() {
        RuntimeException error = new RuntimeException("model error");
        StreamingChatModel model = streamingModel((request, handler) -> {
            handler.onPartialResponse("partial");
            handler.onError(error);
        });

        assertThatThrownBy(() -> AgentEventBridge.stream(model, SIMPLE_REQUEST)
            .collect().asList().await().atMost(Duration.ofSeconds(5)))
            .isSameAs(error);
    }

    @Test
    void stream_emptyStream_completesWithNoEvents() {
        StreamingChatModel model = streamingModel((request, handler) ->
            handler.onCompleteResponse(ChatResponse.builder()
                .aiMessage(AiMessage.from("")).finishReason(FinishReason.STOP).build()));

        List<AgentEvent> events = AgentEventBridge.stream(model, SIMPLE_REQUEST)
            .collect().asList().await().atMost(Duration.ofSeconds(5));

        assertThat(events).isEmpty();
    }

    @Test
    void stream_mixedEventSequence_preservesOrder() {
        StreamingChatModel model = streamingModel((request, handler) -> {
            handler.onPartialThinking(new PartialThinking("thinking..."));
            handler.onPartialResponse("text1");
            handler.onPartialToolCall(PartialToolCall.builder()
                .index(0).id("c1").name("tool1").partialArguments("{").build());
            handler.onCompleteToolCall(new CompleteToolCall(0,
                ToolExecutionRequest.builder()
                    .id("c1").name("tool1").arguments("{\"a\":1}").build()));
            handler.onPartialResponse("text2");
            handler.onCompleteResponse(ChatResponse.builder()
                .aiMessage(AiMessage.from("text1text2"))
                .finishReason(FinishReason.STOP).build());
        });

        List<AgentEvent> events = AgentEventBridge.stream(model, SIMPLE_REQUEST)
            .collect().asList().await().atMost(Duration.ofSeconds(5));

        assertThat(events).hasSize(5);
        assertThat(events.get(0)).isInstanceOf(AgentEvent.ThinkingDelta.class);
        assertThat(events.get(1)).isInstanceOf(AgentEvent.TextDelta.class);
        assertThat(events.get(2)).isInstanceOf(AgentEvent.ToolCallDelta.class);
        assertThat(events.get(3)).isInstanceOf(AgentEvent.ToolCallComplete.class);
        assertThat(events.get(4)).isInstanceOf(AgentEvent.TextDelta.class);
    }

    @Test
    void stream_contextAwareOverload_capturesStreamingHandle() {
        AtomicBoolean cancelCalled = new AtomicBoolean(false);
        StreamingHandle handle = new StreamingHandle() {
            @Override public void cancel() { cancelCalled.set(true); }
            @Override public boolean isCancelled() { return cancelCalled.get(); }
        };

        StreamingChatModel model = streamingModel((request, handler) -> {
            handler.onPartialResponse(
                new PartialResponse("tok1"),
                new PartialResponseContext(handle));
            handler.onPartialResponse(
                new PartialResponse("tok2"),
                new PartialResponseContext(handle));
            // Do not complete — let the subscriber cancel
        });

        AgentEventBridge.stream(model, SIMPLE_REQUEST)
            .select().first(1)
            .collect().asList()
            .await().atMost(Duration.ofSeconds(5));

        assertThat(cancelCalled.get()).isTrue();
    }

    @Test
    void stream_noStreamingHandle_cancellationDoesNotThrow() {
        StreamingChatModel model = streamingModel((request, handler) -> {
            handler.onPartialResponse("tok1");
            handler.onPartialResponse("tok2");
            // Do not complete — let the subscriber cancel
        });

        List<AgentEvent> events = AgentEventBridge.stream(model, SIMPLE_REQUEST)
            .select().first(1)
            .collect().asList()
            .await().atMost(Duration.ofSeconds(5));

        assertThat(events).hasSize(1);
        assertThat(events.get(0)).isEqualTo(new AgentEvent.TextDelta("tok1"));
    }

    // --- dispatch() tests ---

    @Test
    void dispatch_textDelta_callsOnPartialResponse() {
        Multi<AgentEvent> events = Multi.createFrom().items(
            new AgentEvent.TextDelta("Hello"),
            new AgentEvent.TextDelta(" World"));

        var handler = new RecordingHandler();
        AgentEventBridge.dispatch(events, handler);

        assertThat(handler.partialResponses).containsExactly("Hello", " World");
        assertThat(handler.completedResponse).isNotNull();
        assertThat(handler.completedResponse.aiMessage().text()).isEqualTo("Hello World");
        assertThat(handler.completedResponse.finishReason()).isEqualTo(FinishReason.STOP);
    }

    @Test
    void dispatch_thinkingDelta_callsOnPartialThinking() {
        Multi<AgentEvent> events = Multi.createFrom().item(
            new AgentEvent.ThinkingDelta("reasoning"));

        var handler = new RecordingHandler();
        AgentEventBridge.dispatch(events, handler);

        assertThat(handler.thinkingTexts).containsExactly("reasoning");
    }

    @Test
    void dispatch_toolCallDelta_callsOnPartialToolCall() {
        Multi<AgentEvent> events = Multi.createFrom().item(
            new AgentEvent.ToolCallDelta(0, "c1", "tool1", "{\"a\""));

        var handler = new RecordingHandler();
        AgentEventBridge.dispatch(events, handler);

        assertThat(handler.partialToolCalls).hasSize(1);
        assertThat(handler.partialToolCalls.get(0).name()).isEqualTo("tool1");
    }

    @Test
    void dispatch_toolCallComplete_callsOnCompleteToolCallAndIncludesInResponse() {
        Multi<AgentEvent> events = Multi.createFrom().items(
            new AgentEvent.TextDelta("text"),
            new AgentEvent.ToolCallComplete(0, "c1", "tool1", "{\"a\":1}"));

        var handler = new RecordingHandler();
        AgentEventBridge.dispatch(events, handler);

        assertThat(handler.completeToolCalls).hasSize(1);
        assertThat(handler.completeToolCalls.get(0).toolExecutionRequest().name()).isEqualTo("tool1");
        assertThat(handler.completedResponse.aiMessage().toolExecutionRequests()).hasSize(1);
        assertThat(handler.completedResponse.finishReason()).isEqualTo(FinishReason.TOOL_EXECUTION);
    }

    @Test
    void dispatch_toolResult_silentlyIgnored() {
        Multi<AgentEvent> events = Multi.createFrom().items(
            new AgentEvent.TextDelta("text"),
            new AgentEvent.ToolResult("c1", "result", false));

        var handler = new RecordingHandler();
        AgentEventBridge.dispatch(events, handler);

        assertThat(handler.partialResponses).containsExactly("text");
        assertThat(handler.completedResponse.aiMessage().text()).isEqualTo("text");
    }

    @Test
    void dispatch_error_callsOnError() {
        RuntimeException error = new RuntimeException("fail");
        Multi<AgentEvent> events = Multi.createFrom().failure(error);

        var handler = new RecordingHandler();
        AgentEventBridge.dispatch(events, handler);

        assertThat(handler.error).isSameAs(error);
        assertThat(handler.completedResponse).isNull();
    }

    // -- recording handler helper --

    private static class RecordingHandler implements StreamingChatResponseHandler {
        final List<String> partialResponses = new ArrayList<>();
        final List<String> thinkingTexts = new ArrayList<>();
        final List<PartialToolCall> partialToolCalls = new ArrayList<>();
        final List<CompleteToolCall> completeToolCalls = new ArrayList<>();
        ChatResponse completedResponse;
        Throwable error;

        @Override public void onPartialResponse(String text) {
            partialResponses.add(text);
        }
        @Override public void onPartialThinking(PartialThinking pt) {
            thinkingTexts.add(pt.text());
        }
        @Override public void onPartialToolCall(PartialToolCall ptc) {
            partialToolCalls.add(ptc);
        }
        @Override public void onCompleteToolCall(CompleteToolCall ctc) {
            completeToolCalls.add(ctc);
        }
        @Override public void onCompleteResponse(ChatResponse response) {
            completedResponse = response;
        }
        @Override public void onError(Throwable error) {
            this.error = error;
        }
    }

    // -- helper --

    @FunctionalInterface
    interface DoChat {
        void accept(ChatRequest request, StreamingChatResponseHandler handler);
    }

    private static StreamingChatModel streamingModel(DoChat doChat) {
        return new StreamingChatModel() {
            @Override
            public void doChat(ChatRequest request, StreamingChatResponseHandler handler) {
                doChat.accept(request, handler);
            }
        };
    }
}
