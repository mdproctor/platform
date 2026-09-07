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
import org.jboss.logging.Logger;

import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

public class OpenAiAgentBackend implements AgentBackend {

    private static final Logger LOG = Logger.getLogger(OpenAiAgentBackend.class);

    private final OpenAiAgentProperties properties;
    private final Semaphore semaphore;
    private final ScheduledExecutorService timeoutScheduler;
    private final Function<AgentSessionConfig, Multi<AgentEvent>> streamFactory;
    private final com.openai.client.OpenAIClient                  openAiClient;

    public OpenAiAgentBackend(OpenAiAgentProperties properties) {
        this.properties = properties;
        int maxSessions = properties.maxConcurrentSessions();
        if (maxSessions < 0) {
            throw new IllegalStateException(
                    "casehub.platform.agent.openai.max-concurrent-sessions must be >= 0, got " + maxSessions);
        }
        this.semaphore        = new Semaphore(maxSessions == 0 ? Integer.MAX_VALUE : maxSessions);
        this.timeoutScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "casehub-agent-openai-timeout");
            t.setDaemon(true);
            return t;
        });
        this.streamFactory    = null;
        com.openai.client.okhttp.OpenAIOkHttpClient.Builder clientBuilder =
                com.openai.client.okhttp.OpenAIOkHttpClient.builder();
        properties.apiKey().ifPresent(clientBuilder::apiKey);
        this.openAiClient = clientBuilder.build();
    }

    public OpenAiAgentBackend(OpenAiAgentProperties properties,
                              Function<AgentSessionConfig, Multi<AgentEvent>> streamFactory) {
        this.properties = properties;
        int maxSessions = properties.maxConcurrentSessions();
        if (maxSessions < 0) {
            throw new IllegalStateException(
                    "casehub.platform.agent.openai.max-concurrent-sessions must be >= 0, got " + maxSessions);
        }
        this.semaphore        = new Semaphore(maxSessions == 0 ? Integer.MAX_VALUE : maxSessions);
        this.timeoutScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "casehub-agent-openai-timeout");
            t.setDaemon(true);
            return t;
        });
        this.streamFactory    = streamFactory;
        this.openAiClient     = null;
    }

    int availablePermits() {
        return semaphore.availablePermits();
    }

    @Override
    public String key() {
        return "openai";
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
        throw new UnsupportedOperationException("OpenAI multi-turn sessions not yet implemented");
    }

    Multi<AgentEvent> buildEventStream(AgentSessionConfig config) {
        Duration effectiveTimeout = config.timeout() != null
                                    ? config.timeout()
                                    : properties.defaultTimeout();

        String modelId     = config.model() != null ? config.model() : properties.defaultModel();
        long   startTimeMs = System.currentTimeMillis();

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
            AtomicBoolean                                        timedOut  = new AtomicBoolean(false);

            ScheduledFuture<?> timeoutFuture = timeoutScheduler.schedule(() -> {
                if (timedOut.compareAndSet(false, true)) {
                    StreamResponse<?> s = streamRef.get();
                    if (s != null) {
                        try {s.close();} catch (Exception ignored) {}
                    }
                }
            }, effectiveTimeout.toMillis(), TimeUnit.MILLISECONDS);

            try {
                StreamResponse<ChatCompletionChunk> stream =
                        openAiClient.chat().completions().createStreaming(params);
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
                    try {s.close();} catch (Exception ignored) {}
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
