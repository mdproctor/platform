package io.casehub.platform.acl.inmem;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.casehub.platform.api.acl.AclAction;
import io.casehub.platform.api.acl.ResourceId;
import io.casehub.platform.api.acl.WorkerAction;
import io.casehub.platform.api.acl.WorkerCredential;
import java.time.Instant;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class InMemoryWorkerCredentialStoreTest {

    private static final WorkerAction READ_CONTEXT =
        new WorkerAction("READ_CONTEXT", AclAction.READ);

    private InMemoryWorkerCredentialStore store;

    @BeforeEach
    void setUp() {
        store = new InMemoryWorkerCredentialStore();
    }

    @Test
    void storeAndLookup() {
        var cred = credential("t1", "actor-1", new ResourceId("case", "c1"), "tenant-1");
        store.store(cred);
        var result = store.lookup("t1");
        assertTrue(result.isPresent());
        assertEquals("actor-1", result.get().actorId());
    }

    @Test
    void lookup_unknownToken_returnsEmpty() {
        assertTrue(store.lookup("nonexistent").isEmpty());
    }

    @Test
    void revoke_removesCredential() {
        var cred = credential("t1", "actor-1", new ResourceId("case", "c1"), "tenant-1");
        store.store(cred);
        store.revoke("t1");
        assertTrue(store.lookup("t1").isEmpty());
    }

    @Test
    void revokeByResource_sweepsAllForResource() {
        var rid = new ResourceId("case", "c1");
        store.store(credential("t1", "actor-1", rid, "tenant-1"));
        store.store(credential("t2", "actor-2", rid, "tenant-1"));
        store.store(credential("t3", "actor-3", new ResourceId("case", "c2"), "tenant-1"));

        var revoked = store.revokeByResource(rid);

        assertEquals(2, revoked.size());
        assertTrue(store.lookup("t1").isEmpty());
        assertTrue(store.lookup("t2").isEmpty());
        assertTrue(store.lookup("t3").isPresent());
    }

    @Test
    void revokeByActor_sweepsAllForActor() {
        store.store(credential("t1", "agent:pool", new ResourceId("case", "c1"), "tenant-1"));
        store.store(credential("t2", "agent:pool", new ResourceId("case", "c2"), "tenant-1"));
        store.store(credential("t3", "agent:other", new ResourceId("case", "c3"), "tenant-1"));

        var revoked = store.revokeByActor("agent:pool");

        assertEquals(2, revoked.size());
        assertTrue(store.lookup("t1").isEmpty());
        assertTrue(store.lookup("t2").isEmpty());
        assertTrue(store.lookup("t3").isPresent());
    }

    @Test
    void findActiveByActorAndResource_excludesExpired() {
        var rid = new ResourceId("case", "c1");
        store.store(credential("t1", "agent:pool", rid, "tenant-1"));
        store.store(new WorkerCredential("t2", "agent:pool", rid, "tenant-1",
            Set.of(READ_CONTEXT), Instant.now().minusSeconds(60), Instant.now().minusSeconds(120)));

        var active = store.findActiveByActorAndResource("agent:pool", rid);

        assertEquals(1, active.size());
        assertEquals("t1", active.get(0).token());
    }

    @Test
    void lazyEviction_expiredRemovedOnStore() {
        store.store(new WorkerCredential("old", "actor-1", new ResourceId("case", "c1"), "tenant-1",
            Set.of(READ_CONTEXT), Instant.now().minusSeconds(60), Instant.now().minusSeconds(120)));
        store.store(credential("new", "actor-2", new ResourceId("case", "c2"), "tenant-1"));

        assertTrue(store.lookup("old").isEmpty());
        assertTrue(store.lookup("new").isPresent());
    }

    @Test
    void lazyEviction_expiredRemovedOnLookup() {
        store.store(new WorkerCredential("old", "actor-1", new ResourceId("case", "c1"), "tenant-1",
            Set.of(READ_CONTEXT), Instant.now().minusSeconds(60), Instant.now().minusSeconds(120)));

        store.lookup("anything");

        assertTrue(store.lookup("old").isEmpty());
    }

    private WorkerCredential credential(String token, String actorId, ResourceId resourceId, String tenancyId) {
        return new WorkerCredential(token, actorId, resourceId, tenancyId,
            Set.of(READ_CONTEXT), Instant.now().plusSeconds(3600), Instant.now());
    }
}
