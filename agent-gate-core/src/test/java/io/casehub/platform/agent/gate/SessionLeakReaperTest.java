package io.casehub.platform.agent.gate;

import io.casehub.platform.agent.AgentEvent;
import io.casehub.platform.agent.AgentSession;
import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.Uni;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SessionLeakReaperTest {

    private SessionRegistry registry;
    private List<String> logMessages;
    private Handler logHandler;

    @BeforeEach
    void setUp() {
        registry = new SessionRegistry();
        logMessages = new ArrayList<>();
        logHandler = new Handler() {
            @Override
            public void publish(LogRecord record) {
                if (record.getLevel().intValue() >= Level.WARNING.intValue()) {
                    logMessages.add(record.getMessage());
                }
            }
            @Override public void flush() {}
            @Override public void close() {}
        };
        var julLogger = Logger.getLogger(SessionLeakReaper.class.getName());
        julLogger.addHandler(logHandler);
        julLogger.setLevel(Level.WARNING);
    }

    @Test
    void scanDoesNothingWhenNoSessions() {
        var reaper = createReaper(Duration.ofMinutes(5), false, Duration.ofMinutes(30), Duration.ofHours(24));
        reaper.scan();
        assertThat(logMessages).isEmpty();
    }

    @Test
    void scanWarnsForSessionExceedingThreshold() {
        registerOldSession(Duration.ofMinutes(10));
        var reaper = createReaper(Duration.ofMinutes(5), false, Duration.ofMinutes(30), Duration.ofHours(24));

        reaper.scan();

        assertThat(logMessages).anyMatch(msg -> msg.contains("Leaked GatedAgentSession detected"));
    }

    @Test
    void scanDoesNotWarnForSessionWithinThreshold() {
        registerFreshSession();
        var reaper = createReaper(Duration.ofMinutes(5), false, Duration.ofMinutes(30), Duration.ofHours(24));

        reaper.scan();

        assertThat(logMessages).noneMatch(msg -> msg.contains("Leaked"));
    }

    @Test
    void scanForceClosesWhenEnabledAndThresholdExceeded() {
        var closedFlag = new AtomicBoolean(false);
        registerOldSession(Duration.ofMinutes(45), closedFlag);
        var reaper = createReaper(Duration.ofMinutes(5), true, Duration.ofMinutes(30), Duration.ofHours(24));

        reaper.scan();

        assertThat(closedFlag.get()).isTrue();
        assertThat(logMessages).anyMatch(msg -> msg.contains("Force-closed"));
    }

    @Test
    void scanDoesNotForceCloseWhenDisabled() {
        var closedFlag = new AtomicBoolean(false);
        registerOldSession(Duration.ofMinutes(45), closedFlag);
        var reaper = createReaper(Duration.ofMinutes(5), false, Duration.ofMinutes(30), Duration.ofHours(24));

        reaper.scan();

        assertThat(closedFlag.get()).isFalse();
        assertThat(logMessages).anyMatch(msg -> msg.contains("Leaked GatedAgentSession detected"));
    }

    @Test
    void scanEvictsAndClosesSessionExceedingMaxRegistryAge() {
        var closedFlag = new AtomicBoolean(false);
        registerOldSession(Duration.ofHours(25), closedFlag);
        var reaper = createReaper(Duration.ofMinutes(5), false, Duration.ofMinutes(30), Duration.ofHours(24));

        assertThat(registry.snapshot()).hasSize(1);
        reaper.scan();
        assertThat(registry.snapshot()).isEmpty();
        assertThat(closedFlag.get()).isTrue();
        assertThat(logMessages).anyMatch(msg -> msg.contains("Evicted stale"));
    }

    @Test
    void scanContinuesAfterForceCloseException() {
        registerOldSessionThrowing(Duration.ofMinutes(45));
        var normalClosed = new AtomicBoolean(false);
        registerOldSession(Duration.ofMinutes(45), normalClosed);

        var reaper = createReaper(Duration.ofMinutes(5), true, Duration.ofMinutes(30), Duration.ofHours(24));
        reaper.scan();

        assertThat(normalClosed.get()).isTrue();
        assertThat(logMessages).anyMatch(msg -> msg.contains("Failed to force-close"));
    }

    @Test
    void configValidationRejectsWarnAboveForceClose() {
        assertThatThrownBy(() ->
            createReaper(Duration.ofHours(1), true, Duration.ofMinutes(30), Duration.ofHours(24)))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("warn-threshold");
    }

    @Test
    void configValidationRejectsForceCloseAboveMaxAge() {
        assertThatThrownBy(() ->
            createReaper(Duration.ofMinutes(5), true, Duration.ofHours(48), Duration.ofHours(24)))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("force-close-threshold");
    }

    // --- helpers ---

    private SessionLeakReaper createReaper(Duration warnThreshold,
                                           boolean forceCloseEnabled,
                                           Duration forceCloseThreshold,
                                           Duration maxRegistryAge) {
        return new SessionLeakReaper(registry,
                warnThreshold, forceCloseEnabled, forceCloseThreshold, maxRegistryAge);
    }

    private void registerFreshSession() {
        var id = registry.nextId();
        var session = new GatedAgentSession(
                new NoOpSession(), List.of(), List.of(),
                Duration.ofSeconds(5), registry, id);
        registry.register(id, session);
    }

    private void registerOldSession(Duration age) {
        registerOldSession(age, new AtomicBoolean(false));
    }

    private void registerOldSession(Duration age, AtomicBoolean closedFlag) {
        var delegate = new NoOpSession() {
            @Override
            public void close(Duration maxWait) {
                closedFlag.set(true);
            }
        };
        var id = registry.nextId();
        var session = new GatedAgentSession(
                delegate, List.of(), List.of(),
                Duration.ofSeconds(5), registry, id);
        registry.registerWithTimestamp(id, session, Instant.now().minus(age));
    }

    private void registerOldSessionThrowing(Duration age) {
        var delegate = new NoOpSession() {
            @Override
            public void close(Duration maxWait) {
                throw new RuntimeException("delegate close failed");
            }
        };
        var id = registry.nextId();
        var session = new GatedAgentSession(
                delegate, List.of(), List.of(),
                Duration.ofSeconds(5), registry, id);
        registry.registerWithTimestamp(id, session, Instant.now().minus(age));
    }

    private static class NoOpSession implements AgentSession {
        @Override
        public Multi<AgentEvent> query(String prompt) {
            return Multi.createFrom().empty();
        }

        @Override
        public Uni<Void> interrupt() {
            return Uni.createFrom().voidItem();
        }

        @Override
        public void close(Duration maxWait) {}

        @Override
        public void close() { close(Duration.ofSeconds(30)); }
    }
}
