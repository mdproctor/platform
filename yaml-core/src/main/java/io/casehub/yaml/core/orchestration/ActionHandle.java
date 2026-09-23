package io.casehub.yaml.core.orchestration;

import java.util.Map;

@FunctionalInterface
public interface ActionHandle {
    Object invoke(ScenarioScope scope, Map<String, Object> args);
}
