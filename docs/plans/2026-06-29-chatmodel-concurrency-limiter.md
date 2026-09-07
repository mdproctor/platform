# ChatModelAgentProvider Concurrency Limiter Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a configurable semaphore to `ChatModelAgentProvider` that gates `invoke()` and `openSession()` with fail-fast backpressure, matching the `ClaudeAgentClient` pattern.

**Architecture:** Semaphore initialized in `@PostConstruct init()` from config. `invoke()` acquires before building the Multi, releases via three-path handlers (completion/failure/cancellation) or catch block (mutually exclusive). `openSession()` acquires and passes the semaphore to `ChatModelAgentSession`, which releases on `close()` via `getAndSet(State.CLOSED)`.

**Tech Stack:** Java 21, Quarkus CDI, SmallRye Mutiny, JUnit 5, AssertJ, Mockito

## Global Constraints

- `maxConcurrentSessions` must be `>= 1` — validated at startup via `IllegalStateException`
- Semaphore is always non-null — created before the `disabled` check in `init()`
- `disabled` guard precedes all semaphore operations in `invoke()` and `openSession()`
- Three-path release and catch-path release are mutually exclusive — never both
- Session query failure/cancellation → IDLE (not CLOSED) — no semaphore release per query
- `close()` uses `getAndSet(State.CLOSED)` for idempotent double-close prevention

---

### Task 1: invoke() concurrency limiter

Config property, semaphore initialization with validation, `availablePermits()`, and `invoke()` acquire + three-path release.

**Files:**
- Modify: `agent-langchain4j/src/main/java/io/casehub/platform/agent/langchain4j/AgentLangchain4jProperties.java`
- Modify: `agent-langchain4j/src/main/java/io/casehub/platform/agent/langchain4j/ChatModelAgentProvider.java`
- Modify: `agent-langchain4j/src/test/java/io/casehub/platform/agent/langchain4j/ChatModelAgentProviderTest.java`

**Interfaces:**
- Produces: `AgentLangchain4jProperties.maxConcurrentSessions()` returning `int` (default 10)
- Produces: `ChatModelAgentProvider.availablePermits()` returning `int` (package-private, for tests)

- [ ] **Step 1: Add `maxConcurrentSessions` to `AgentLangchain4jProperties`**

```java
@WithDefault("10")
int maxConcurrentSessions();
```

Append after `sessionMemoryWindowSize()` (line 14).

- [ ] **Step 2: Update test `setUp()` to stub `maxConcurrentSessions`**

In `ChatModelAgentProviderTest.setUp()`, add after the existing `when` calls (line 48):

```java
when(properties.maxConcurrentSessions()).thenReturn(10);
```

- [ ] **Step 3: Write the failing test — init validates `maxConcurrentSessions >= 1`**

Add to `ChatModelAgentProviderTest`:

```java
@Test
void init_withZeroMaxConcurrentSessions_throws() {
    when(properties.maxConcurrentSessions()).thenReturn(0);
    ChatModel chatModel = mock(ChatModel.class);
    Instance<ChatModel> instance = instanceOf(chatModel);
    provider.chatModels = instance;
    provider.properties = properties;

    assertThatThrownBy(() -> provider.init())
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("max-concurrent-sessions must be >= 1");
}

@Test
void init_withNegativeMaxConcurrentSessions_throws() {
    when(properties.maxConcurrentSessions()).thenReturn(-1);
    ChatModel chatModel = mock(ChatModel.class);
    Instance<ChatModel> instance = instanceOf(chatModel);
    provider.chatModels = instance;
    provider.properties = properties;

    assertThatThrownBy(() -> provider.init())
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("max-concurrent-sessions must be >= 1");
}
```

- [ ] **Step 4: Run tests to verify they fail**

Run: `mvn --batch-mode test -pl agent-langchain4j -Dtest=ChatModelAgentProviderTest#init_withZeroMaxConcurrentSessions_throws+init_withNegativeMaxConcurrentSessions_throws`
Expected: FAIL — `maxConcurrentSessions()` not yet on interface, and no validation exists

- [ ] **Step 5: Implement semaphore field, init validation, and availablePermits**

In `ChatModelAgentProvider`, add import and field:

```java
import java.util.concurrent.Semaphore;
import io.casehub.platform.agent.AgentSessionLimitException;
```

Add field after `disabled` (line 40):

```java
Semaphore semaphore;
```

Replace `init()` method (lines 44-58) with:

```java
@PostConstruct
void init() {
    int maxSessions = properties.maxConcurrentSessions();
    if (maxSessions < 1) {
        throw new IllegalStateException(
            "casehub.platform.agent.langchain4j.max-concurrent-sessions must be >= 1, got " + maxSessions);
    }
    semaphore = new Semaphore(maxSessions);

    List<ChatModel> candidates = chatModels.select(Default.Literal.INSTANCE).stream()
        .filter(m -> !(m instanceof AgentProviderChatModel))
        .toList();
    if (candidates.isEmpty()) {
        LOG.warn("ChatModelAgentProvider: no ChatModel bean available — " +
                 "add a quarkus-langchain4j provider to activate. " +
                 "AgentProvider calls will fail until a ChatModel is present.");
        disabled = true;
        return;
    }
    chatModel = candidates.get(0);
    streamingChatModel = (chatModel instanceof StreamingChatModel s) ? s : null;
}

int availablePermits() {
    return semaphore.availablePermits();
}
```

- [ ] **Step 6: Run validation tests to verify they pass**

Run: `mvn --batch-mode test -pl agent-langchain4j -Dtest=ChatModelAgentProviderTest#init_withZeroMaxConcurrentSessions_throws+init_withNegativeMaxConcurrentSessions_throws`
Expected: PASS

- [ ] **Step 7: Run all existing tests to verify no regressions**

Run: `mvn --batch-mode test -pl agent-langchain4j -Dtest=ChatModelAgentProviderTest`
Expected: PASS — existing tests unaffected (mock stubs `maxConcurrentSessions()` returning 10)

- [ ] **Step 8: Write the failing test — invoke at capacity returns AgentSessionLimitException**

```java
@Test
void invoke_atCapacity_returnsAgentSessionLimitException() {
    when(properties.maxConcurrentSessions()).thenReturn(1);
    ChatModel chatModel = mock(ChatModel.class);
    when(chatModel.chat(any(ChatRequest.class))).thenReturn(
        ChatResponse.builder().aiMessage(AiMessage.from("response")).build());
    injectFields(chatModel);

    // Exhaust the single permit
    semaphoreAcquire();

    AgentSessionConfig config = AgentSessionConfig.of("", "test");
    Multi<AgentEvent> result = provider.invoke(config);

    assertThatThrownBy(() -> result.collect().asList().await().indefinitely())
        .isInstanceOf(AgentSessionLimitException.class)
        .hasMessageContaining("1 active sessions");

    // Release the manually acquired permit
    provider.semaphore.release();
}
```

Add helper method:

```java
private void semaphoreAcquire() {
    try {
        provider.semaphore.acquire();
    } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new RuntimeException(e);
    }
}
```

- [ ] **Step 9: Write the failing test — invoke releases permit on completion**

```java
@Test
void invoke_releasesPermitOnCompletion() {
    when(properties.maxConcurrentSessions()).thenReturn(1);
    ChatModel chatModel = mock(ChatModel.class);
    when(chatModel.chat(any(ChatRequest.class))).thenReturn(
        ChatResponse.builder().aiMessage(AiMessage.from("response")).build());
    injectFields(chatModel);

    assertThat(provider.availablePermits()).isEqualTo(1);

    provider.invoke(AgentSessionConfig.of("", "test"))
        .collect().asList().await().indefinitely();

    assertThat(provider.availablePermits()).isEqualTo(1);
}
```

- [ ] **Step 10: Write the failing test — invoke releases permit on failure**

```java
@Test
void invoke_releasesPermitOnFailure() {
    when(properties.maxConcurrentSessions()).thenReturn(1);
    ChatModel chatModel = mock(ChatModel.class);
    when(chatModel.chat(any(ChatRequest.class)))
        .thenThrow(new RuntimeException("model error"));
    injectFields(chatModel);

    assertThatThrownBy(() ->
        provider.invoke(AgentSessionConfig.of("", "test"))
            .collect().asList().await().indefinitely())
        .hasMessageContaining("model error");

    assertThat(provider.availablePermits()).isEqualTo(1);
}
```

- [ ] **Step 11: Write the failing test — invoke releases permit on cancellation**

```java
@Test
void invoke_releasesPermitOnCancellation() {
    when(properties.maxConcurrentSessions()).thenReturn(1);
    CountDownLatch modelBlocked = new CountDownLatch(1);
    CountDownLatch modelReached = new CountDownLatch(1);
    ChatModel slowModel = mock(ChatModel.class);
    when(slowModel.chat(any(ChatRequest.class))).thenAnswer(inv -> {
        modelReached.countDown();
        modelBlocked.await(5, TimeUnit.SECONDS);
        return ChatResponse.builder().aiMessage(AiMessage.from("late")).build();
    });
    injectFields(slowModel);

    var cancellable = provider.invoke(AgentSessionConfig.of("", "test"))
        .subscribe().withSubscriber(io.smallrye.mutiny.helpers.test.AssertSubscriber.create(10));

    // Wait for the model call to start, then cancel
    try { modelReached.await(5, TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    cancellable.cancel();
    modelBlocked.countDown();

    // Permit must be released after cancellation
    assertThat(provider.availablePermits()).isEqualTo(1);
}
```

Add imports to test file:

```java
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
```

- [ ] **Step 12: Write the failing test — sync exception releases permit**

```java
@Test
void invoke_syncExceptionReleasesPermit() {
    when(properties.maxConcurrentSessions()).thenReturn(1);
    ChatModel chatModel = mock(ChatModel.class);
    injectFields(chatModel);

    // Force NPE in try block: systemPrompt() returns null → .isEmpty() throws NPE
    AgentSessionConfig config = mock(AgentSessionConfig.class);
    when(config.systemPrompt()).thenReturn(null);

    Multi<AgentEvent> result = provider.invoke(config);
    assertThatThrownBy(() -> result.collect().asList().await().indefinitely())
        .isInstanceOf(NullPointerException.class);

    assertThat(provider.availablePermits()).isEqualTo(1);
}
```

- [ ] **Step 13: Write the failing test — streaming holds permit until stream completes**

```java
@Test
void invoke_streaming_holdsPermitUntilStreamCompletes() {
    when(properties.maxConcurrentSessions()).thenReturn(1);
    CountDownLatch streamStarted = new CountDownLatch(1);
    CountDownLatch completeStream = new CountDownLatch(1);
    ChatModel dualModel = dualModel((request, handler) -> {
        streamStarted.countDown();
        handler.onPartialResponse("chunk");
        try { completeStream.await(5, TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        handler.onCompleteResponse(ChatResponse.builder()
            .aiMessage(AiMessage.from("chunk")).finishReason(FinishReason.STOP).build());
    });
    injectFields(dualModel);

    Thread streamThread = new Thread(() ->
        provider.invoke(AgentSessionConfig.of("", "test"))
            .collect().asList().await().atMost(Duration.ofSeconds(10)));
    streamThread.start();

    try { streamStarted.await(5, TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }

    // Permit held while stream is active
    assertThat(provider.availablePermits()).isEqualTo(0);

    // Second invoke fails while first stream is active
    AgentSessionConfig config2 = AgentSessionConfig.of("", "test2");
    assertThatThrownBy(() ->
        provider.invoke(config2).collect().asList().await().indefinitely())
        .isInstanceOf(AgentSessionLimitException.class);

    completeStream.countDown();
    try { streamThread.join(5000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }

    // Permit released after stream completes
    assertThat(provider.availablePermits()).isEqualTo(1);
}
```

- [ ] **Step 14: Run all new tests to verify they fail**

Run: `mvn --batch-mode test -pl agent-langchain4j -Dtest=ChatModelAgentProviderTest#invoke_atCapacity_returnsAgentSessionLimitException+invoke_releasesPermitOnCompletion+invoke_releasesPermitOnFailure+invoke_releasesPermitOnCancellation+invoke_syncExceptionReleasesPermit+invoke_streaming_holdsPermitUntilStreamCompletes`
Expected: FAIL — no semaphore logic in `invoke()` yet

- [ ] **Step 15: Implement invoke() semaphore logic**

Replace `invoke()` method (lines 60-97) with:

```java
@Override
public Multi<AgentEvent> invoke(AgentSessionConfig config) {
    if (disabled) {
        return Multi.createFrom().failure(new IllegalStateException(
            "ChatModelAgentProvider is inactive — no ChatModel bean available. " +
            "Add a quarkus-langchain4j provider (e.g. quarkus-langchain4j-openai) " +
            "to the classpath."));
    }
    if (!semaphore.tryAcquire()) {
        LOG.warnf("ChatModelAgentProvider: session limit reached (%d/%d active sessions)",
            properties.maxConcurrentSessions() - semaphore.availablePermits(),
            properties.maxConcurrentSessions());
        return Multi.createFrom().failure(
            new AgentSessionLimitException(properties.maxConcurrentSessions()));
    }
    try {
        if (!config.mcpServers().isEmpty()) {
            LOG.warnf("ChatModelAgentProvider: mcpServers ignored — LangChain4j models " +
                      "do not support MCP. %d server(s) configured but unused.",
                      config.mcpServers().size());
        }

        ChatRequest request = ChatRequest.builder()
            .messages(config.systemPrompt().isEmpty()
                ? List.of(UserMessage.from(config.userPrompt()))
                : List.of(SystemMessage.from(config.systemPrompt()),
                          UserMessage.from(config.userPrompt())))
            .build();

        Multi<AgentEvent> result;
        if (streamingChatModel != null) {
            result = AgentEventBridge.stream(streamingChatModel, request);
        } else {
            result = Multi.createFrom().item(() -> {
                ChatResponse response = chatModel.chat(request);
                String text = response.aiMessage().text();
                return (AgentEvent) new AgentEvent.TextDelta(text != null ? text : "");
            });
        }

        if (config.timeout() != null) {
            result = result.ifNoItem().after(config.timeout()).failWith(
                () -> new AgentTimeoutException(config.timeout()));
        }
        return result
            .onCompletion().invoke(semaphore::release)
            .onFailure().invoke(t -> semaphore.release())
            .onCancellation().invoke(semaphore::release);
    } catch (Exception e) {
        semaphore.release();
        return Multi.createFrom().failure(e);
    }
}
```

- [ ] **Step 16: Run all tests**

Run: `mvn --batch-mode test -pl agent-langchain4j -Dtest=ChatModelAgentProviderTest`
Expected: PASS — all existing and new tests green

- [ ] **Step 17: Commit**

```
feat(platform#125): ChatModelAgentProvider invoke() concurrency limiter

Semaphore with fail-fast tryAcquire() gates invoke() calls.
Config: casehub.platform.agent.langchain4j.max-concurrent-sessions (default 10).
Three-path release ensures no permit leaks.
```

---

### Task 2: openSession() + session concurrency limiter

`openSession()` acquire, semaphore passed to `ChatModelAgentSession`, `close()` releases via `getAndSet()`.

**Files:**
- Modify: `agent-langchain4j/src/main/java/io/casehub/platform/agent/langchain4j/ChatModelAgentProvider.java`
- Modify: `agent-langchain4j/src/main/java/io/casehub/platform/agent/langchain4j/ChatModelAgentSession.java`
- Modify: `agent-langchain4j/src/test/java/io/casehub/platform/agent/langchain4j/ChatModelAgentProviderTest.java`
- Modify: `../../agent-langchain4j-core/src/test/java/io/casehub/platform/agent/langchain4j/ChatModelAgentSessionTest.java`

**Interfaces:**
- Consumes: `ChatModelAgentProvider.semaphore` field (from Task 1)
- Consumes: `AgentLangchain4jProperties.maxConcurrentSessions()` (from Task 1)

- [ ] **Step 1: Update `TestProperties` record in `ChatModelAgentSessionTest`**

Replace the `TestProperties` record (line 282-283) with:

```java
private record TestProperties(Duration closeTimeout, int sessionMemoryWindowSize, int maxConcurrentSessions)
    implements AgentLangchain4jProperties {}
```

Update all `TestProperties` construction sites in `ChatModelAgentSessionTest` from `new TestProperties(Duration.ofSeconds(30), 20)` to `new TestProperties(Duration.ofSeconds(30), 20, 10)`.

- [ ] **Step 2: Write the failing test — openSession at capacity throws AgentSessionLimitException**

Add to `ChatModelAgentProviderTest`:

```java
@Test
void openSession_atCapacity_throwsAgentSessionLimitException() {
    when(properties.maxConcurrentSessions()).thenReturn(1);
    ChatModel chatModel = mock(ChatModel.class);
    injectFields(chatModel);

    semaphoreAcquire();

    AgentSessionInit init = AgentSessionInit.of("system");

    assertThatThrownBy(() -> provider.openSession(init))
        .isInstanceOf(AgentSessionLimitException.class)
        .hasMessageContaining("1 active sessions");

    provider.semaphore.release();
}
```

- [ ] **Step 3: Write the failing test — openSession construction failure releases permit**

```java
@Test
void openSession_constructionFailure_releasesPermit() {
    when(properties.maxConcurrentSessions()).thenReturn(1);
    ChatModel chatModel = mock(ChatModel.class);
    injectFields(chatModel);

    assertThatThrownBy(() -> provider.openSession(null))
        .isInstanceOf(NullPointerException.class);

    assertThat(provider.availablePermits()).isEqualTo(1);
}
```

- [ ] **Step 4: Write the failing test — session close releases permit**

Add to `ChatModelAgentSessionTest`:

```java
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
```

- [ ] **Step 5: Write the failing test — double close does not double-release**

```java
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
```

- [ ] **Step 6: Write the failing test — query completion does NOT release permit**

```java
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
```

- [ ] **Step 7: Write the failing test — query failure does NOT release permit**

```java
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
```

- [ ] **Step 8: Write the failing test — session close releases permit after openSession**

Add to `ChatModelAgentProviderTest`:

```java
@Test
void openSession_close_releasesPermit() {
    when(properties.maxConcurrentSessions()).thenReturn(1);
    ChatModel chatModel = mock(ChatModel.class);
    injectFields(chatModel);

    AgentSession session = provider.openSession(AgentSessionInit.of("system"));
    assertThat(provider.availablePermits()).isEqualTo(0);

    session.close(Duration.ofSeconds(1));
    assertThat(provider.availablePermits()).isEqualTo(1);

    // Can open another session now
    AgentSession session2 = provider.openSession(AgentSessionInit.of("system"));
    session2.close(Duration.ofSeconds(1));
}
```

- [ ] **Step 9: Run all new tests to verify they fail**

Run: `mvn --batch-mode test -pl agent-langchain4j -Dtest=ChatModelAgentProviderTest#openSession_atCapacity_throwsAgentSessionLimitException+openSession_constructionFailure_releasesPermit+openSession_close_releasesPermit`
Expected: FAIL — no semaphore logic in `openSession()` yet

Run: `mvn --batch-mode test -pl agent-langchain4j -Dtest=ChatModelAgentSessionTest#close_releasesSemaphorePermit+close_idempotent_noDoubleRelease+query_completion_doesNotReleasePermit+query_failure_doesNotReleasePermit`
Expected: FAIL — constructor doesn't accept `Semaphore`, `close()` doesn't release

- [ ] **Step 10: Implement ChatModelAgentSession semaphore**

Add import to `ChatModelAgentSession`:

```java
import java.util.concurrent.Semaphore;
```

Add field after `state` (line 32):

```java
private final Semaphore semaphore;
```

Replace constructor (lines 34-40) with:

```java
ChatModelAgentSession(ChatModel chatModel, StreamingChatModel streamingChatModel,
                      AgentSessionInit init, AgentLangchain4jProperties properties,
                      Semaphore semaphore) {
    this.chatModel = chatModel;
    this.streamingChatModel = streamingChatModel;
    this.systemPrompt = init.systemPrompt();
    this.memory = MessageWindowChatMemory.withMaxMessages(properties.sessionMemoryWindowSize());
    this.semaphore = semaphore;
}
```

Replace `close()` method (lines 88-90) with:

```java
@Override
public void close(Duration maxWait) {
    State prev = state.getAndSet(State.CLOSED);
    if (prev != State.CLOSED) {
        semaphore.release();
    }
}
```

- [ ] **Step 11: Implement openSession() semaphore logic**

Replace `openSession()` method in `ChatModelAgentProvider` (lines 99-106) with:

```java
@Override
public AgentSession openSession(AgentSessionInit init) {
    if (disabled) {
        throw new IllegalStateException(
            "ChatModelAgentProvider is inactive — no ChatModel bean available.");
    }
    if (!semaphore.tryAcquire()) {
        LOG.warnf("ChatModelAgentProvider: session limit reached (%d/%d active sessions)",
            properties.maxConcurrentSessions() - semaphore.availablePermits(),
            properties.maxConcurrentSessions());
        throw new AgentSessionLimitException(properties.maxConcurrentSessions());
    }
    try {
        return new ChatModelAgentSession(chatModel, streamingChatModel, init, properties, semaphore);
    } catch (Exception e) {
        semaphore.release();
        throw e;
    }
}
```

- [ ] **Step 12: Update all existing ChatModelAgentSession constructor calls in tests**

In `ChatModelAgentSessionTest`, every `new ChatModelAgentSession(model, streamingModel, init, properties)` call must add a fifth parameter. For tests that don't exercise the semaphore, use `new Semaphore(Integer.MAX_VALUE)`:

Add import: `import java.util.concurrent.Semaphore;`

Replace each 4-arg construction with 5-arg. Example for `query_happyPath_emitsTextDelta`:

```java
ChatModelAgentSession session = new ChatModelAgentSession(model, null, init, properties, new Semaphore(Integer.MAX_VALUE));
```

Apply the same pattern to: `query_multiTurn_includesHistory`, `query_onClosed_throws`, `query_concurrent_throws`, `interrupt_isNoOp`, `close_setsClosedState`, `close_idempotent`, `query_streaming_emitsMultipleTextDeltas`, `query_streaming_updatesMemoryOnCompletion`, `query_streaming_doesNotUpdateMemoryOnFailure`, `query_streaming_multiTurnPreservesHistory`.

- [ ] **Step 13: Run all tests in both test classes**

Run: `mvn --batch-mode test -pl agent-langchain4j`
Expected: PASS — all existing and new tests green

- [ ] **Step 14: Run full module build**

Run: `mvn --batch-mode install -pl agent-langchain4j`
Expected: BUILD SUCCESS

- [ ] **Step 15: Commit**

```
feat(platform#125): ChatModelAgentProvider openSession() + session concurrency limiter

openSession() acquires semaphore permit, passes to ChatModelAgentSession.
Session releases on close() via getAndSet(CLOSED) — idempotent, no double-release.
Query failure/cancellation stays IDLE — session accepts subsequent queries.
```

- [ ] **Step 16: Run full project build**

Run: `mvn --batch-mode install`
Expected: BUILD SUCCESS — no cross-module breakage
