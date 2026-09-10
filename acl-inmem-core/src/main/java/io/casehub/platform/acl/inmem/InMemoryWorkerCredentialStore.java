package io.casehub.platform.acl.inmem;

import io.casehub.platform.api.acl.ResourceId;
import io.casehub.platform.api.acl.WorkerCredential;
import io.casehub.platform.api.acl.WorkerCredentialStore;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

public class InMemoryWorkerCredentialStore implements WorkerCredentialStore {

    private final ConcurrentHashMap<String, WorkerCredential> store = new ConcurrentHashMap<>();

    @Override
    public void store(WorkerCredential credential) {
        evictExpired();
        store.put(credential.token(), credential);
    }

    @Override
    public Optional<WorkerCredential> lookup(String token) {
        evictExpired();
        return Optional.ofNullable(store.get(token));
    }

    @Override
    public void revoke(String token) {
        store.remove(token);
    }

    @Override
    public List<WorkerCredential> revokeByResource(ResourceId resourceId) {
        var revoked = store.values().stream()
            .filter(c -> c.resourceId().equals(resourceId)).toList();
        revoked.forEach(c -> store.remove(c.token()));
        return revoked;
    }

    @Override
    public List<WorkerCredential> revokeByActor(String actorId) {
        var revoked = store.values().stream()
            .filter(c -> c.actorId().equals(actorId)).toList();
        revoked.forEach(c -> store.remove(c.token()));
        return revoked;
    }

    @Override
    public List<WorkerCredential> findActiveByActorAndResource(String actorId, ResourceId resourceId) {
        return store.values().stream()
            .filter(c -> c.actorId().equals(actorId)
                && c.resourceId().equals(resourceId) && !c.isExpired())
            .toList();
    }

    private void evictExpired() {
        store.values().removeIf(WorkerCredential::isExpired);
    }
}
