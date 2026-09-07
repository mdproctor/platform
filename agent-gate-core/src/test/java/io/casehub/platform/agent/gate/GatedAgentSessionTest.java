package io.casehub.platform.agent.gate;

import io.casehub.platform.agent.AgentEvent;
import io.casehub.platform.agent.AgentRateLimitException;
import io.casehub.platform.agent.AgentSession;
import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.Uni;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GatedAgentSessionTest {

    @Test
    void queryConsumesTokenWhenRateLimitActive() throws Exception {
        var tokenBucket = new TokenBucketStrategy(1.0, 1);
        var concurrency = new ConcurrencyStrategy(1);
        var session = new GatedAgentSession(
                stubSession("hello"),
                List.of(concurrency), List.of(tokenBucket),
                Duration.ofSeconds(5), new SessionRegistry(), 0);

        String result = collectText(session.query("prompt"));
        assertThat(result).isEqualTo("hello");
    }

    @Test
    void querySkipsTokenWhenNoQueryStrategies() {
        var concurrency = new ConcurrencyStrategy(1);
        var session = new GatedAgentSession(
                stubSession("hello"),
                List.of(concurrency), List.of(),
                Duration.ofSeconds(5), new SessionRegistry(), 0);

        String result = collectText(session.query("prompt"));
        assertThat(result).isEqualTo("hello");
    }

    @Test
    void queryFailsWithRateLimitExceptionWhenBucketEmpty() throws Exception {
        var tokenBucket = new TokenBucketStrategy(0.5, 1);
        tokenBucket.tryAcquire(Duration.ZERO);
        var session = new GatedAgentSession(
                stubSession("hello"),
                List.of(), List.of(tokenBucket),
                Duration.ofMillis(50), new SessionRegistry(), 0);

        assertThatThrownBy(() -> collectText(session.query("prompt")))
                .isInstanceOf(AgentRateLimitException.class);
    }

    @Test
    void closeReleasesSessionStrategies() throws Exception {
        var concurrency = new ConcurrencyStrategy(1);
        concurrency.tryAcquire(Duration.ofSeconds(1));
        var session = new GatedAgentSession(
                stubSession("x"),
                List.of(concurrency), List.of(),
                Duration.ofSeconds(5), new SessionRegistry(), 0);

        session.close();
        assertThat(concurrency.tryAcquire(Duration.ofMillis(50))).isTrue();
    }

    @Test
    void closeReleasesPermitEvenWhenDelegateThrows() throws Exception {
        var concurrency = new ConcurrencyStrategy(1);
        concurrency.tryAcquire(Duration.ofSeconds(1));
        var failing = new StubSession() {
            @Override
            public void close(Duration maxWait) {
                throw new RuntimeException("close failed");
            }
        };
        var session = new GatedAgentSession(
                failing,
                List.of(concurrency), List.of(),
                Duration.ofSeconds(5), new SessionRegistry(), 0);

        try {
            session.close();
        } catch (RuntimeException ignored) {
        }
        assertThat(concurrency.tryAcquire(Duration.ofMillis(50))).isTrue();
    }

    @Test
    void closeWithNoSessionStrategies() {
        var session = new GatedAgentSession(
                stubSession("x"),
                List.of(), List.of(),
                Duration.ofSeconds(5), new SessionRegistry(), 0);
        session.close();
    }

    @Test
    void interruptDelegatesToSession() {
        var delegate = new StubSession();
        var session = new GatedAgentSession(
                delegate,
                List.of(), List.of(),
                Duration.ofSeconds(5), new SessionRegistry(), 0);
        session.interrupt();
        assertThat(delegate.interrupted).isTrue();
    }

    @Test
    void doubleCloseReleasesPermitOnlyOnce() throws Exception {
        var concurrency = new ConcurrencyStrategy(2);
        concurrency.tryAcquire(Duration.ofSeconds(1));
        concurrency.tryAcquire(Duration.ofSeconds(1));
        var registry = new SessionRegistry();
        long id = registry.nextId();
        var session = new GatedAgentSession(
                stubSession("x"),
                List.of(concurrency), List.of(),
                Duration.ofSeconds(5),
                registry, id);
        registry.register(id, session);

        session.close();
        session.close();

        assertThat(concurrency.tryAcquire(Duration.ofMillis(50))).isTrue();
        assertThat(concurrency.tryAcquire(Duration.ofMillis(50))).isFalse();
    }

    @Test
    void closeDeregistersFromRegistry() {
        var registry = new SessionRegistry();
        long id = registry.nextId();
        var session = new GatedAgentSession(
                stubSession("x"),
                List.of(), List.of(),
                Duration.ofSeconds(5),
                registry, id);
        registry.register(id, session);

        session.close();
        assertThat(registry.snapshot()).doesNotContainKey(id);
    }

    @Test
    void queryAfterCloseReturnsFailure() {
        var session = new GatedAgentSession(
                stubSession("hello"),
                List.of(), List.of(),
                Duration.ofSeconds(5), new SessionRegistry(), 0);
        session.close();

        assertThatThrownBy(() -> collectText(session.query("prompt")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("closed");
    }

    // --- helpers ---

    private static String collectText(Multi<AgentEvent> multi) {
        return multi
                .filter(e -> e instanceof AgentEvent.TextDelta)
                .map(e -> ((AgentEvent.TextDelta) e).text())
                .collect().with(Collectors.joining())
                .await().atMost(Duration.ofSeconds(5));
    }

    private static AgentSession stubSession(String text) {
        return new StubSession() {
            @Override
            public Multi<AgentEvent> query(String prompt) {
                return Multi.createFrom().item(new AgentEvent.TextDelta(text));
            }
        };
    }

    private static class StubSession implements AgentSession {
        boolean interrupted = false;

        @Override
        public Multi<AgentEvent> query(String prompt) {
            return Multi.createFrom().empty();
        }

        @Override
        public Uni<Void> interrupt() {
            interrupted = true;
            return Uni.createFrom().voidItem();
        }

        @Override
        public void close(Duration maxWait) {
        }

        @Override
        public void close() {
            close(Duration.ofSeconds(30));
        }
    }
}
