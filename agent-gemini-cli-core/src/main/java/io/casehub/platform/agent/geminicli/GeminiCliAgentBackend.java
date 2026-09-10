package io.casehub.platform.agent.geminicli;

import io.casehub.platform.agent.AgentBackend;
import io.casehub.platform.agent.AgentEvent;
import io.casehub.platform.agent.AgentProcessException;
import io.casehub.platform.agent.AgentRuntime;
import io.casehub.platform.agent.AgentRuntimeConfig;
import io.casehub.platform.agent.AgentSession;
import io.casehub.platform.agent.AgentSessionConfig;
import io.casehub.platform.agent.AgentSessionInit;
import io.casehub.platform.agent.AgentSessionLimitException;
import io.casehub.platform.agent.AgentTimeoutException;
import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.infrastructure.Infrastructure;
import org.jboss.logging.Logger;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;

public class GeminiCliAgentBackend implements AgentBackend {

    private static final Logger LOG = Logger.getLogger(GeminiCliAgentBackend.class);

    private final GeminiCliAgentProperties properties;
    private final Semaphore semaphore;
    private final ScheduledExecutorService timeoutScheduler;
    private final Function<AgentSessionConfig, Multi<AgentEvent>> streamFactory;
    private final AgentRuntime runtime;

    public GeminiCliAgentBackend(GeminiCliAgentProperties properties, AgentRuntime runtime) {
        this.properties = properties;
        this.runtime = runtime;
        int maxSessions = properties.maxConcurrentSessions();
        if (maxSessions < 0) {
            throw new IllegalStateException(
                    "casehub.platform.agent.gemini-cli.max-concurrent-sessions must be >= 0, got " + maxSessions);
        }
        this.semaphore = new Semaphore(maxSessions == 0 ? Integer.MAX_VALUE : maxSessions);
        this.timeoutScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "casehub-agent-gemini-cli-timeout");
            t.setDaemon(true);
            return t;
        });
        this.streamFactory = null;
    }

    public GeminiCliAgentBackend(GeminiCliAgentProperties properties,
                                 Function<AgentSessionConfig, Multi<AgentEvent>> streamFactory) {
        this.properties = properties;
        this.runtime = null;
        int maxSessions = properties.maxConcurrentSessions();
        if (maxSessions < 0) {
            throw new IllegalStateException(
                    "casehub.platform.agent.gemini-cli.max-concurrent-sessions must be >= 0, got " + maxSessions);
        }
        this.semaphore = new Semaphore(maxSessions == 0 ? Integer.MAX_VALUE : maxSessions);
        this.timeoutScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "casehub-agent-gemini-cli-timeout");
            t.setDaemon(true);
            return t;
        });
        this.streamFactory = streamFactory;
    }

    int availablePermits() {
        return semaphore.availablePermits();
    }

    @Override
    public String key() {
        return "gemini-cli";
    }

    @Override
    public Multi<AgentEvent> invoke(AgentSessionConfig config) {
        if (!semaphore.tryAcquire()) {
            return Multi.createFrom().failure(
                    new AgentSessionLimitException(properties.maxConcurrentSessions()));
        }
        try {
            Multi<AgentEvent> stream = streamFactory != null
                    ? streamFactory.apply(config)
                    : buildEventStream(config);
            return stream
                    .runSubscriptionOn(Infrastructure.getDefaultWorkerPool())
                    .onCompletion().invoke(semaphore::release)
                    .onFailure().invoke(t -> semaphore.release())
                    .onCancellation().invoke(semaphore::release);
        } catch (Exception e) {
            semaphore.release();
            return Multi.createFrom().failure(e);
        }
    }

    @Override
    public AgentSession openSession(AgentSessionInit init) {
        throw new UnsupportedOperationException("Gemini CLI multi-turn sessions not yet implemented");
    }

    Multi<AgentEvent> buildEventStream(AgentSessionConfig config) {
        Duration effectiveTimeout = config.timeout() != null
                                    ? config.timeout()
                                    : properties.defaultTimeout();

        return Multi.createFrom().emitter(emitter -> {
            AtomicBoolean                          timedOut      = new AtomicBoolean(false);
            io.casehub.platform.agent.AgentProcess process       = null;
            ScheduledFuture<?>                     timeoutFuture = null;
            try {
                var runtimeConfig = new AgentRuntimeConfig(
                        properties.binaryPath(),
                        List.of("--prompt", config.userPrompt()),
                        Map.of(),
                        null);
                process = runtime.spawn(runtimeConfig);
                final var proc = process;

                timeoutFuture = timeoutScheduler.schedule(() -> {
                    if (timedOut.compareAndSet(false, true)) {
                        proc.destroyForcibly();
                    }
                }, effectiveTimeout.toMillis(), TimeUnit.MILLISECONDS);

                try (var reader = new BufferedReader(
                        new InputStreamReader(proc.stdout(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        if (!line.isBlank()) {
                            emitter.emit(new AgentEvent.TextDelta(line + "\n"));
                        }
                    }
                }

                int exitCode = proc.exitCode().join();

                if (timedOut.get()) {
                    emitter.fail(new AgentTimeoutException(effectiveTimeout));
                } else if (exitCode != 0) {
                    emitter.fail(new AgentProcessException(
                            "gemini exited with code " + exitCode, null));
                } else {
                    emitter.complete();
                }
            } catch (Exception e) {
                if (timedOut.get()) {
                    emitter.fail(new AgentTimeoutException(effectiveTimeout));
                } else {
                    emitter.fail(new AgentProcessException(
                            Objects.toString(e.getMessage(), e.getClass().getSimpleName()), e));
                }
            } finally {
                if (timeoutFuture != null) {
                    timeoutFuture.cancel(false);
                }
                if (process != null) {
                    process.destroy();
                }
            }
        });
    }

    public void shutdown() {
        if (timeoutScheduler != null) {
            timeoutScheduler.shutdownNow();
        }
    }
}
