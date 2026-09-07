package io.casehub.platform.agent.claude;

import io.casehub.platform.agent.AgentEvent;
import io.casehub.platform.agent.AgentMcpServer;
import io.casehub.platform.agent.AgentProcessException;
import io.casehub.platform.agent.AgentSession;
import io.casehub.platform.agent.AgentSessionConfig;
import io.casehub.platform.agent.AgentSessionInit;
import io.casehub.platform.agent.AgentSessionLimitException;
import io.casehub.platform.agent.AgentTimeoutException;
import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.infrastructure.Infrastructure;
import org.jboss.logging.Logger;
import org.springaicommunity.claude.agent.sdk.ClaudeAsyncClient;
import org.springaicommunity.claude.agent.sdk.ClaudeClient;
import org.springaicommunity.claude.agent.sdk.mcp.McpServerConfig;
import org.springaicommunity.claude.agent.sdk.types.Message;
import reactor.adapter.JdkFlowAdapter;
import reactor.core.publisher.Flux;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

public class ClaudeAgentClient {

    private static final Logger LOG = Logger.getLogger(ClaudeAgentClient.class);

    private final ClaudeAgentProperties properties;
    private final Semaphore semaphore;
    private final CopyOnWriteArraySet<ClaudeAsyncClient> activeSessions;
    private final ScheduledExecutorService timeoutScheduler;
    private final Function<AgentSessionConfig, Multi<AgentEvent>> streamFactory;
    private volatile boolean binaryAvailable = true;

    public ClaudeAgentClient(ClaudeAgentProperties properties) {
        this.properties = properties;
        int maxSessions = properties.maxConcurrentSessions();
        if (maxSessions < 0) {
            throw new IllegalStateException(
                "casehub.platform.agent.claude.max-concurrent-sessions must be >= 0, got " + maxSessions);
        }
        this.semaphore = new Semaphore(maxSessions == 0 ? Integer.MAX_VALUE : maxSessions);
        this.activeSessions = new CopyOnWriteArraySet<>();
        this.timeoutScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "casehub-agent-timeout");
            t.setDaemon(true);
            return t;
        });
        this.streamFactory = null;
    }

    public ClaudeAgentClient(ClaudeAgentProperties properties,
                             Function<AgentSessionConfig, Multi<AgentEvent>> streamFactory) {
        this.properties = properties;
        int maxSessions = properties.maxConcurrentSessions();
        if (maxSessions < 0) {
            throw new IllegalStateException(
                "casehub.platform.agent.claude.max-concurrent-sessions must be >= 0, got " + maxSessions);
        }
        this.semaphore = new Semaphore(maxSessions == 0 ? Integer.MAX_VALUE : maxSessions);
        this.activeSessions = new CopyOnWriteArraySet<>();
        this.timeoutScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "casehub-agent-timeout");
            t.setDaemon(true);
            return t;
        });
        this.streamFactory = streamFactory;
    }

    int availablePermits() {
        return semaphore.availablePermits();
    }

    public void validateBinary() {
        String binary = properties.binaryPath().orElse("claude");
        try {
            Process process  = new ProcessBuilder(binary, "--version").start();
            boolean finished = process.waitFor(10, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                LOG.warnf("claude binary probe timed out after 10s: %s — agent features disabled", binary);
                binaryAvailable = false;
                return;
            }
            int exitCode = process.exitValue();
            if (exitCode != 0) {
                LOG.warnf("claude binary at '%s' exited with code %d — agent features disabled", binary, exitCode);
                binaryAvailable = false;
                return;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            LOG.warnf("Interrupted while probing claude binary: %s — agent features disabled", binary);
            binaryAvailable = false;
            return;
        } catch (IOException e) {
            LOG.warnf("claude binary not found at '%s' — agent features disabled. " +
                      "Install Claude Code CLI or set casehub.platform.agent.claude.binary-path", binary);
            binaryAvailable = false;
            return;
        }
        LOG.infof("claude binary resolved at '%s'. Authentication not verified — " +
                  "AgentProcessException will surface on first invocation if unauthenticated.",
                  binary);
    }

    public Multi<AgentEvent> run(AgentSessionConfig config) {
        if (!binaryAvailable) {
            return Multi.createFrom().failure(
                    new IllegalStateException("Claude CLI binary not available — agent features are disabled"));
        }
        if (!semaphore.tryAcquire()) {
            return Multi.createFrom().failure(
                    new AgentSessionLimitException(properties.maxConcurrentSessions()));
        }
        try {
            return buildEventStream(config)
                           .runSubscriptionOn(Infrastructure.getDefaultWorkerPool())
                           .onCompletion().invoke(semaphore::release)
                           .onFailure().invoke(t -> semaphore.release())
                           .onCancellation().invoke(semaphore::release);
        } catch (Exception e) {
            semaphore.release();
            return Multi.createFrom().failure(e);
        }
    }

    Multi<AgentEvent> buildEventStream(AgentSessionConfig config) {
        if (streamFactory != null) {
            return streamFactory.apply(config);
        }

        Duration effectiveTimeout = config.timeout() != null
            ? config.timeout()
            : properties.defaultTimeout();

        ClaudeClient.AsyncSpec builder = ClaudeClient.async()
            .workingDirectory(Path.of(System.getProperty("user.dir")))
            .systemPrompt(config.systemPrompt());

        properties.binaryPath().ifPresent(builder::claudePath);

        Map<String, McpServerConfig> sdkMcpServers = toSdkMcpServers(config.mcpServers());
        if (!sdkMcpServers.isEmpty()) {
            builder.mcpServers(sdkMcpServers);
        }

        ClaudeAsyncClient sdkClient = builder.build();
        activeSessions.add(sdkClient);

        AtomicBoolean timedOut = new AtomicBoolean(false);

        ScheduledFuture<?> timeoutFuture = timeoutScheduler.schedule(() -> {
            if (timedOut.compareAndSet(false, true)) {
                sdkClient.close().subscribe();
            }
        }, effectiveTimeout.toMillis(), TimeUnit.MILLISECONDS);

        try {
            Flux<Message> messageFlux = sdkClient.connect(config.userPrompt()).messages();
            final AtomicInteger toolIndex = new AtomicInteger(0);

            Multi<AgentEvent> eventStream = Multi.createFrom()
                .publisher(JdkFlowAdapter.publisherToFlowPublisher(messageFlux))
                .onItem().transformToMultiAndConcatenate(
                    msg -> Multi.createFrom().iterable(MessageEventMapper.toEvents(msg, toolIndex)))
                .onFailure().transform(e ->
                    timedOut.get()
                        ? new AgentTimeoutException(effectiveTimeout)
                        : new AgentProcessException(
                            Objects.toString(e.getMessage(), e.getClass().getSimpleName()), e))
                .onCompletion().invoke(() -> {
                    logCorrelationEvent(config, "completed");
                    cleanup(timeoutFuture, sdkClient);
                })
                .onFailure().invoke(t -> {
                    logCorrelationEvent(config, "failed");
                    cleanup(timeoutFuture, sdkClient);
                })
                .onCancellation().invoke(() -> {
                    logCorrelationEvent(config, "cancelled");
                    cleanup(timeoutFuture, sdkClient);
                });

            if (config.correlationId() != null) {
                LOG.infof("Agent session started [correlationId=%s]", config.correlationId());
            }

            return eventStream;
        } catch (Exception e) {
            timeoutFuture.cancel(false);
            activeSessions.remove(sdkClient);
            try {
                sdkClient.close().subscribe();
            } catch (Exception ignored) {}
            throw e;
        }
    }

    private void logCorrelationEvent(AgentSessionConfig config, String event) {
        if (config.correlationId() != null) {
            LOG.infof("agent session %s correlationId=%s", event, config.correlationId());
        }
    }

    private void cleanup(ScheduledFuture<?> timeoutFuture, ClaudeAsyncClient sdkClient) {
        timeoutFuture.cancel(false);
        activeSessions.remove(sdkClient);
        try {
            sdkClient.close().subscribe();
        } catch (Exception ignored) {}
    }

    private static Map<String, McpServerConfig> toSdkMcpServers(
            List<AgentMcpServer> mcpServers) {
        if (mcpServers == null || mcpServers.isEmpty()) {
            return Map.of();
        }
        Map<String, McpServerConfig> result = new HashMap<>();
        int stdioIdx = 0, sseIdx = 0, httpIdx = 0;
        for (AgentMcpServer server : mcpServers) {
            switch (server) {
                case AgentMcpServer.Stdio s ->
                    result.put("stdio-" + stdioIdx++,
                        new McpServerConfig.McpStdioServerConfig(
                            s.command(), s.args(), s.env()));
                case AgentMcpServer.Sse s ->
                    result.put("sse-" + sseIdx++,
                        new McpServerConfig.McpSseServerConfig(s.url(), s.headers()));
                case AgentMcpServer.Http s ->
                    result.put("http-" + httpIdx++,
                        new McpServerConfig.McpHttpServerConfig(s.url(), s.headers()));
            }
        }
        return result;
    }

    public AgentSession openSession(final AgentSessionInit init) {
        if (!semaphore.tryAcquire()) {
            throw new AgentSessionLimitException(properties.maxConcurrentSessions());
        }
        try {
            if (streamFactory != null) {
                return new ClaudeAgentSession(properties,
                    prompt -> Multi.createFrom().empty(),
                    semaphore, activeSessions, timeoutScheduler);
            }

            final Duration effectiveTimeout = init.timeout() != null
                ? init.timeout()
                : properties.defaultTimeout();

            final ClaudeClient.AsyncSpec builder = ClaudeClient.async()
                .workingDirectory(Path.of(System.getProperty("user.dir")))
                .systemPrompt(init.systemPrompt());
            properties.binaryPath().ifPresent(builder::claudePath);
            final Map<String, McpServerConfig> sdkMcpServers = toSdkMcpServers(init.mcpServers());
            if (!sdkMcpServers.isEmpty()) builder.mcpServers(sdkMcpServers);

            final ClaudeAsyncClient sdkClient = builder.build();
            activeSessions.add(sdkClient);
            if (init.correlationId() != null) {
                LOG.infof("Agent session opened [correlationId=%s]", init.correlationId());
            }
            return new ClaudeAgentSession(sdkClient, effectiveTimeout, semaphore,
                activeSessions, timeoutScheduler, init.correlationId());
        } catch (final Exception e) {
            semaphore.release();
            throw e;
        }
    }

    public void shutdown() {
        timeoutScheduler.shutdownNow();
        activeSessions.forEach(c -> {
            try {
                c.close().subscribe();
            } catch (Exception ignored) {}
        });
    }
}
