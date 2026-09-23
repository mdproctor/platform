package io.casehub.platform.expression;

import io.casehub.yaml.core.orchestration.ActionHandle;
import io.casehub.yaml.core.orchestration.ActionRegistry;

import java.util.Optional;
import java.util.Set;

public class NoOpActionRegistry implements ActionRegistry {

    @Override
    public Optional<ActionHandle> resolve(String name) {
        return Optional.empty();
    }

    @Override
    public Set<String> registeredNames() {
        return Set.of();
    }
}
