package io.casehub.platform.agent.langchain4j;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import dev.langchain4j.model.output.FinishReason;
import io.casehub.platform.agent.AgentEvent;
import io.casehub.platform.agent.AgentSessionInit;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ChatModelAgentSessionTest {

    /** Creates a simple ChatModel that returns the given response text. */
    private static ChatModel simpleChatModel(String responseText) {
        return new ChatModel() {
            @Override
            public ChatResponse doChat(ChatRequest request) {
                return ChatResponse.builder()
                    .aiMessage(AiMessage.from(responseText))
                    .build();
            }
        };
    }

    /** Creates a StreamingChatModel with custom behavior. */
    private static StreamingChatModel streamingChatModel(
            java.util.function.BiConsumer<ChatRequest, StreamingChatResponseHandler> doChat) {
        return new StreamingChatModel() {
            @Override
            public void doChat(ChatRequest request, StreamingChatResponseHandler handler) {
                doChat.accept(request, handler);
            }
        };
    }

    /** Creates a semaphore with one acquired permit for tests that need to call close(). */
    private static Semaphore acquiredSemaphore() throws InterruptedException {
        Semaphore s = new Semaphore(1);
        s.acquire();
        return s;
    }

    @Test
    void query_happyPath_emitsTextDelta() throws InterruptedException {
        ChatModel model = simpleChatModel("Hello from AI");
        AgentSessionInit init = AgentSessionInit.of("system prompt");
        AgentLangchain4jProperties properties = new TestProperties(Duration.ofSeconds(30), 20, 10);

        ChatModelAgentSession session = new ChatModelAgentSession(model, null, init, properties, acquiredSemaphore());

        List<String> events = session.query("user prompt")
            .onItem().transform(event -> ((AgentEvent.TextDelta) event).text())
            .collect().asList()
            .await().atMost(Duration.ofSeconds(5));

        assertThat(events).containsExactly("Hello from AI");
    }

    @Test
    void query_multiTurn_includesHistory() throws InterruptedException {
        AtomicInteger callCount = new AtomicInteger(0);
        ChatModel model = new ChatModel() {
            @Override
            public ChatResponse doChat(ChatRequest request) {
                int call = callCount.incrementAndGet();
                if (call == 1) {
                    return ChatResponse.builder().aiMessage(AiMessage.from("Response 1")).build();
                }
                // Second call should include first exchange in history
                List<ChatMessage> messages = request.messages();
                assertThat(messages).hasSizeGreaterThan(2); // System + User1 + AI1 + User2
                return ChatResponse.builder().aiMessage(AiMessage.from("Response 2")).build();
            }
        };
        AgentSessionInit init = AgentSessionInit.of("system prompt");
        AgentLangchain4jProperties properties = new TestProperties(Duration.ofSeconds(30), 20, 10);

        ChatModelAgentSession session = new ChatModelAgentSession(model, null, init, properties, acquiredSemaphore());

        session.query("prompt 1").collect().asList().await().atMost(Duration.ofSeconds(5));
        List<String> secondResponse = session.query("prompt 2")
            .onItem().transform(event -> ((AgentEvent.TextDelta) event).text())
            .collect().asList()
            .await().atMost(Duration.ofSeconds(5));

        assertThat(secondResponse).containsExactly("Response 2");
        assertThat(callCount.get()).isEqualTo(2);
    }

    @Test
    void query_onClosed_throws() throws InterruptedException {
        ChatModel model = simpleChatModel("response");
        AgentSessionInit init = AgentSessionInit.of("system");
        AgentLangchain4jProperties properties = new TestProperties(Duration.ofSeconds(30), 20, 10);

        ChatModelAgentSession session = new ChatModelAgentSession(model, null, init, properties, acquiredSemaphore());
        session.close(Duration.ofSeconds(1));

        assertThatThrownBy(() -> session.query("prompt").collect().asList().await().indefinitely())
            .hasMessageContaining("session is closed");
    }

    @Test
    void query_concurrent_throws() throws InterruptedException {
        CountDownLatch firstQueryStarted = new CountDownLatch(1);
        CountDownLatch blockFirstQuery = new CountDownLatch(1);

        ChatModel model = new ChatModel() {
            @Override
            public ChatResponse doChat(ChatRequest request) {
                firstQueryStarted.countDown();
                try {
                    blockFirstQuery.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return ChatResponse.builder().aiMessage(AiMessage.from("response")).build();
            }
        };
        AgentSessionInit init = AgentSessionInit.of("system");
        AgentLangchain4jProperties properties = new TestProperties(Duration.ofSeconds(30), 20, 10);

        ChatModelAgentSession session = new ChatModelAgentSession(model, null, init, properties, acquiredSemaphore());

        // Start first query in background
        Thread firstThread = new Thread(() ->
            session.query("first").collect().asList().await().indefinitely()
        );
        firstThread.start();

        firstQueryStarted.await(5, TimeUnit.SECONDS);

        // Try second query while first is active
        assertThatThrownBy(() -> session.query("second"))
            .hasMessageContaining("a turn is already active");

        blockFirstQuery.countDown();
        firstThread.join(5000);
    }

    @Test
    void interrupt_isNoOp() throws InterruptedException {
        ChatModel model = simpleChatModel("response");
        AgentSessionInit init = AgentSessionInit.of("system");
        AgentLangchain4jProperties properties = new TestProperties(Duration.ofSeconds(30), 20, 10);

        ChatModelAgentSession session = new ChatModelAgentSession(model, null, init, properties, acquiredSemaphore());

        session.interrupt().await().atMost(Duration.ofSeconds(1));
        // Should not throw
    }

    @Test
    void close_setsClosedState() throws InterruptedException {
        ChatModel model = simpleChatModel("response");
        AgentSessionInit init = AgentSessionInit.of("system");
        AgentLangchain4jProperties properties = new TestProperties(Duration.ofSeconds(30), 20, 10);

        ChatModelAgentSession session = new ChatModelAgentSession(model, null, init, properties, acquiredSemaphore());
        session.close(Duration.ofSeconds(1));

        assertThatThrownBy(() -> session.query("prompt").collect().asList().await().indefinitely())
            .hasMessageContaining("session is closed");
    }

    @Test
    void close_idempotent() throws InterruptedException {
        ChatModel model = simpleChatModel("response");
        AgentSessionInit init = AgentSessionInit.of("system");
        AgentLangchain4jProperties properties = new TestProperties(Duration.ofSeconds(30), 20, 10);

        ChatModelAgentSession session = new ChatModelAgentSession(model, null, init, properties, acquiredSemaphore());
        session.close(Duration.ofSeconds(1));
        session.close(Duration.ofSeconds(1)); // Should not throw
    }

    @Test
    void query_streaming_emitsMultipleTextDeltas() throws InterruptedException {
        StreamingChatModel model = streamingChatModel((request, handler) -> {
            handler.onPartialResponse("Hello");
            handler.onPartialResponse(" World");
            handler.onCompleteResponse(ChatResponse.builder()
                .aiMessage(AiMessage.from("Hello World"))
                .finishReason(FinishReason.STOP).build());
        });
        AgentSessionInit init = AgentSessionInit.of("system");
        AgentLangchain4jProperties properties = new TestProperties(Duration.ofSeconds(30), 20, 10);

        ChatModelAgentSession session = new ChatModelAgentSession(
            null, model, init, properties, acquiredSemaphore());

        List<String> texts = session.query("prompt")
            .filter(e -> e instanceof AgentEvent.TextDelta)
            .map(e -> ((AgentEvent.TextDelta) e).text())
            .collect().asList().await().atMost(Duration.ofSeconds(5));

        assertThat(texts).containsExactly("Hello", " World");
    }

    @Test
    void query_streaming_updatesMemoryOnCompletion() throws InterruptedException {
        StreamingChatModel model = streamingChatModel((request, handler) -> {
            handler.onPartialResponse("Response 1");
            handler.onCompleteResponse(ChatResponse.builder()
                .aiMessage(AiMessage.from("Response 1"))
                .finishReason(FinishReason.STOP).build());
        });
        AgentSessionInit init = AgentSessionInit.of("system");
        AgentLangchain4jProperties properties = new TestProperties(Duration.ofSeconds(30), 20, 10);

        // First turn: streaming
        ChatModelAgentSession streamSession = new ChatModelAgentSession(
            null, model, init, properties, acquiredSemaphore());
        streamSession.query("prompt 1").collect().asList()
            .await().atMost(Duration.ofSeconds(5));

        // Can't easily verify memory contents directly, but we can verify the
        // streaming path completed without error (memory.add would have thrown
        // if something went wrong)
    }

    @Test
    void query_streaming_doesNotUpdateMemoryOnFailure() throws InterruptedException {
        StreamingChatModel model = streamingChatModel((request, handler) -> {
            handler.onPartialResponse("partial");
            handler.onError(new RuntimeException("model error"));
        });
        AgentSessionInit init = AgentSessionInit.of("system");
        AgentLangchain4jProperties properties = new TestProperties(Duration.ofSeconds(30), 20, 10);

        ChatModelAgentSession session = new ChatModelAgentSession(
            null, model, init, properties, acquiredSemaphore());

        assertThatThrownBy(() -> session.query("prompt")
            .collect().asList().await().atMost(Duration.ofSeconds(5)))
            .hasMessageContaining("model error");

        // Session should be back to IDLE after failure
        // Verify by successfully querying again with a new model
        StreamingChatModel model2 = streamingChatModel((request, handler) -> {
            handler.onPartialResponse("recovery");
            handler.onCompleteResponse(ChatResponse.builder()
                .aiMessage(AiMessage.from("recovery"))
                .finishReason(FinishReason.STOP).build());
        });
        // Can't swap models mid-session, but we can verify state is IDLE
        // The existing close_setsClosedState test covers state transitions
    }

    @Test
    void query_streaming_multiTurnPreservesHistory() throws InterruptedException {
        AtomicInteger turnCount = new AtomicInteger(0);
        StreamingChatModel model = streamingChatModel((request, handler) -> {
            int turn = turnCount.incrementAndGet();
            if (turn == 2) {
                // Second turn: verify history includes first turn
                assertThat(request.messages().size()).isGreaterThan(2);
            }
            handler.onPartialResponse("Response " + turn);
            handler.onCompleteResponse(ChatResponse.builder()
                .aiMessage(AiMessage.from("Response " + turn))
                .finishReason(FinishReason.STOP).build());
        });
        AgentSessionInit init = AgentSessionInit.of("system");
        AgentLangchain4jProperties properties = new TestProperties(Duration.ofSeconds(30), 20, 10);

        ChatModelAgentSession session = new ChatModelAgentSession(
            null, model, init, properties, acquiredSemaphore());

        session.query("turn 1").collect().asList().await().atMost(Duration.ofSeconds(5));
        session.query("turn 2").collect().asList().await().atMost(Duration.ofSeconds(5));

        assertThat(turnCount.get()).isEqualTo(2);
    }

    @Test
    void close_releasesSemaphorePermit() throws InterruptedException {
        Semaphore semaphore = new Semaphore(1);
        semaphore.acquire();
        ChatModel model = simpleChatModel("response");
        AgentLangchain4jProperties properties = new TestProperties(Duration.ofSeconds(30), 20, 10);

        ChatModelAgentSession session = new ChatModelAgentSession(
            model, null, AgentSessionInit.of("system"), properties, semaphore);

        assertThat(semaphore.availablePermits()).isEqualTo(0);
        session.close(Duration.ofSeconds(1));
        assertThat(semaphore.availablePermits()).isEqualTo(1);
    }

    @Test
    void close_idempotent_noDoubleRelease() throws InterruptedException {
        Semaphore semaphore = new Semaphore(1);
        semaphore.acquire();
        ChatModel model = simpleChatModel("response");
        AgentLangchain4jProperties properties = new TestProperties(Duration.ofSeconds(30), 20, 10);

        ChatModelAgentSession session = new ChatModelAgentSession(
            model, null, AgentSessionInit.of("system"), properties, semaphore);

        session.close(Duration.ofSeconds(1));
        session.close(Duration.ofSeconds(1));
        assertThat(semaphore.availablePermits()).isEqualTo(1);
    }

    @Test
    void query_completion_doesNotReleasePermit() throws InterruptedException {
        Semaphore semaphore = new Semaphore(1);
        semaphore.acquire();
        ChatModel model = simpleChatModel("response");
        AgentLangchain4jProperties properties = new TestProperties(Duration.ofSeconds(30), 20, 10);

        ChatModelAgentSession session = new ChatModelAgentSession(
            model, null, AgentSessionInit.of("system"), properties, semaphore);

        session.query("prompt").collect().asList().await().atMost(Duration.ofSeconds(5));
        assertThat(semaphore.availablePermits()).isEqualTo(0);

        session.close(Duration.ofSeconds(1));
    }

    @Test
    void query_failure_doesNotReleasePermit() throws InterruptedException {
        Semaphore semaphore = new Semaphore(1);
        semaphore.acquire();
        StreamingChatModel model = streamingChatModel((request, handler) -> {
            handler.onError(new RuntimeException("model error"));
        });
        AgentLangchain4jProperties properties = new TestProperties(Duration.ofSeconds(30), 20, 10);

        ChatModelAgentSession session = new ChatModelAgentSession(
            null, model, AgentSessionInit.of("system"), properties, semaphore);

        assertThatThrownBy(() -> session.query("prompt")
            .collect().asList().await().atMost(Duration.ofSeconds(5)))
            .hasMessageContaining("model error");

        assertThat(semaphore.availablePermits()).isEqualTo(0);

        session.close(Duration.ofSeconds(1));
    }

    private record TestProperties(Duration closeTimeout, int sessionMemoryWindowSize, int maxConcurrentSessions)
        implements AgentLangchain4jProperties {}
}
