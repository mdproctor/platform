package io.casehub.platform.expression;

import io.casehub.yaml.core.orchestration.ActionHandle;
import io.casehub.yaml.core.orchestration.ActionRegistry;

import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class DefaultActionRegistry implements ActionRegistry {

    private final Map<String, ActionHandle> actions = new ConcurrentHashMap<>();

    public void register(String name, ActionHandle handle) {
        if (actions.putIfAbsent(name, handle) != null) {
            throw new IllegalArgumentException(
                    "Duplicate @ScenarioAction name: '" + name + "' — action names must be unique");
        }
    }

    @Override
    public Optional<ActionHandle> resolve(String name) {
        return Optional.ofNullable(actions.get(name));
    }

    @Override
    public Set<String> registeredNames() {
        return Set.copyOf(actions.keySet());
    }
}
