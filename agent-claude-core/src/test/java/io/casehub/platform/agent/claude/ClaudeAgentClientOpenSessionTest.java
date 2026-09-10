package io.casehub.platform.agent.claude;

import io.casehub.platform.agent.AgentSessionInit;
import io.casehub.platform.agent.AgentSessionLimitException;
import io.smallrye.mutiny.Multi;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class ClaudeAgentClientOpenSessionTest {

    private ClaudeAgentClient client;

    @AfterEach
    void teardown() {
        if (client != null) client.shutdown();
    }

    private ClaudeAgentProperties props(final int maxSessions) {
        final var p = mock(ClaudeAgentProperties.class);
        when(p.maxConcurrentSessions()).thenReturn(maxSessions);
        when(p.defaultTimeout()).thenReturn(Duration.ofMinutes(5));
        when(p.binaryPath()).thenReturn(Optional.empty());
        return p;
    }

    private AgentSessionInit init() {
        return AgentSessionInit.of("system prompt");
    }

    // ── test 1: semaphore limit enforced ─────────────────────────────────────

    @Test
    void openSession_beyondLimit_throwsAgentSessionLimitExceptionImmediately() {
        client = new ClaudeAgentClient(props(1), c -> Multi.createFrom().empty());
        // fill the cap
        final var s1 = client.openSession(init());

        assertThatThrownBy(() -> client.openSession(init()))
            .isInstanceOf(AgentSessionLimitException.class);

        s1.close(Duration.ofSeconds(1));
    }

    // ── test 2: semaphore not released on turn completion ─────────────────────

    @Test
    void openSession_semaphoreNotReleasedOnTurnCompletion() {
        client = new ClaudeAgentClient(props(2), c -> Multi.createFrom().empty());
        final var session = client.openSession(init());

        // drain a turn
        session.query("q").collect().asList().await().indefinitely();

        // Semaphore is session-scoped — still consumed after turn completion
        assertThat(client.availablePermits()).isEqualTo(1);

        session.close(Duration.ofSeconds(1));
    }

    // ── test 3: semaphore released after close() ──────────────────────────────

    @Test
    void openSession_semaphoreReleasedAfterClose() {
        client = new ClaudeAgentClient(props(2), c -> Multi.createFrom().empty());
        final var session = client.openSession(init());
        assertThat(client.availablePermits()).isEqualTo(1);

        session.close(Duration.ofSeconds(1));
        assertThat(client.availablePermits()).isEqualTo(2);
    }
}
