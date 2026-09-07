package io.casehub.platform.agent.claude;

import io.casehub.platform.agent.AgentEvent;
import io.casehub.platform.agent.AgentProcessException;
import io.casehub.platform.agent.AgentSession;
import io.casehub.platform.agent.AgentTimeoutException;
import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.Uni;
import io.smallrye.mutiny.infrastructure.Infrastructure;
import org.jboss.logging.Logger;
import org.springaicommunity.claude.agent.sdk.ClaudeAsyncClient;
import org.springaicommunity.claude.agent.sdk.types.Message;
import reactor.adapter.JdkFlowAdapter;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

class ClaudeAgentSession implements AgentSession {

    private static final Logger LOG = Logger.getLogger(ClaudeAgentSession.class);

    private enum State { IDLE, ACTIVE, CLOSED }

    private final ClaudeAsyncClient sdkClient;
    private final Function<String, Multi<AgentEvent>> turnFactory;
    private final Duration timeout;
    private final Semaphore semaphore;
    private final CopyOnWriteArraySet<ClaudeAsyncClient> activeSessions;
    private final ScheduledExecutorService timeoutScheduler;

    private final AtomicReference<State> state = new AtomicReference<>(State.IDLE);
    private final AtomicBoolean sessionStarted = new AtomicBoolean(false);
    private final String correlationId;
    private final AtomicInteger turnCounter = new AtomicInteger(0);
    private volatile CompletableFuture<Void> currentTurnFuture;
    private volatile ScheduledFuture<?> currentTimeoutFuture;

    ClaudeAgentSession(final ClaudeAsyncClient sdkClient,
                       final Duration timeout,
                       final Semaphore semaphore,
                       final CopyOnWriteArraySet<ClaudeAsyncClient> activeSessions,
                       final ScheduledExecutorService timeoutScheduler,
                       final String correlationId) {
        this.sdkClient = sdkClient;
        this.turnFactory = null;
        this.timeout = timeout;
        this.semaphore = semaphore;
        this.activeSessions = activeSessions;
        this.timeoutScheduler = timeoutScheduler;
        this.correlationId = correlationId;
    }

    ClaudeAgentSession(final ClaudeAgentProperties properties,
                       final Function<String, Multi<AgentEvent>> turnFactory,
                       final Semaphore semaphore,
                       final CopyOnWriteArraySet<ClaudeAsyncClient> activeSessions,
                       final ScheduledExecutorService timeoutScheduler) {
        this.sdkClient = null;
        this.turnFactory = turnFactory;
        this.timeout = properties.defaultTimeout();
        this.semaphore = semaphore;
        this.activeSessions = activeSessions;
        this.timeoutScheduler = timeoutScheduler;
        this.correlationId = null;
    }

    @Override
    public Multi<AgentEvent> query(final String prompt) {
        final var pendingFuture = new CompletableFuture<Void>();
        this.currentTurnFuture = pendingFuture;

        if (!state.compareAndSet(State.IDLE, State.ACTIVE)) {
            final State current = state.get();
            throw new IllegalStateException(current == State.CLOSED
                ? "session is closed"
                : "a turn is already active — wait for it to complete or call interrupt()");
        }

        final int turn = turnCounter.incrementAndGet();
        logTurn("started", turn);

        final var timedOut = new AtomicBoolean(false);

        final var timeoutFuture = timeoutScheduler.schedule(() -> {
            if (timedOut.compareAndSet(false, true)) {
                if (sdkClient != null) sdkClient.close().subscribe();
            }
        }, timeout.toMillis(), TimeUnit.MILLISECONDS);
        this.currentTimeoutFuture = timeoutFuture;

        final Multi<AgentEvent> rawStream = buildTurnStream(prompt);

        return rawStream
            .onCompletion().call(() -> {
                if (timedOut.get()) {
                    return Uni.createFrom().failure(new AgentTimeoutException(timeout));
                }
                return Uni.createFrom().voidItem();
            })
            .onFailure().transform(e -> {
                if (e instanceof AgentTimeoutException) return e;
                if (timedOut.get()) return new AgentTimeoutException(timeout);
                return new AgentProcessException(
                    Objects.toString(e.getMessage(), e.getClass().getSimpleName()), e);
            })
            .onCompletion().invoke(() -> {
                cancelTimeout(timeoutFuture);
                pendingFuture.complete(null);
                logTurn("completed", turn);
                state.compareAndSet(State.ACTIVE, State.IDLE);
            })
            .onFailure().invoke(e -> {
                cancelTimeout(timeoutFuture);
                pendingFuture.complete(null);
                logTurn("failed", turn);
                if (state.compareAndSet(State.ACTIVE, State.CLOSED)) {
                    closeSubprocess();
                    semaphore.release();
                }
            })
            .onCancellation().invoke(() -> {
                cancelTimeout(timeoutFuture);
                pendingFuture.complete(null);
                logTurn("cancelled", turn);
                if (state.compareAndSet(State.ACTIVE, State.CLOSED)) {
                    closeSubprocess();
                    semaphore.release();
                }
            })
            .runSubscriptionOn(Infrastructure.getDefaultWorkerPool());
    }

    @Override
    public Uni<Void> interrupt() {
        if (state.get() != State.ACTIVE) {
            return Uni.createFrom().voidItem();
        }
        if (sdkClient == null) {
            return Uni.createFrom().voidItem();
        }
        return Uni.createFrom().publisher(
            JdkFlowAdapter.publisherToFlowPublisher(sdkClient.interrupt().flux())
        ).onFailure().recoverWithNull();
    }

    @Override
    public void close(final Duration maxWait) {
        final State prev = state.getAndSet(State.CLOSED);
        if (prev == State.CLOSED) {
            return;
        }

        if (prev == State.ACTIVE) {
            final CompletableFuture<Void> turnFuture = currentTurnFuture;
            if (turnFuture != null) {
                try {
                    turnFuture.get(maxWait.toMillis(), TimeUnit.MILLISECONDS);
                } catch (final InterruptedException e) {
                    Thread.currentThread().interrupt();
                } catch (TimeoutException | ExecutionException | CancellationException ignored) {}
            }
        }

        cancelTimeout(currentTimeoutFuture);
        if (sdkClient != null) activeSessions.remove(sdkClient);
        if (sdkClient != null) sdkClient.close().subscribe();
        if (correlationId != null) {
            LOG.infof("Agent session closed [correlationId=%s]", correlationId);
        }
        semaphore.release();
    }

    private Multi<AgentEvent> buildTurnStream(final String prompt) {
        if (turnFactory != null) {
            return turnFactory.apply(prompt);
        }
        final Flux<Message> messageFlux = sessionStarted.compareAndSet(false, true)
            ? sdkClient.connect(prompt).messages()
            : sdkClient.query(prompt).messages();
        final AtomicInteger toolIndex = new AtomicInteger(0);

        return Multi.createFrom()
            .publisher(JdkFlowAdapter.publisherToFlowPublisher(messageFlux))
            .onItem().transformToMultiAndConcatenate(
                msg -> Multi.createFrom().iterable(MessageEventMapper.toEvents(msg, toolIndex)));
    }

    private void closeSubprocess() {
        if (sdkClient != null) {
            activeSessions.remove(sdkClient);
            sdkClient.close().subscribe();
        }
    }

    private static void cancelTimeout(final ScheduledFuture<?> future) {
        if (future != null) future.cancel(false);
    }

    private void logTurn(final String event, final int turn) {
        if (correlationId != null) {
            LOG.infof("Agent session turn %s [correlationId=%s, turn=%d]",
                event, correlationId, turn);
        }
    }
}
