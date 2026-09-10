package io.casehub.platform.agent.gate;

import io.casehub.platform.agent.AgentEvent;
import io.casehub.platform.agent.AgentSession;
import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.Uni;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class SessionRegistryTest {

    @Test
    void registerAndDeregister() {
        var registry = new SessionRegistry();
        long id = registry.register(dummySession(registry));
        assertThat(registry.snapshot()).containsKey(id);

        registry.deregister(id);
        assertThat(registry.snapshot()).doesNotContainKey(id);
    }

    @Test
    void snapshotIsIsolatedFromMutations() {
        var registry = new SessionRegistry();
        long id = registry.register(dummySession(registry));

        var snapshot = registry.snapshot();
        registry.deregister(id);

        assertThat(snapshot).containsKey(id);
        assertThat(registry.snapshot()).doesNotContainKey(id);
    }

    @Test
    void deregisterIsIdempotent() {
        var registry = new SessionRegistry();
        long id = registry.register(dummySession(registry));
        registry.deregister(id);
        registry.deregister(id);
        assertThat(registry.snapshot()).isEmpty();
    }

    @Test
    void idsAreMonotonicallyIncreasing() {
        var registry = new SessionRegistry();
        long id1 = registry.register(dummySession(registry));
        long id2 = registry.register(dummySession(registry));
        long id3 = registry.register(dummySession(registry));
        assertThat(id2).isGreaterThan(id1);
        assertThat(id3).isGreaterThan(id2);
    }

    @Test
    void trackedSessionContainsCreationTimestamp() {
        var registry = new SessionRegistry();
        long id = registry.register(dummySession(registry));

        var tracked = registry.snapshot().get(id);
        assertThat(tracked).isNotNull();
        assertThat(tracked.createdAt()).isNotNull();
        assertThat(tracked.id()).isEqualTo(id);
    }

    @Test
    void concurrentRegisterDeregister() throws Exception {
        var registry = new SessionRegistry();
        int threads = 20;
        var latch = new CountDownLatch(threads);
        var ids = new long[threads];

        for (int i = 0; i < threads; i++) {
            final int idx = i;
            Thread.ofVirtual().start(() -> {
                ids[idx] = registry.register(dummySession(registry));
                latch.countDown();
            });
        }
        assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(registry.snapshot()).hasSize(threads);

        var deregLatch = new CountDownLatch(threads);
        for (int i = 0; i < threads; i++) {
            final long id = ids[i];
            Thread.ofVirtual().start(() -> {
                registry.deregister(id);
                deregLatch.countDown();
            });
        }
        assertThat(deregLatch.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(registry.snapshot()).isEmpty();
    }

    @Test
    void nextIdAndRegisterWithExplicitId() {
        var registry = new SessionRegistry();
        long id = registry.nextId();
        var session = dummySession(registry);
        registry.register(id, session);

        var tracked = registry.snapshot().get(id);
        assertThat(tracked).isNotNull();
        assertThat(tracked.session()).isSameAs(session);
    }

    private static GatedAgentSession dummySession(SessionRegistry registry) {
        return new GatedAgentSession(
                new NoOpSession(),
                List.of(), List.of(),
                Duration.ofSeconds(5),
                registry, 0);
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
