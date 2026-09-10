package io.casehub.platform.agent.claude;

import io.casehub.platform.agent.AgentEvent;
import org.junit.jupiter.api.Test;
import org.springaicommunity.claude.agent.sdk.types.AssistantMessage;
import org.springaicommunity.claude.agent.sdk.types.ContentBlock;
import org.springaicommunity.claude.agent.sdk.types.ResultMessage;
import org.springaicommunity.claude.agent.sdk.types.SystemMessage;
import org.springaicommunity.claude.agent.sdk.types.TextBlock;
import org.springaicommunity.claude.agent.sdk.types.ThinkingBlock;
import org.springaicommunity.claude.agent.sdk.types.ToolUseBlock;
import org.springaicommunity.claude.agent.sdk.types.ToolResultBlock;
import org.springaicommunity.claude.agent.sdk.types.UserMessage;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class MessageEventMapperTest {

    private final AtomicInteger toolIndex = new AtomicInteger(0);

    @Test
    void textBlock_maps_to_textDelta() {
        final var msg = AssistantMessage.of(List.of(TextBlock.of("hello")));
        final var events = MessageEventMapper.toEvents(msg, toolIndex);

        assertThat(events).singleElement().isEqualTo(new AgentEvent.TextDelta("hello"));
    }

    @Test
    void empty_textBlock_skipped() {
        final var msg = AssistantMessage.of(List.of(TextBlock.of("")));
        final var events = MessageEventMapper.toEvents(msg, toolIndex);

        assertThat(events).isEmpty();
    }

    @Test
    void null_textBlock_text_skipped() {
        final var msg = AssistantMessage.of(List.of(new TextBlock(null)));
        final var events = MessageEventMapper.toEvents(msg, toolIndex);

        assertThat(events).isEmpty();
    }

    @Test
    void thinkingBlock_maps_to_thinkingDelta() {
        final var msg = AssistantMessage.of(List.of(ThinkingBlock.of("reasoning")));
        final var events = MessageEventMapper.toEvents(msg, toolIndex);

        assertThat(events).singleElement().isEqualTo(new AgentEvent.ThinkingDelta("reasoning"));
    }

    @Test
    void null_thinkingBlock_text_skipped() {
        final var msg = AssistantMessage.of(List.of(ThinkingBlock.of(null)));
        final var events = MessageEventMapper.toEvents(msg, toolIndex);

        assertThat(events).isEmpty();
    }

    @Test
    void empty_thinkingBlock_skipped() {
        final var msg = AssistantMessage.of(List.of(ThinkingBlock.of("")));
        final var events = MessageEventMapper.toEvents(msg, toolIndex);

        assertThat(events).isEmpty();
    }

    @Test
    void toolUseBlock_maps_to_toolCallComplete() {
        final var msg = AssistantMessage.of(List.of(
            ToolUseBlock.builder().id("tu1").name("Read").input(Map.of("path", "f.java")).build()));
        final var events = MessageEventMapper.toEvents(msg, toolIndex);

        assertThat(events).singleElement().satisfies(e -> {
            assertThat(e).isInstanceOf(AgentEvent.ToolCallComplete.class);
            final var tc = (AgentEvent.ToolCallComplete) e;
            assertThat(tc.index()).isEqualTo(0);
            assertThat(tc.id()).isEqualTo("tu1");
            assertThat(tc.name()).isEqualTo("Read");
            assertThat(tc.arguments()).contains("\"path\"");
            assertThat(tc.arguments()).contains("\"f.java\"");
        });
    }

    @Test
    void toolUseBlock_null_input_serializes_to_empty_json() {
        final var msg = AssistantMessage.of(List.of(
            ToolUseBlock.builder().id("tu1").name("Read").input(null).build()));
        final var events = MessageEventMapper.toEvents(msg, toolIndex);

        assertThat(events).singleElement().satisfies(e -> {
            final var tc = (AgentEvent.ToolCallComplete) e;
            assertThat(tc.arguments()).isEqualTo("{}");
        });
    }

    @Test
    void mixed_blocks_preserve_order() {
        final var msg = AssistantMessage.of(List.of(
            ThinkingBlock.of("thinking first"),
            TextBlock.of("then text"),
            ToolUseBlock.builder().id("tu1").name("Bash").input(Map.of("cmd", "ls")).build()));
        final var events = MessageEventMapper.toEvents(msg, toolIndex);

        assertThat(events).hasSize(3);
        assertThat(events.get(0)).isInstanceOf(AgentEvent.ThinkingDelta.class);
        assertThat(events.get(1)).isInstanceOf(AgentEvent.TextDelta.class);
        assertThat(events.get(2)).isInstanceOf(AgentEvent.ToolCallComplete.class);
    }

    @Test
    void toolIndex_increments_across_messages() {
        final var msg1 = AssistantMessage.of(List.of(
            ToolUseBlock.builder().id("tu1").name("Read").input(Map.of()).build()));
        final var msg2 = AssistantMessage.of(List.of(
            ToolUseBlock.builder().id("tu2").name("Write").input(Map.of()).build()));

        final var events1 = MessageEventMapper.toEvents(msg1, toolIndex);
        final var events2 = MessageEventMapper.toEvents(msg2, toolIndex);

        assertThat(((AgentEvent.ToolCallComplete) events1.get(0)).index()).isEqualTo(0);
        assertThat(((AgentEvent.ToolCallComplete) events2.get(0)).index()).isEqualTo(1);
    }

    @Test
    void unknown_contentBlock_skipped() {
        final ContentBlock unknown = new ContentBlock() {
            @Override public String getType() { return "unknown_future_type"; }
        };
        final var msg = AssistantMessage.of(List.of(unknown));
        final var events = MessageEventMapper.toEvents(msg, toolIndex);

        assertThat(events).isEmpty();
    }

    @Test
    void resultMessage_maps_to_invocationComplete() {
        final var msg = ResultMessage.builder()
            .subtype("success")
            .durationMs(1500)
            .durationApiMs(1200)
            .isError(false)
            .numTurns(3)
            .sessionId("sess-123")
            .totalCostUsd(0.042)
            .usage(Map.of(
                "input_tokens", 1000,
                "output_tokens", 500,
                "thinking_tokens", 200,
                "cache_creation_input_tokens", 50,
                "cache_read_input_tokens", 300))
            .build();
        final var events = MessageEventMapper.toEvents(msg, toolIndex);

        assertThat(events).singleElement().satisfies(e -> {
            assertThat(e).isInstanceOf(AgentEvent.InvocationComplete.class);
            final var ic = (AgentEvent.InvocationComplete) e;
            assertThat(ic.inputTokens()).isEqualTo(1000);
            assertThat(ic.outputTokens()).isEqualTo(500);
            assertThat(ic.thinkingTokens()).isEqualTo(200);
            assertThat(ic.cacheWriteTokens()).isEqualTo(50);
            assertThat(ic.cacheReadTokens()).isEqualTo(300);
            assertThat(ic.totalCostUsd()).isEqualTo(0.042);
            assertThat(ic.durationMs()).isEqualTo(1500);
            assertThat(ic.apiDurationMs()).isEqualTo(1200);
            assertThat(ic.sessionId()).isEqualTo("sess-123");
            assertThat(ic.numTurns()).isEqualTo(3);
            assertThat(ic.isError()).isFalse();
        });
    }

    @Test
    void resultMessage_nullUsage_zeroTokens() {
        final var msg = ResultMessage.builder()
            .subtype("success")
            .isError(true)
            .build();
        final var events = MessageEventMapper.toEvents(msg, toolIndex);

        assertThat(events).singleElement().satisfies(e -> {
            final var ic = (AgentEvent.InvocationComplete) e;
            assertThat(ic.inputTokens()).isZero();
            assertThat(ic.outputTokens()).isZero();
            assertThat(ic.thinkingTokens()).isZero();
            assertThat(ic.cacheReadTokens()).isZero();
            assertThat(ic.cacheWriteTokens()).isZero();
            assertThat(ic.totalCostUsd()).isNull();
            assertThat(ic.isError()).isTrue();
        });
    }

    @Test
    void userMessage_text_skipped() {
        final var msg = new UserMessage("hello");
        final var events = MessageEventMapper.toEvents(msg, toolIndex);

        assertThat(events).isEmpty();
    }

    @Test
    void userMessage_toolResultBlock_maps_to_toolResult() {
        final var trb = ToolResultBlock.builder()
            .toolUseId("tu-42")
            .content("file contents here")
            .isError(false)
            .build();
        final var msg = new UserMessage(List.of(trb));
        final var events = MessageEventMapper.toEvents(msg, toolIndex);

        assertThat(events).singleElement().satisfies(e -> {
            assertThat(e).isInstanceOf(AgentEvent.ToolResult.class);
            final var tr = (AgentEvent.ToolResult) e;
            assertThat(tr.toolCallId()).isEqualTo("tu-42");
            assertThat(tr.content()).isEqualTo("file contents here");
            assertThat(tr.isError()).isFalse();
        });
    }

    @Test
    void userMessage_toolResultBlock_error() {
        final var trb = ToolResultBlock.builder()
            .toolUseId("tu-99")
            .content("command failed")
            .isError(true)
            .build();
        final var msg = new UserMessage(List.of(trb));
        final var events = MessageEventMapper.toEvents(msg, toolIndex);

        assertThat(events).singleElement().satisfies(e -> {
            final var tr = (AgentEvent.ToolResult) e;
            assertThat(tr.isError()).isTrue();
        });
    }

    @Test
    void userMessage_toolResultBlock_objectContent_serialized() {
        final var trb = ToolResultBlock.builder()
            .toolUseId("tu-7")
            .content(List.of(Map.of("type", "text", "text", "structured output")))
            .isError(false)
            .build();
        final var msg = new UserMessage(List.of(trb));
        final var events = MessageEventMapper.toEvents(msg, toolIndex);

        assertThat(events).singleElement().satisfies(e -> {
            final var tr = (AgentEvent.ToolResult) e;
            assertThat(tr.content()).contains("structured output");
        });
    }

    @Test
    void systemMessage_skipped() {
        final var msg = new SystemMessage("init", Map.of());
        final var events = MessageEventMapper.toEvents(msg, toolIndex);

        assertThat(events).isEmpty();
    }
}
