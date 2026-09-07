package io.casehub.platform.agent.claude;

import io.casehub.platform.agent.AgentEvent;
import io.casehub.platform.agent.AgentProcessException;
import io.casehub.platform.agent.AgentSession;
import io.smallrye.mutiny.Multi;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class ClaudeAgentSessionTest {

    private ClaudeAgentSession session;

    @AfterEach
    void teardown() {
        if (session != null) {
            session.close(Duration.ofMillis(100));
        }
    }

    private ClaudeAgentProperties props() {
        final var p = mock(ClaudeAgentProperties.class);
        when(p.maxConcurrentSessions()).thenReturn(2);
        when(p.defaultTimeout()).thenReturn(Duration.ofMinutes(5));
        when(p.binaryPath()).thenReturn(Optional.empty());
        return p;
    }

    private ClaudeAgentSession session(final Function<String, Multi<AgentEvent>> factory) {
        final var semaphore = new Semaphore(2);
        semaphore.acquireUninterruptibly();  // simulate openSession() acquiring
        session = new ClaudeAgentSession(props(), factory, semaphore,
            new java.util.concurrent.CopyOnWriteArraySet<>(),
            java.util.concurrent.Executors.newSingleThreadScheduledExecutor());
        return session;
    }

    // ── test 1: IDLE → ACTIVE; concurrent query() throws ────────────────────

    @Test
    void query_fromIdle_startsSuccessfully() {
        final var s = session(prompt -> Multi.createFrom().empty());
        s.query("hello").collect().asList().await().indefinitely();
    }

    @Test
    void query_whileActive_throwsIllegalStateException() {
        final var s = session(prompt -> Multi.createFrom().empty());
        // Hold the cold Multi without subscribing — state is ACTIVE
        final var firstTurn = s.query("first");
        assertThatIllegalStateException()
            .isThrownBy(() -> s.query("concurrent"))
            .withMessageContaining("a turn is already active");
        firstTurn.collect().asList().await().indefinitely();
    }

    // ── test 2: turn completes → IDLE; next query succeeds ──────────────────

    @Test
    void query_afterPreviousTurnCompletes_sessionReusable() {
        final var callCount = new AtomicInteger(0);
        final var s = session(prompt -> {
            callCount.incrementAndGet();
            return Multi.createFrom().empty();
        });
        s.query("first").collect().asList().await().indefinitely();
        assertThatCode(() ->
            s.query("second").collect().asList().await().indefinitely()
        ).doesNotThrowAnyException();
        assertThat(callCount.get()).isEqualTo(2);
    }

    // ── test 3: turn fails → CLOSED; semaphore released ─────────────────────

    @Test
    void query_turnFails_sessionMovesToClosed_semaphoreReleased() {
        final var semaphore = new Semaphore(2);
        semaphore.acquireUninterruptibly();  // simulate openSession()
        final var failSession = new ClaudeAgentSession(props(),
            prompt -> Multi.createFrom().failure(new RuntimeException("boom")),
            semaphore, new java.util.concurrent.CopyOnWriteArraySet<>(),
            java.util.concurrent.Executors.newSingleThreadScheduledExecutor());
        session = failSession;

        assertThatThrownBy(() ->
            failSession.query("q").collect().asList().await().indefinitely()
        ).isInstanceOf(AgentProcessException.class);

        // Semaphore must be released after failure
        assertThat(semaphore.availablePermits()).isEqualTo(2);
        // Session must be closed
        assertThatIllegalStateException()
            .isThrownBy(() -> failSession.query("retry"))
            .withMessageContaining("session is closed");
    }

    // ── test 4: cancellation → CLOSED; semaphore released ───────────────────

    @Test
    void query_subscriberCancels_sessionMovesToClosed_semaphoreReleased() throws InterruptedException {
        final var subscribed = new CountDownLatch(1);
        final var semaphore = new Semaphore(2);
        semaphore.acquireUninterruptibly();
        final var cancelSession = new ClaudeAgentSession(props(),
            prompt -> Multi.createFrom().<AgentEvent>emitter(em -> subscribed.countDown()),
            semaphore, new java.util.concurrent.CopyOnWriteArraySet<>(),
            java.util.concurrent.Executors.newSingleThreadScheduledExecutor());
        session = cancelSession;

        final var sub = cancelSession.query("q").subscribe().with(x -> {}, e -> {}, () -> {});
        assertThat(subscribed.await(2, TimeUnit.SECONDS)).isTrue();
        sub.cancel();

        Awaitility.await("semaphore release after cancellation")
            .atMost(2, TimeUnit.SECONDS)
            .until(() -> semaphore.availablePermits() == 2);

        assertThatIllegalStateException()
            .isThrownBy(() -> cancelSession.query("after"))
            .withMessageContaining("session is closed");
    }

    // ── test 5: close() from IDLE ────────────────────────────────────────────

    @Test
    void close_fromIdle_releasesSemaphore_noSubprocessCalls() {
        final var semaphore = new Semaphore(2);
        semaphore.acquireUninterruptibly();
        final var s = new ClaudeAgentSession(props(),
            prompt -> Multi.createFrom().empty(),
            semaphore, new java.util.concurrent.CopyOnWriteArraySet<>(),
            java.util.concurrent.Executors.newSingleThreadScheduledExecutor());
        session = s;
        s.close(Duration.ofSeconds(1));
        assertThat(semaphore.availablePermits()).isEqualTo(2);
    }

    // ── test 6: close() is idempotent ────────────────────────────────────────

    @Test
    void close_idempotent_semaphoreNotDoubleReleased() {
        final var semaphore = new Semaphore(2);
        semaphore.acquireUninterruptibly();
        final var s = new ClaudeAgentSession(props(),
            prompt -> Multi.createFrom().empty(),
            semaphore, new java.util.concurrent.CopyOnWriteArraySet<>(),
            java.util.concurrent.Executors.newSingleThreadScheduledExecutor());
        session = s;
        s.close(Duration.ofSeconds(1));
        s.close(Duration.ofSeconds(1));  // second call — must not double-release
        assertThat(semaphore.availablePermits()).isEqualTo(2);
    }

    // ── test 7: close() from ACTIVE drains within timeout ───────────────────

    @Test
    void close_fromActive_drainsWithinTimeout_semaphoreReleased() throws InterruptedException {
        final var started = new CountDownLatch(1);
        final var semaphore = new Semaphore(2);
        semaphore.acquireUninterruptibly();
        final var s = new ClaudeAgentSession(props(),
            prompt -> Multi.createFrom().<AgentEvent>emitter(em -> {
                started.countDown();
                // never emits — will be force-completed by close()
            }),
            semaphore, new java.util.concurrent.CopyOnWriteArraySet<>(),
            java.util.concurrent.Executors.newSingleThreadScheduledExecutor());
        session = s;

        s.query("long running").subscribe().with(x -> {}, e -> {}, () -> {});
        assertThat(started.await(2, TimeUnit.SECONDS)).isTrue();

        final long before = System.currentTimeMillis();
        s.close(Duration.ofMillis(200));
        final long elapsed = System.currentTimeMillis() - before;

        assertThat(elapsed).isLessThan(1000);  // well within 200ms + tolerance
        assertThat(semaphore.availablePermits()).isEqualTo(2);
    }

    // ── test 8: interrupt() from ACTIVE in test mode ─────────────────────────

    @Test
    void interrupt_fromActive_testMode_isNoOp_noException() {
        final var s = session(prompt -> Multi.createFrom().empty());
        final var firstTurn = s.query("active");
        assertThatCode(() -> s.interrupt().await().indefinitely())
            .doesNotThrowAnyException();
        firstTurn.collect().asList().await().indefinitely();
    }

    // ── test 9: interrupt() from IDLE or CLOSED is no-op ─────────────────────

    @Test
    void interrupt_fromIdleOrClosed_isNoOp() {
        final var s = session(prompt -> Multi.createFrom().empty());
        assertThatCode(() -> s.interrupt().await().indefinitely())
            .doesNotThrowAnyException();
        s.close(Duration.ofSeconds(1));
        assertThatCode(() -> s.interrupt().await().indefinitely())
            .doesNotThrowAnyException();
    }
}
