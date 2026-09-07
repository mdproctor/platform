package io.casehub.platform.agent.claude;

import io.casehub.platform.agent.AgentEvent;
import io.casehub.platform.agent.AgentSessionConfig;
import io.casehub.platform.agent.AgentSessionLimitException;
import io.smallrye.mutiny.Multi;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ClaudeAgentClientTest {

    private ClaudeAgentClient client;

    @AfterEach
    void teardown() {
        if (client != null) {
            client.shutdown();
        }
    }

    private ClaudeAgentProperties props(int maxSessions) {
        var p = mock(ClaudeAgentProperties.class);
        when(p.maxConcurrentSessions()).thenReturn(maxSessions);
        when(p.defaultTimeout()).thenReturn(Duration.ofMinutes(5));
        when(p.binaryPath()).thenReturn(Optional.empty());
        return p;
    }

    private AgentSessionConfig config() {
        return AgentSessionConfig.of("sys", "user");
    }

    @Test
    void limitReached_returnsFailureMulti_notSynchronousThrow() {
        client = new ClaudeAgentClient(props(1), c -> Multi.createFrom().nothing());
        client.run(config()); // fill the cap

        var result = client.run(config());
        assertThatThrownBy(() -> result.collect().asList().await().indefinitely())
            .isInstanceOf(AgentSessionLimitException.class);
    }

    @Test
    void semaphore_releasedOnCompletion_allowsNextCall() {
        client = new ClaudeAgentClient(props(1), c -> Multi.createFrom().empty());
        client.run(config()).collect().asList().await().indefinitely();

        assertThatCode(() ->
            client.run(config()).collect().asList().await().indefinitely()
        ).doesNotThrowAnyException();
    }

    @Test
    void semaphore_releasedOnFailure_allowsNextCall() {
        var callCount = new java.util.concurrent.atomic.AtomicInteger(0);
        client = new ClaudeAgentClient(props(1), c -> {
            if (callCount.getAndIncrement() == 0) {
                return Multi.createFrom().failure(new RuntimeException("boom"));
            }
            return Multi.createFrom().empty();
        });

        // First call fails
        assertThatThrownBy(() ->
            client.run(config()).collect().asList().await().indefinitely()
        ).isInstanceOf(RuntimeException.class);

        // Same client — semaphore must have been released for this to succeed
        assertThatCode(() ->
            client.run(config()).collect().asList().await().indefinitely()
        ).doesNotThrowAnyException();
    }

    @Test
    void semaphore_releasedOnCancellation() throws InterruptedException {
        var subscribed = new CountDownLatch(1);
        var cancelled = new CountDownLatch(1);
        // Use AtomicReference to track which stream factory invocation
        var callCount = new java.util.concurrent.atomic.AtomicInteger(0);
        client = new ClaudeAgentClient(props(1), c -> {
            int call = callCount.incrementAndGet();
            if (call == 1) {
                // First call: never-ending emitter
                return Multi.createFrom().<AgentEvent>emitter(em -> subscribed.countDown())
                    .onCancellation().invoke(cancelled::countDown);
            }
            // Subsequent calls: complete immediately
            return Multi.createFrom().empty();
        });

        var sub = client.run(config()).subscribe().with(x -> {}, e -> {}, () -> {});
        assertThat(subscribed.await(2, TimeUnit.SECONDS))
            .as("emitter should be subscribed within 2s").isTrue();
        sub.cancel();
        assertThat(cancelled.await(2, TimeUnit.SECONDS))
            .as("cancellation should propagate within 2s").isTrue();

        Awaitility.await("outer semaphore release after cancellation")
            .atMost(2, TimeUnit.SECONDS)
            .pollInterval(10, TimeUnit.MILLISECONDS)
            .until(() -> client.availablePermits() == 1);

        // Semaphore was released — second run on same client should succeed
        var result = client.run(config());
        assertThatCode(() ->
            result.collect().asList().await().atMost(Duration.ofSeconds(5))
        ).doesNotThrowAnyException();
    }

    @Test
    void run_streamsItemsFromFactory() {
        client = new ClaudeAgentClient(props(2), c ->
            Multi.createFrom().items(
                new AgentEvent.TextDelta("hello"),
                new AgentEvent.TextDelta(" world")
            )
        );
        List<AgentEvent> result = client.run(config())
            .collect().asList()
            .await().indefinitely();
        assertThat(result).hasSize(2);
        assertThat(((AgentEvent.TextDelta) result.get(0)).text()).isEqualTo("hello");
    }

    @Test
    void constructor_zeroMaxConcurrentSessions_createsAlwaysPermitSemaphore() {
        var client = new ClaudeAgentClient(props(0), c -> Multi.createFrom().empty());
        assertThat(client.availablePermits()).isEqualTo(Integer.MAX_VALUE);
    }

    @Test
    void constructor_negativeMaxConcurrentSessions_throwsIllegalState() {
        assertThatThrownBy(() -> new ClaudeAgentClient(props(-3), c -> Multi.createFrom().empty()))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("max-concurrent-sessions")
            .hasMessageContaining("-3");
    }

    @Test
    void validateBinary_nonExistentPath_degradesGracefully() {
        var p = props(1);
        when(p.binaryPath()).thenReturn(Optional.of("/no/such/binary/claude-xyz-nonexistent"));
        client = new ClaudeAgentClient(p, c -> Multi.createFrom().empty());
        assertThatCode(client::validateBinary).doesNotThrowAnyException();

        var result = client.run(config());
        assertThatThrownBy(() -> result.collect().asList().await().indefinitely())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not available");
    }
}
