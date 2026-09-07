package io.casehub.platform.agent.codex;

import io.casehub.platform.agent.AgentEvent;
import io.casehub.platform.agent.AgentSessionConfig;
import io.casehub.platform.agent.AgentSessionInit;
import io.casehub.platform.agent.AgentSessionLimitException;
import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.helpers.test.AssertSubscriber;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CodexAgentBackendTest {

    @Test
    void keyIsCodex() {
        var backend = backend(1, config -> Multi.createFrom().empty());
        assertThat(backend.key()).isEqualTo("codex");
    }

    @Test
    void invokeStreamsEventsFromFactory() {
        var backend = backend(4, config -> Multi.createFrom().items(
                new AgentEvent.TextDelta("codex "),
                new AgentEvent.TextDelta("output")));

        var config = AgentSessionConfig.of("sys", "user", "codex");
        var events = backend.invoke(config).collect().asList().await().atMost(Duration.ofSeconds(5));

        assertThat(events).hasSize(2);
        assertThat(((AgentEvent.TextDelta) events.get(0)).text()).isEqualTo("codex ");
    }

    @Test
    void semaphoreReleasedOnCompletion() {
        var backend = backend(1, config -> Multi.createFrom().item(new AgentEvent.TextDelta("ok")));
        backend.invoke(AgentSessionConfig.of("sys", "user"))
                .collect().asList().await().atMost(Duration.ofSeconds(5));
        assertThat(backend.availablePermits()).isEqualTo(1);
    }

    @Test
    void semaphoreReleasedOnFailure() {
        var backend = backend(1, config -> Multi.createFrom().failure(new RuntimeException("boom")));
        var subscriber = backend.invoke(AgentSessionConfig.of("sys", "user"))
                .subscribe().withSubscriber(AssertSubscriber.create(Long.MAX_VALUE));
        subscriber.awaitFailure(Duration.ofSeconds(5));
        assertThat(backend.availablePermits()).isEqualTo(1);
    }

    @Test
    void semaphoreLimitRejectsExcessCalls() throws InterruptedException {
        var subscribedLatch = new CountDownLatch(1);
        var backend = backend(1, config -> Multi.createFrom().<AgentEvent>nothing()
                .onSubscription().invoke(s -> subscribedLatch.countDown()));

        var first = backend.invoke(AgentSessionConfig.of("sys", "first"))
                .subscribe().withSubscriber(AssertSubscriber.create(Long.MAX_VALUE));
        subscribedLatch.await(5, TimeUnit.SECONDS);

        var second = backend.invoke(AgentSessionConfig.of("sys", "second"))
                .subscribe().withSubscriber(AssertSubscriber.create(Long.MAX_VALUE));
        second.awaitFailure(Duration.ofSeconds(5));
        assertThat(second.getFailure()).isInstanceOf(AgentSessionLimitException.class);

        first.cancel();
    }

    @Test
    void openSessionThrowsUnsupported() {
        var backend = backend(1, config -> Multi.createFrom().empty());
        assertThatThrownBy(() -> backend.openSession(AgentSessionInit.of("sys")))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    private static CodexAgentBackend backend(int maxSessions,
                                             java.util.function.Function<AgentSessionConfig, Multi<AgentEvent>> factory) {
        var props = mock(CodexAgentProperties.class);
        when(props.binaryPath()).thenReturn("codex");
        when(props.defaultTimeout()).thenReturn(Duration.ofMinutes(5));
        when(props.maxConcurrentSessions()).thenReturn(maxSessions);
        return new CodexAgentBackend(props, factory);
    }
}
