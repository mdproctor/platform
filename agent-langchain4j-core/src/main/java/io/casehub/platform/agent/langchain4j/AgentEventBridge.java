package io.casehub.platform.agent.langchain4j;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.CompleteToolCall;
import dev.langchain4j.model.chat.response.PartialResponse;
import dev.langchain4j.model.chat.response.PartialResponseContext;
import dev.langchain4j.model.chat.response.PartialThinking;
import dev.langchain4j.model.chat.response.PartialThinkingContext;
import dev.langchain4j.model.chat.response.PartialToolCall;
import dev.langchain4j.model.chat.response.PartialToolCallContext;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import dev.langchain4j.model.chat.response.StreamingHandle;
import dev.langchain4j.model.output.FinishReason;
import io.casehub.platform.agent.AgentEvent;
import io.smallrye.mutiny.Multi;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

public final class AgentEventBridge {

    private AgentEventBridge() {}

    public static Multi<AgentEvent> stream(StreamingChatModel model, ChatRequest request) {
        return Multi.createFrom().emitter(emitter -> {
            AtomicReference<StreamingHandle> handleRef = new AtomicReference<>();
            emitter.onTermination(() -> {
                StreamingHandle h = handleRef.get();
                if (h != null) h.cancel();
            });

            model.chat(request, new StreamingChatResponseHandler() {
                @Override
                public void onPartialResponse(String text) {
                    emitter.emit(new AgentEvent.TextDelta(text));
                }

                @Override
                public void onPartialResponse(PartialResponse partialResponse,
                                               PartialResponseContext context) {
                    handleRef.compareAndSet(null, context.streamingHandle());
                    emitter.emit(new AgentEvent.TextDelta(partialResponse.text()));
                }

                @Override
                public void onPartialThinking(PartialThinking partialThinking) {
                    emitter.emit(new AgentEvent.ThinkingDelta(partialThinking.text()));
                }

                @Override
                public void onPartialThinking(PartialThinking partialThinking,
                                               PartialThinkingContext context) {
                    handleRef.compareAndSet(null, context.streamingHandle());
                    emitter.emit(new AgentEvent.ThinkingDelta(partialThinking.text()));
                }

                @Override
                public void onPartialToolCall(PartialToolCall partialToolCall) {
                    emitter.emit(new AgentEvent.ToolCallDelta(
                        partialToolCall.index(), partialToolCall.id(),
                        partialToolCall.name(), partialToolCall.partialArguments()));
                }

                @Override
                public void onPartialToolCall(PartialToolCall partialToolCall,
                                               PartialToolCallContext context) {
                    handleRef.compareAndSet(null, context.streamingHandle());
                    emitter.emit(new AgentEvent.ToolCallDelta(
                        partialToolCall.index(), partialToolCall.id(),
                        partialToolCall.name(), partialToolCall.partialArguments()));
                }

                @Override
                public void onCompleteToolCall(CompleteToolCall completeToolCall) {
                    var ter = completeToolCall.toolExecutionRequest();
                    emitter.emit(new AgentEvent.ToolCallComplete(
                        completeToolCall.index(), ter.id(), ter.name(), ter.arguments()));
                }

                @Override
                public void onCompleteResponse(ChatResponse response) {
                    emitter.complete();
                }

                @Override
                public void onError(Throwable error) {
                    emitter.fail(error);
                }
            });
        });
    }

    public static void dispatch(Multi<AgentEvent> events, StreamingChatResponseHandler handler) {
        StringBuilder buffer = new StringBuilder();
        List<ToolExecutionRequest> toolRequests = new ArrayList<>();
        events.subscribe().with(
            event -> {
                switch (event) {
                    case AgentEvent.TextDelta delta -> {
                        handler.onPartialResponse(delta.text());
                        buffer.append(delta.text());
                    }
                    case AgentEvent.ThinkingDelta thinking ->
                        handler.onPartialThinking(new PartialThinking(thinking.text()));
                    case AgentEvent.ToolCallDelta d ->
                        handler.onPartialToolCall(PartialToolCall.builder()
                            .index(d.index()).id(d.id()).name(d.name())
                            .partialArguments(d.partialArguments()).build());
                    case AgentEvent.ToolCallComplete c -> {
                        ToolExecutionRequest ter = ToolExecutionRequest.builder()
                            .id(c.id()).name(c.name()).arguments(c.arguments()).build();
                        handler.onCompleteToolCall(new CompleteToolCall(c.index(), ter));
                        toolRequests.add(ter);
                    }
                    case AgentEvent.ToolResult ignored -> {}
                    case AgentEvent.InvocationComplete ignored -> {}
                }
            },
            handler::onError,
            () -> {
                AiMessage aiMessage = toolRequests.isEmpty()
                    ? AiMessage.from(buffer.toString())
                    : AiMessage.builder().text(buffer.toString())
                        .toolExecutionRequests(toolRequests).build();
                FinishReason reason = toolRequests.isEmpty()
                    ? FinishReason.STOP
                    : FinishReason.TOOL_EXECUTION;
                handler.onCompleteResponse(ChatResponse.builder()
                    .aiMessage(aiMessage).finishReason(reason).build());
            }
        );
    }
}
