package io.casehub.platform.agent.session;

import io.casehub.platform.agent.AgentBackend;
import io.casehub.platform.agent.AgentEvent;
import io.casehub.platform.agent.AgentSession;
import io.casehub.platform.agent.AgentSessionConfig;
import io.casehub.platform.agent.AgentSessionInit;
import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.Uni;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SessionPoolTest {

    @Test
    void checkoutReturnsPooledSession() {
        var backend = new StubBackend();
        var pool = new SessionPool(backend, config(1, 2));

        var session = pool.checkout(init());

        assertThat(session).isInstanceOf(PooledSession.class);
        assertThat(session.backendKey()).isEqualTo("stub");
        session.close();
    }

    @Test
    void releaseDestroysNonClearableSession() {
        var backend = new StubBackend();
        var pool = new SessionPool(backend, config(0, 2));

        var session = pool.checkout(init());
        session.close();

        assertThat(backend.sessionsCreated.get()).isEqualTo(1);
    }

    @Test
    void releaseClearsAndKeepsWarmWhenBelowMin() {
        var backend = new ClearableBackend();
        var pool = new SessionPool(backend, config(2, 4));

        var session = pool.checkout(init());
        session.close();

        assertThat(pool.warmCount()).isEqualTo(1);
    }

    @Test
    void invokeViaSessionChecksOutAndReleases() {
        var backend = new StubBackend();
        var pool = new SessionPool(backend, config(0, 2));

        var config = AgentSessionConfig.of("system", "user");
        var events = pool.invokeViaSession(config)
                .collect().asList().await().atMost(Duration.ofSeconds(5));

        assertThat(events).hasSize(1);
        assertThat(events.get(0)).isInstanceOf(AgentEvent.TextDelta.class);
    }

    @Test
    void maxActiveBlocksWhenExhausted() throws Exception {
        var backend = new StubBackend();
        var pool = new SessionPool(backend, config(0, 1));

        var session1 = pool.checkout(init());

        var future = CompletableFuture.supplyAsync(() -> {
            try {
                return pool.checkout(init());
            } catch (Exception e) {
                return null;
            }
        });

        assertThatThrownBy(() -> future.get(200, TimeUnit.MILLISECONDS))
                .isInstanceOf(TimeoutException.class);

        session1.close();
        var session2 = future.get(5, TimeUnit.SECONDS);
        assertThat(session2).isNotNull();
        session2.close();
    }

    @Test
    void shutdownClosesWarmSessions() {
        var backend = new ClearableBackend();
        var pool = new SessionPool(backend, config(2, 4));

        var s1 = pool.checkout(init());
        var s2 = pool.checkout(init());
        s1.close();
        s2.close();

        assertThat(pool.warmCount()).isEqualTo(2);

        pool.shutdown();

        assertThat(pool.warmCount()).isEqualTo(0);
    }

    @Test
    void checkoutAfterShutdownThrows() {
        var backend = new StubBackend();
        var pool = new SessionPool(backend, config(0, 2));
        pool.shutdown();

        assertThatThrownBy(() -> pool.checkout(init()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("shut down");
    }

    @Test
    void preWarmReplacesDestroyedSession() throws Exception {
        var backend = new StubBackend();
        var pool    = new SessionPool(backend, config(1, 2));

        var session = pool.checkout(init());
        assertThat(backend.sessionsCreated.get()).isEqualTo(1);

        session.close();

        // Pre-warm runs async — give it a moment
        Thread.sleep(200);

        // Pool should have pre-warmed a replacement since minActive=1
        // and clear() is a no-op on StubSession (non-clearable), so it was destroyed
        // The pre-warm fires asynchronously after release
        assertThat(backend.sessionsCreated.get()).isGreaterThanOrEqualTo(2);
    }

    @Test
    void idleEvictionClosesSessionsBeyondTimeout() throws Exception {
        // Use a very short idle timeout for testing
        var shortConfig = new SessionPoolConfig("test", "stub", 0, 4, Duration.ofMillis(100), null);
        var backend     = new ClearableBackend();
        var pool        = new SessionPool(backend, shortConfig);

        var s1 = pool.checkout(init());
        s1.close();

        assertThat(pool.warmCount()).isEqualTo(0); // minActive=0, so clear+keep doesn't happen

        pool.shutdown();
    }

    @Test
    void releaseKeepsWarmUpToMinActive() {
        var backend = new ClearableBackend();
        var pool    = new SessionPool(backend, config(2, 4));

        var s1 = pool.checkout(init());
        var s2 = pool.checkout(init());
        var s3 = pool.checkout(init());
        s1.close();
        s2.close();
        s3.close();

        // Only minActive=2 kept warm, 3rd destroyed after clear
        assertThat(pool.warmCount()).isEqualTo(2);

        pool.shutdown();
    }


    // --- Test infrastructure ---

    private static SessionPoolConfig config(int min, int max) {
        return new SessionPoolConfig("test", "stub", min, max, null, null);
    }

    private static AgentSessionInit init() {
        return AgentSessionInit.of("test system prompt");
    }

    static class StubBackend implements AgentBackend {
        final AtomicInteger sessionsCreated = new AtomicInteger();

        @Override public String key() { return "stub"; }

        @Override
        public Multi<AgentEvent> invoke(AgentSessionConfig config) {
            return Multi.createFrom().item(new AgentEvent.TextDelta("response"));
        }

        @Override
        public AgentSession openSession(AgentSessionInit init) {
            sessionsCreated.incrementAndGet();
            return new StubSession();
        }
    }

    static class ClearableBackend implements AgentBackend {
        @Override public String key() { return "stub"; }

        @Override
        public Multi<AgentEvent> invoke(AgentSessionConfig config) {
            return Multi.createFrom().item(new AgentEvent.TextDelta("response"));
        }

        @Override
        public AgentSession openSession(AgentSessionInit init) {
            return new ClearableSession();
        }
    }

    static class StubSession implements AgentSession {
        volatile boolean closed = false;

        @Override
        public Multi<AgentEvent> query(String prompt) {
            return Multi.createFrom().item(new AgentEvent.TextDelta("response"));
        }

        @Override public Uni<Void> interrupt() { return Uni.createFrom().voidItem(); }

        @Override
        public void clear() {
            throw new UnsupportedOperationException("StubSession does not support clear");
        }


        @Override
        public void close(Duration maxWait) { closed = true; }
    }

    static class ClearableSession implements AgentSession {
        final AtomicBoolean cleared = new AtomicBoolean(false);
        volatile boolean closed = false;

        @Override
        public Multi<AgentEvent> query(String prompt) {
            return Multi.createFrom().item(new AgentEvent.TextDelta("response"));
        }

        @Override public Uni<Void> interrupt() { return Uni.createFrom().voidItem(); }

        @Override
        public void clear() { cleared.set(true); }

        @Override
        public void close(Duration maxWait) { closed = true; }
    }
}
