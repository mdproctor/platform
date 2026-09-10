package io.casehub.platform.agent.claude;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.casehub.platform.agent.AgentEvent;
import org.jboss.logging.Logger;
import org.springaicommunity.claude.agent.sdk.types.AssistantMessage;
import org.springaicommunity.claude.agent.sdk.types.ContentBlock;
import org.springaicommunity.claude.agent.sdk.types.Message;
import org.springaicommunity.claude.agent.sdk.types.ResultMessage;
import org.springaicommunity.claude.agent.sdk.types.TextBlock;
import org.springaicommunity.claude.agent.sdk.types.ThinkingBlock;
import org.springaicommunity.claude.agent.sdk.types.ToolResultBlock;
import org.springaicommunity.claude.agent.sdk.types.ToolUseBlock;
import org.springaicommunity.claude.agent.sdk.types.UserMessage;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

public class MessageEventMapper {

    private static final Logger LOG = Logger.getLogger(MessageEventMapper.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    public static List<AgentEvent> toEvents(final Message message, final AtomicInteger toolIndex) {
        if (message instanceof ResultMessage rm) return List.of(toInvocationComplete(rm));
        if (message instanceof UserMessage um) return toToolResults(um);
        if (!(message instanceof AssistantMessage am)) return List.of();
        final List<AgentEvent> events = new ArrayList<>();
        for (final ContentBlock block : am.content()) {
            switch (block) {
                case TextBlock tb -> {
                    if (tb.text() != null && !tb.text().isEmpty())
                        events.add(new AgentEvent.TextDelta(tb.text()));
                }
                case ThinkingBlock tb -> {
                    if (tb.thinking() != null && !tb.thinking().isEmpty())
                        events.add(new AgentEvent.ThinkingDelta(tb.thinking()));
                }
                case ToolUseBlock tu -> {
                    final String args = serializeInput(tu.input());
                    events.add(new AgentEvent.ToolCallComplete(
                        toolIndex.getAndIncrement(), tu.id(), tu.name(), args));
                }
                default -> LOG.debugf("Skipping unrecognized ContentBlock type: %s",
                    block.getType());
            }
        }
        return events;
    }

    private static List<AgentEvent> toToolResults(final UserMessage um) {
        final List<ContentBlock> blocks = um.getContentAsBlocks();
        if (blocks == null || blocks.isEmpty()) return List.of();
        final List<AgentEvent> events = new ArrayList<>();
        for (final ContentBlock block : blocks) {
            if (block instanceof ToolResultBlock trb) {
                final String content = serializeContent(trb.content());
                events.add(new AgentEvent.ToolResult(
                    trb.toolUseId(), content, Boolean.TRUE.equals(trb.isError())));
            }
        }
        return events;
    }

    private static String serializeContent(final Object content) {
        if (content == null) return "";
        if (content instanceof String s) return s;
        try {
            return MAPPER.writeValueAsString(content);
        } catch (final Exception e) {
            LOG.warnf("Failed to serialize tool result content: %s", e.getMessage());
            return content.toString();
        }
    }

    private static AgentEvent.InvocationComplete toInvocationComplete(final ResultMessage rm) {
        final Map<String, Object> usage = rm.usage();
        return new AgentEvent.InvocationComplete(
            getInt(usage, "input_tokens"),
            getInt(usage, "output_tokens"),
            getInt(usage, "thinking_tokens"),
            getInt(usage, "cache_read_input_tokens"),
            getInt(usage, "cache_creation_input_tokens"),
            rm.totalCostUsd(),
            rm.durationMs(), rm.durationApiMs(),
            rm.sessionId(), rm.numTurns(),
            rm.isError());
    }

    private static int getInt(final Map<String, Object> map, final String key) {
        if (map == null) return 0;
        final Object value = map.get(key);
        return value instanceof Number n ? n.intValue() : 0;
    }

    private static String serializeInput(final Map<String, Object> input) {
        if (input == null || input.isEmpty()) return "{}";
        try {
            return MAPPER.writeValueAsString(input);
        } catch (final Exception e) {
            LOG.warnf("Failed to serialize tool input, defaulting to {}: %s", e.getMessage());
            return "{}";
        }
    }
}
