package io.casehub.platform.agent.openai;

import io.casehub.platform.agent.AgentEvent;
import io.casehub.platform.agent.AgentSessionConfig;
import io.casehub.platform.agent.AgentSessionInit;
import io.casehub.platform.agent.AgentSessionLimitException;
import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.helpers.test.AssertSubscriber;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class OpenAiAgentBackendTest {

    @Test
    void keyIsOpenai() {
        var backend = backend(1, config -> Multi.createFrom().empty());
        assertThat(backend.key()).isEqualTo("openai");
    }

    @Test
    void invokeStreamsEventsFromFactory() {
        var backend = backend(4, config -> Multi.createFrom().items(
                new AgentEvent.TextDelta("hello "),
                new AgentEvent.TextDelta("world")));

        var config = AgentSessionConfig.of("sys", "user", "openai");
        var events = backend.invoke(config).collect().asList().await().atMost(Duration.ofSeconds(5));

        assertThat(events).hasSize(2);
        assertThat(((AgentEvent.TextDelta) events.get(0)).text()).isEqualTo("hello ");
        assertThat(((AgentEvent.TextDelta) events.get(1)).text()).isEqualTo("world");
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
    void semaphoreReleasedOnCancellation() throws InterruptedException {
        var latch = new CountDownLatch(1);
        var cancelled = new AtomicBoolean(false);
        var backend = backend(1, config -> Multi.createFrom().<AgentEvent>nothing()
                .onSubscription().invoke(s -> latch.countDown())
                .onCancellation().invoke(() -> cancelled.set(true)));

        var subscriber = backend.invoke(AgentSessionConfig.of("sys", "user"))
                .subscribe().withSubscriber(AssertSubscriber.create(Long.MAX_VALUE));
        latch.await(5, TimeUnit.SECONDS);
        subscriber.cancel();

        assertThat(cancelled.get()).isTrue();
        assertThat(backend.availablePermits()).isEqualTo(1);
    }

    @Test
    void semaphoreLimitRejectsExcessCalls() throws InterruptedException {
        var blockLatch = new CountDownLatch(1);
        var subscribedLatch = new CountDownLatch(1);
        var backend = backend(1, config -> Multi.createFrom().<AgentEvent>nothing()
                .onSubscription().invoke(s -> subscribedLatch.countDown()));

        // First call — occupies the single permit
        var first = backend.invoke(AgentSessionConfig.of("sys", "first"))
                .subscribe().withSubscriber(AssertSubscriber.create(Long.MAX_VALUE));
        subscribedLatch.await(5, TimeUnit.SECONDS);

        // Second call — should fail
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

    @Test
    void zeroMaxSessionsMeansUnlimited() {
        var backend = backend(0, config -> Multi.createFrom().item(new AgentEvent.TextDelta("ok")));
        // Should not throw — zero means unlimited
        var events = backend.invoke(AgentSessionConfig.of("sys", "user"))
                .collect().asList().await().atMost(Duration.ofSeconds(5));
        assertThat(events).hasSize(1);
    }

    @Test
    void negativeMaxSessionsThrows() {
        assertThatThrownBy(() -> backend(-1, config -> Multi.createFrom().empty()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("max-concurrent-sessions");
    }

    private static OpenAiAgentBackend backend(int maxSessions,
                                              java.util.function.Function<AgentSessionConfig, Multi<AgentEvent>> factory) {
        var props = mock(OpenAiAgentProperties.class);
        when(props.defaultModel()).thenReturn("gpt-4.1");
        when(props.defaultTimeout()).thenReturn(Duration.ofMinutes(5));
        when(props.maxConcurrentSessions()).thenReturn(maxSessions);
        when(props.promptCacheRetention()).thenReturn("in_memory");
        when(props.apiKey()).thenReturn(Optional.empty());
        return new OpenAiAgentBackend(props, factory);
    }
}
