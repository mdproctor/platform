package io.casehub.platform.agent.openai;

import io.casehub.platform.agent.AgentEvent;
import com.openai.models.chat.completions.ChatCompletionChunk;

import java.util.ArrayList;
import java.util.List;

public final class OpenAiEventMapper {

    private OpenAiEventMapper() {}

    public static List<AgentEvent> toEvents(ChatCompletionChunk chunk, long startTimeMs) {
        List<AgentEvent> events = new ArrayList<>();
        for (ChatCompletionChunk.Choice choice : chunk.choices()) {
            ChatCompletionChunk.Choice.Delta delta = choice.delta();
            delta.content().ifPresent(content -> {
                if (!content.isEmpty()) {
                    events.add(new AgentEvent.TextDelta(content));
                }
            });
            delta.toolCalls().ifPresent(toolCalls -> {
                for (var tc : toolCalls) {
                    tc.function().ifPresent(fn -> {
                        String id = tc.id().orElse(null);
                        String name = fn.name().orElse(null);
                        String args = fn.arguments().orElse(null);
                        int index = (int) tc.index();
                        if (name != null) {
                            events.add(new AgentEvent.ToolCallDelta(index, id, name, args));
                        } else if (args != null) {
                            events.add(new AgentEvent.ToolCallDelta(index, id, null, args));
                        }
                    });
                }
            });
        }
        chunk.usage().ifPresent(usage -> {
            int promptTokens = (int) usage.promptTokens();
            int completionTokens = (int) usage.completionTokens();
            int cacheRead = usage.promptTokensDetails()
                    .flatMap(d -> d.cachedTokens())
                    .map(Long::intValue)
                    .orElse(0);
            int reasoningTokens = usage.completionTokensDetails()
                    .flatMap(d -> d.reasoningTokens())
                    .map(Long::intValue)
                    .orElse(0);
            long durationMs = System.currentTimeMillis() - startTimeMs;
            events.add(new AgentEvent.InvocationComplete(
                    promptTokens, completionTokens, reasoningTokens,
                    cacheRead, 0,
                    null, durationMs, durationMs,
                    chunk.id(), 1, false));
        });
        return events;
    }
}
