package io.casehub.yaml.core.orchestration;

import java.util.Optional;
import java.util.Set;

public interface ActionRegistry {
    Optional<ActionHandle> resolve(String name);
    Set<String> registeredNames();
}
