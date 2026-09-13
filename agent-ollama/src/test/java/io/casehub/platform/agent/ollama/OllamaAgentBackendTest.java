package io.casehub.platform.agent.ollama;

import io.casehub.platform.agent.AgentEvent;
import io.casehub.platform.agent.AgentSessionConfig;
import io.casehub.platform.agent.AgentSessionInit;
import io.casehub.platform.agent.AgentSessionLimitException;
import io.smallrye.mutiny.Multi;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class OllamaAgentBackendTest {

    @Test
    void keyIsOllama() {
        var backend = backend(1, config -> Multi.createFrom().empty());
        assertThat(backend.key()).isEqualTo("ollama");
    }

    @Test
    void invokeStreamsEventsFromFactory() {
        var backend = backend(4, config -> Multi.createFrom().items(
            new AgentEvent.TextDelta("hello "),
            new AgentEvent.TextDelta("local")));

        var config = AgentSessionConfig.of("sys", "user", "llama3");
        var events = backend.invoke(config).collect().asList()
            .await().atMost(Duration.ofSeconds(5));

        assertThat(events).hasSize(2);
        assertThat(((AgentEvent.TextDelta) events.get(0)).text()).isEqualTo("hello ");
        assertThat(((AgentEvent.TextDelta) events.get(1)).text()).isEqualTo("local");
    }

    @Test
    void semaphoreReleasedOnCompletion() {
        var backend = backend(1, config ->
            Multi.createFrom().item(new AgentEvent.TextDelta("ok")));

        backend.invoke(AgentSessionConfig.of("sys", "user"))
            .collect().asList().await().atMost(Duration.ofSeconds(5));

        var second = backend.invoke(AgentSessionConfig.of("sys", "user"))
            .collect().asList().await().atMost(Duration.ofSeconds(5));
        assertThat(second).hasSize(1);
    }

    @Test
    void semaphoreLimitRejectsExcessCalls() throws InterruptedException {
        var subscribedLatch = new CountDownLatch(1);
        var backend = backend(1, config -> Multi.createFrom().<AgentEvent>nothing()
            .onSubscription().invoke(s -> subscribedLatch.countDown()));

        var first = backend.invoke(AgentSessionConfig.of("sys", "first"))
            .subscribe().withSubscriber(io.smallrye.mutiny.helpers.test.AssertSubscriber.create(Long.MAX_VALUE));
        subscribedLatch.await(5, TimeUnit.SECONDS);

        var second = backend.invoke(AgentSessionConfig.of("sys", "second"))
            .subscribe().withSubscriber(io.smallrye.mutiny.helpers.test.AssertSubscriber.create(Long.MAX_VALUE));
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

    private static OllamaAgentBackend backend(int maxSessions,
            java.util.function.Function<AgentSessionConfig, Multi<AgentEvent>> factory) {
        var props = mock(OllamaAgentProperties.class);
        when(props.host()).thenReturn("http://localhost:11434");
        when(props.defaultModel()).thenReturn("llama3");
        when(props.defaultTimeout()).thenReturn(Duration.ofMinutes(2));
        when(props.maxConcurrentSessions()).thenReturn(maxSessions);
        return new OllamaAgentBackend(props, factory);
    }
}
