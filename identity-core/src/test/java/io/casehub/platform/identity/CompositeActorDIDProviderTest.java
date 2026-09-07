package io.casehub.platform.identity;

import io.casehub.platform.api.identity.ActorDIDProvider;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

class CompositeActorDIDProviderTest {

    private static final String ACTOR = "claude:reviewer@v1";

    @Test
    void empty_providers_returns_empty() {
        var composite = new CompositeActorDIDProvider(List.of());
        assertTrue(composite.didFor(ACTOR).isEmpty());
    }

    @Test
    void delegates_to_single_provider() {
        ActorDIDProvider p = actorId -> Optional.of("did:web:example.com");
        var composite = new CompositeActorDIDProvider(List.of(p));
        assertEquals("did:web:example.com", composite.didFor(ACTOR).orElseThrow());
    }

    @Test
    void returns_first_non_empty_result() {
        ActorDIDProvider empty = actorId -> Optional.empty();
        ActorDIDProvider found = actorId -> Optional.of("did:web:found");
        ActorDIDProvider never = actorId -> { fail("Should not be called"); return Optional.empty(); };

        var composite = new CompositeActorDIDProvider(List.of(empty, found, never));
        assertEquals("did:web:found", composite.didFor(ACTOR).orElseThrow());
    }

    @Test
    void catches_exception_and_continues() {
        ActorDIDProvider throwing = actorId -> { throw new RuntimeException("SCIM down"); };
        ActorDIDProvider fallback = actorId -> Optional.of("did:web:fallback");

        var composite = new CompositeActorDIDProvider(List.of(throwing, fallback));
        assertEquals("did:web:fallback", composite.didFor(ACTOR).orElseThrow());
    }

    @Test
    void all_throw_returns_empty() {
        ActorDIDProvider t1 = actorId -> { throw new RuntimeException("fail 1"); };
        ActorDIDProvider t2 = actorId -> { throw new RuntimeException("fail 2"); };

        var composite = new CompositeActorDIDProvider(List.of(t1, t2));
        assertTrue(composite.didFor(ACTOR).isEmpty());
    }

    @Test
    void invalidate_propagates_to_all_children() {
        AtomicBoolean p1Called = new AtomicBoolean(false);
        AtomicBoolean p2Called = new AtomicBoolean(false);

        ActorDIDProvider p1 = new ActorDIDProvider() {
            @Override public Optional<String> didFor(String actorId) { return Optional.empty(); }
            @Override public void invalidate(String actorId) { p1Called.set(true); }
        };
        ActorDIDProvider p2 = new ActorDIDProvider() {
            @Override public Optional<String> didFor(String actorId) { return Optional.empty(); }
            @Override public void invalidate(String actorId) { p2Called.set(true); }
        };

        var composite = new CompositeActorDIDProvider(List.of(p1, p2));
        composite.invalidate(ACTOR);

        assertTrue(p1Called.get());
        assertTrue(p2Called.get());
    }

    @Test
    void invalidate_catches_exception_and_continues() {
        AtomicBoolean p2Called = new AtomicBoolean(false);

        ActorDIDProvider throwing = new ActorDIDProvider() {
            @Override public Optional<String> didFor(String actorId) { return Optional.empty(); }
            @Override public void invalidate(String actorId) { throw new RuntimeException("boom"); }
        };
        ActorDIDProvider p2 = new ActorDIDProvider() {
            @Override public Optional<String> didFor(String actorId) { return Optional.empty(); }
            @Override public void invalidate(String actorId) { p2Called.set(true); }
        };

        var composite = new CompositeActorDIDProvider(List.of(throwing, p2));
        composite.invalidate(ACTOR);

        assertTrue(p2Called.get(), "Second provider's invalidate must be called despite first throwing");
    }
}
