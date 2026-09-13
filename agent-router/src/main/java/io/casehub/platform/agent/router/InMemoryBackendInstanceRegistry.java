package io.casehub.platform.agent.router;

import io.casehub.platform.agent.AgentBackend;
import io.casehub.platform.agent.BackendInstanceRegistry;
import io.casehub.platform.agent.BackendRef;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

@ApplicationScoped
public class InMemoryBackendInstanceRegistry implements BackendInstanceRegistry {

    private final ConcurrentHashMap<BackendRef, AgentBackend> instances = new ConcurrentHashMap<>();

    @Override
    public void register(AgentBackend backend) {
        instances.put(new BackendRef(backend.key(), backend.instanceId()), backend);
    }

    @Override
    public Optional<AgentBackend> resolve(String key, String instanceId) {
        return Optional.ofNullable(instances.get(new BackendRef(key, instanceId)));
    }

    @Override
    public List<AgentBackend> resolveByKey(String key) {
        return instances.entrySet().stream()
                .filter(e -> e.getKey().key().equals(key))
                .map(Map.Entry::getValue)
                .toList();
    }
}
