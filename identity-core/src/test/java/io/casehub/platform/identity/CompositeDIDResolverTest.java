package io.casehub.platform.identity;

import io.casehub.platform.api.identity.DIDDocument;
import io.casehub.platform.api.identity.DIDResolver;
import io.casehub.platform.api.identity.VerificationMethod;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class CompositeDIDResolverTest {

    private static final String ACTOR = "claude:reviewer@v1";
    private static final String DID = "did:web:example.com:agents:reviewer";

    private static DIDDocument doc(String id) {
        return new DIDDocument(id, List.of(), List.of());
    }

    @Test
    void empty_resolvers_returns_empty() {
        var composite = new CompositeDIDResolver(List.of());
        assertTrue(composite.resolve(ACTOR, DID).isEmpty());
    }

    @Test
    void delegates_to_single_resolver() {
        DIDResolver r = (actorId, did) -> Optional.of(doc(did));
        var composite = new CompositeDIDResolver(List.of(r));
        var result = composite.resolve(ACTOR, DID);
        assertTrue(result.isPresent());
        assertEquals(DID, result.get().id());
    }

    @Test
    void returns_first_non_empty_result() {
        DIDResolver empty = (actorId, did) -> Optional.empty();
        DIDResolver found = (actorId, did) -> Optional.of(doc("found"));
        DIDResolver never = (actorId, did) -> { fail("Should not be called"); return Optional.empty(); };

        var composite = new CompositeDIDResolver(List.of(empty, found, never));
        assertEquals("found", composite.resolve(ACTOR, DID).orElseThrow().id());
    }

    @Test
    void catches_exception_and_continues() {
        DIDResolver throwing = (actorId, did) -> { throw new RuntimeException("SCIM down"); };
        DIDResolver fallback = (actorId, did) -> Optional.of(doc("fallback"));

        var composite = new CompositeDIDResolver(List.of(throwing, fallback));
        assertEquals("fallback", composite.resolve(ACTOR, DID).orElseThrow().id());
    }

    @Test
    void all_throw_returns_empty() {
        DIDResolver t1 = (actorId, did) -> { throw new RuntimeException("fail 1"); };
        DIDResolver t2 = (actorId, did) -> { throw new RuntimeException("fail 2"); };

        var composite = new CompositeDIDResolver(List.of(t1, t2));
        assertTrue(composite.resolve(ACTOR, DID).isEmpty());
    }
}
