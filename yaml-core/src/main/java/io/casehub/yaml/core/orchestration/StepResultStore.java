package io.casehub.yaml.core.orchestration;

import java.util.Map;

public interface StepResultStore {
    void recordSuccess(String stepName, Map<String, Object> result);
    void recordFailure(String stepName, io.casehub.yaml.core.error.YamlError error);
    Map<String, Object> result(String stepName);
    io.casehub.yaml.core.error.YamlError error(String stepName);
    boolean hasCompleted(String stepName);
}
