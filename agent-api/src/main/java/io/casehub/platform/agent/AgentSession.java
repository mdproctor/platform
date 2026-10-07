package io.casehub.platform.agent;

import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.Uni;

import java.time.Duration;

/**
 * A multi-turn AI agent session.
 *
 * <p>Sessions are created via {@link AgentProvider#openSession(AgentSessionInit)} and hold a
 * concurrent-session semaphore slot until closed. Callers <strong>must</strong> close the
 * session on all paths — use try-with-resources.
 *
 * <p>The session is serial: only one turn may be active at a time.
 *
 * <p><strong>Do not call {@link #query(String)} from multiple threads concurrently.</strong>
 * Concurrent calls result in {@link IllegalStateException} for all but the first caller.
 * In the unlikely event of a write-before-CAS race (two threads both write
 * {@code currentTurnFuture} before either CAS), {@link #close(Duration)} may wait up to
 * {@code maxWait} on an uncompleted future before force-closing. The outcome is bounded and
 * safe, but callers must treat the session as single-threaded.
 */
public interface AgentSession extends AutoCloseable {

    /**
     * Send a query and stream the response.
     *
     * <p>Serial model — only one turn may be active at a time. Calling {@code query()}
     * while a turn is streaming throws {@link IllegalStateException}. Wait for the
     * current {@code Multi} to complete (or call {@link #interrupt()}) before calling again.
     *
     * <p>First call internally uses the SDK's {@code connect()} to establish the subprocess
     * and send the initial prompt. Subsequent calls use {@code query()}, preserving
     * conversational context.
     *
     * @throws IllegalStateException if a turn is already active or the session is closed
     * @throws AgentTimeoutException via onFailure() if the wall-clock turn timeout is exceeded
     * @throws AgentProcessException via onFailure() if the subprocess errors
     */
    Multi<AgentEvent> query(String prompt);

    /**
     * Send an interrupt signal to the Claude CLI subprocess.
     *
     * <p>Best-effort fire-and-forget. The session remains ACTIVE. If the CLI responds by
     * sending a {@code ResultMessage}, the current turn completes naturally. Calling from
     * IDLE or CLOSED state is a no-op.
     */
    Uni<Void> interrupt();

    /**
     * Close the session with a best-effort drain.
     *
     * <p>Sets state to CLOSED immediately (no new turns). If a turn is active, this method
     * <strong>blocks</strong> the calling thread for up to {@code maxWait}, waiting for the
     * active turn's Mutiny pipeline to drain. The drain signal arrives on the Mutiny worker
     * pool thread (the pipeline runs via {@code runSubscriptionOn}); blocking is safe because
     * the signal does not need the caller's thread to fire.
     *
     * <p><strong>Do not call from a Mutiny/Vert.x event-loop thread.</strong> The blocking
     * wait will starve the event loop and the drain signal will never arrive.
     *
     * <p>After the drain (or timeout), subprocess teardown is fired asynchronously —
     * {@code close()} returns before teardown finishes. The semaphore slot is released on
     * the calling thread as the final step.
     *
     * <p>Idempotent — second call is a no-op.
     *
     * <p><strong>Sessions not closed leak a semaphore slot permanently.</strong>
     */
    void close(Duration maxWait);

    /** Delegates to {@code close(Duration.ofSeconds(30))}. */
    @Override
    default void close() {
        close(Duration.ofSeconds(30));
    }

    /**
     * Reset conversation context without closing the session.
     *
     * <p>Default no-op. Backends that support in-place context reset (e.g. clearing
     * {@code ChatMemory}) override this. For subprocess-based backends where clearing
     * requires a process restart, the session pool handles recycling externally.
     */
    default void clear() {}

}
