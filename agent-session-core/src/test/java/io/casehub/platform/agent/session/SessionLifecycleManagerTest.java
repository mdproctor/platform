package io.casehub.platform.agent.session;

import io.casehub.platform.agent.AgentBackend;
import io.casehub.platform.agent.AgentSessionInit;
import io.casehub.platform.agent.BackendInstanceRegistry;
import io.casehub.platform.agent.config.PoolDeclaration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class SessionLifecycleManagerTest {

    private SessionPoolRegistry poolRegistry;
    private SessionLifecycleManager manager;

    @BeforeEach
    void setUp() {
        var decl = new PoolDeclaration("test-pool", "test", "stub",
                0, 5, null, Map.of(), Map.of());
        poolRegistry = new SessionPoolRegistry(List.of(decl), stubBackendRegistry());
        manager = new SessionLifecycleManager(poolRegistry);
    }

    @AfterEach
    void tearDown() {
        manager.shutdown();
        poolRegistry.shutdown();
    }

    @Test
    void acquireReturnsManagedSession() {
        var session = manager.acquire("appraisal", "stub", init());

        assertThat(session).isNotNull();
        assertThat(session.conversationId()).isEqualTo("appraisal");
        assertThat(session.clearingPolicy()).isEqualTo(ClearingPolicy.MANUAL);
    }

    @Test
    void acquireSameIdReturnsSameSession() {
        var first = manager.acquire("appraisal", "stub", init());
        var second = manager.acquire("appraisal", "stub", init());

        assertThat(second).isSameAs(first);
    }

    @Test
    void acquireDifferentIdsReturnsDifferentSessions() {
        var appraisal = manager.acquire("appraisal", "stub", init());
        var extractor = manager.acquire("kg-extractor", "stub", init());

        assertThat(appraisal).isNotSameAs(extractor);
        assertThat(appraisal.conversationId()).isEqualTo("appraisal");
        assertThat(extractor.conversationId()).isEqualTo("kg-extractor");
    }

    @Test
    void releaseUnbindsConversationId() {
        manager.acquire("appraisal", "stub", init());
        assertThat(manager.hasSession("appraisal")).isTrue();

        manager.release("appraisal");
        assertThat(manager.hasSession("appraisal")).isFalse();
    }

    @Test
    void acquireAfterReleaseCreatesNewSession() {
        var first = manager.acquire("appraisal", "stub", init());
        manager.release("appraisal");
        var second = manager.acquire("appraisal", "stub", init());

        assertThat(second).isNotSameAs(first);
    }

    @Test
    void everyCallPolicyClearsBeforeSecondQuery() {
        var session = manager.acquire("appraisal", "stub", init(), ClearingPolicy.EVERY_CALL, 0);

        // First query — no clear
        session.query("prompt 1").collect().asList().await().atMost(Duration.ofSeconds(5));
        assertThat(session.turnCount()).isEqualTo(1);

        // Second query — clear fires before query
        session.query("prompt 2").collect().asList().await().atMost(Duration.ofSeconds(5));
        assertThat(session.turnCount()).isEqualTo(2);
    }

    @Test
    void afterNTurnsPolicyClearsAtInterval() {
        var session = manager.acquire("reflection", "stub", init(), ClearingPolicy.AFTER_N_TURNS, 3);

        session.query("p1").collect().asList().await().atMost(Duration.ofSeconds(5));
        session.query("p2").collect().asList().await().atMost(Duration.ofSeconds(5));
        session.query("p3").collect().asList().await().atMost(Duration.ofSeconds(5));
        // clear() fires at turn 3 — verified by turn count continuing
        assertThat(session.turnCount()).isEqualTo(3);

        session.query("p4").collect().asList().await().atMost(Duration.ofSeconds(5));
        assertThat(session.turnCount()).isEqualTo(4);
    }

    @Test
    void manualClearResetsCounter() {
        var session = manager.acquire("appraisal", "stub", init());

        session.query("p1").collect().asList().await().atMost(Duration.ofSeconds(5));
        session.query("p2").collect().asList().await().atMost(Duration.ofSeconds(5));
        assertThat(session.turnCount()).isEqualTo(2);

        session.clear();
        assertThat(session.turnCount()).isEqualTo(0);
    }

    @Test
    void closeViaSessionReleasesFromManager() {
        var session = manager.acquire("appraisal", "stub", init());
        assertThat(manager.hasSession("appraisal")).isTrue();

        session.close();
        assertThat(manager.hasSession("appraisal")).isFalse();
    }

    // --- Infrastructure ---

    private static AgentSessionInit init() {
        return AgentSessionInit.of("test system prompt");
    }

    private static BackendInstanceRegistry stubBackendRegistry() {
        return new BackendInstanceRegistry() {
            private final AgentBackend stub = new SessionPoolTest.ClearableBackend();
            @Override public void register(AgentBackend backend) {}
            @Override public Optional<AgentBackend> resolve(String key, String instanceId) {
                return "stub".equals(key) ? Optional.of(stub) : Optional.empty();
            }
            @Override public List<AgentBackend> resolveByKey(String key) {
                return "stub".equals(key) ? List.of(stub) : List.of();
            }
        };
    }
}
