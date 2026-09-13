package io.casehub.platform.agent.openai;

import io.casehub.platform.agent.AgentBackend;
import io.casehub.platform.agent.AgentEvent;
import io.casehub.platform.agent.AgentProcessException;
import io.casehub.platform.agent.AgentSession;
import io.casehub.platform.agent.AgentSessionConfig;
import io.casehub.platform.agent.AgentSessionInit;
import io.casehub.platform.agent.AgentSessionLimitException;
import io.casehub.platform.agent.AgentTimeoutException;
import com.openai.core.http.StreamResponse;
import com.openai.models.ChatModel;
import com.openai.models.chat.completions.ChatCompletionChunk;
import com.openai.models.chat.completions.ChatCompletionCreateParams;
import com.openai.models.chat.completions.ChatCompletionStreamOptions;
import com.openai.models.chat.completions.ChatCompletionSystemMessageParam;
import com.openai.models.chat.completions.ChatCompletionUserMessageParam;
import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.infrastructure.Infrastructure;
import jakarta.annotation.PreDestroy;

import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

public abstract class AbstractOpenAiSdkBackend implements AgentBackend {

    private final Semaphore semaphore;
    private final ScheduledExecutorService timeoutScheduler;
    private final Function<AgentSessionConfig, Multi<AgentEvent>> streamFactory;

    protected AbstractOpenAiSdkBackend(int maxConcurrentSessions,
                                        Function<AgentSessionConfig, Multi<AgentEvent>> streamFactory) {
        if (maxConcurrentSessions < 0) {
            throw new IllegalStateException(
                "max-concurrent-sessions must be >= 0, got " + maxConcurrentSessions);
        }
        this.semaphore = new Semaphore(
            maxConcurrentSessions == 0 ? Integer.MAX_VALUE : maxConcurrentSessions);
        this.timeoutScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "casehub-agent-" + key() + "-timeout");
            t.setDaemon(true);
            return t;
        });
        this.streamFactory = streamFactory;
    }

    protected AbstractOpenAiSdkBackend() {
        this.semaphore = null;
        this.timeoutScheduler = null;
        this.streamFactory = null;
    }

    protected abstract com.openai.client.OpenAIClient openAiClient();
    protected abstract Duration defaultTimeout();
    protected abstract String defaultModel();

    int availablePermits() {
        return semaphore.availablePermits();
    }

    @Override
    public Multi<AgentEvent> invoke(AgentSessionConfig config) {
        if (!semaphore.tryAcquire()) {
            return Multi.createFrom().failure(
                new AgentSessionLimitException(semaphore.availablePermits()));
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
        throw new UnsupportedOperationException(key() + " multi-turn sessions not yet implemented");
    }

    Multi<AgentEvent> buildEventStream(AgentSessionConfig config) {
        Duration effectiveTimeout = config.timeout() != null
            ? config.timeout()
            : defaultTimeout();

        String modelId = config.model() != null ? config.model() : defaultModel();
        long startTimeMs = System.currentTimeMillis();

        ChatCompletionCreateParams params = ChatCompletionCreateParams.builder()
            .model(ChatModel.of(modelId))
            .addMessage(ChatCompletionSystemMessageParam.builder()
                .content(config.systemPrompt())
                .build())
            .addMessage(ChatCompletionUserMessageParam.builder()
                .content(config.userPrompt())
                .build())
            .streamOptions(ChatCompletionStreamOptions.builder().includeUsage(true).build())
            .build();

        return Multi.createFrom().emitter(emitter -> {
            AtomicReference<StreamResponse<ChatCompletionChunk>> streamRef = new AtomicReference<>();
            AtomicBoolean timedOut = new AtomicBoolean(false);

            ScheduledFuture<?> timeoutFuture = timeoutScheduler.schedule(() -> {
                if (timedOut.compareAndSet(false, true)) {
                    StreamResponse<?> s = streamRef.get();
                    if (s != null) {
                        try { s.close(); } catch (Exception ignored) {}
                    }
                }
            }, effectiveTimeout.toMillis(), TimeUnit.MILLISECONDS);

            try {
                StreamResponse<ChatCompletionChunk> stream =
                    openAiClient().chat().completions().createStreaming(params);
                streamRef.set(stream);
                stream.stream().forEach(chunk ->
                    OpenAiEventMapper.toEvents(chunk, startTimeMs).forEach(emitter::emit));
                emitter.complete();
            } catch (Exception e) {
                if (timedOut.get()) {
                    emitter.fail(new AgentTimeoutException(effectiveTimeout));
                } else {
                    emitter.fail(new AgentProcessException(
                        java.util.Objects.toString(e.getMessage(), e.getClass().getSimpleName()), e));
                }
            } finally {
                timeoutFuture.cancel(false);
                StreamResponse<?> s = streamRef.get();
                if (s != null) {
                    try { s.close(); } catch (Exception ignored) {}
                }
            }
        });
    }

    @PreDestroy
    void shutdown() {
        if (timeoutScheduler != null) {
            timeoutScheduler.shutdownNow();
        }
    }
}
