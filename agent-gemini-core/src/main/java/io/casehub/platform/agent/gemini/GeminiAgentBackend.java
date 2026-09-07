package io.casehub.platform.agent.gemini;

import io.casehub.platform.agent.AgentBackend;
import io.casehub.platform.agent.AgentEvent;
import io.casehub.platform.agent.AgentProcessException;
import io.casehub.platform.agent.AgentSession;
import io.casehub.platform.agent.AgentSessionConfig;
import io.casehub.platform.agent.AgentSessionInit;
import io.casehub.platform.agent.AgentSessionLimitException;
import io.casehub.platform.agent.AgentTimeoutException;
import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.infrastructure.Infrastructure;
import org.jboss.logging.Logger;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Semaphore;
import java.util.function.Function;

public class GeminiAgentBackend implements AgentBackend {

    private static final Logger LOG = Logger.getLogger(GeminiAgentBackend.class);

    private final GeminiAgentProperties properties;
    private final Semaphore semaphore;
    private final ScheduledExecutorService timeoutScheduler;
    private final Function<AgentSessionConfig, Multi<AgentEvent>> streamFactory;

    public GeminiAgentBackend(GeminiAgentProperties properties) {
        this.properties = properties;
        int maxSessions = properties.maxConcurrentSessions();
        if (maxSessions < 0) {
            throw new IllegalStateException(
                    "casehub.platform.agent.gemini.max-concurrent-sessions must be >= 0, got " + maxSessions);
        }
        this.semaphore = new Semaphore(maxSessions == 0 ? Integer.MAX_VALUE : maxSessions);
        this.timeoutScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "casehub-agent-gemini-timeout");
            t.setDaemon(true);
            return t;
        });
        this.streamFactory = null;
    }

    public GeminiAgentBackend(GeminiAgentProperties properties,
                              Function<AgentSessionConfig, Multi<AgentEvent>> streamFactory) {
        this.properties = properties;
        int maxSessions = properties.maxConcurrentSessions();
        if (maxSessions < 0) {
            throw new IllegalStateException(
                    "casehub.platform.agent.gemini.max-concurrent-sessions must be >= 0, got " + maxSessions);
        }
        this.semaphore = new Semaphore(maxSessions == 0 ? Integer.MAX_VALUE : maxSessions);
        this.timeoutScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "casehub-agent-gemini-timeout");
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
        return "gemini";
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
        throw new UnsupportedOperationException("Gemini multi-turn sessions not yet implemented");
    }

    Multi<AgentEvent> buildEventStream(AgentSessionConfig config) {
        return Multi.createFrom().emitter(emitter -> {
            emitter.fail(new AgentProcessException(
                    "Gemini production path requires GEMINI_API_KEY — " +
                    "configure casehub.platform.agent.gemini.api-key", null));
        });
    }

    public void shutdown() {
        if (timeoutScheduler != null) {
            timeoutScheduler.shutdownNow();
        }
    }
}
