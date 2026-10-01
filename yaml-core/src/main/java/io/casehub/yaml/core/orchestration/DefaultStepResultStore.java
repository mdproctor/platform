package io.casehub.yaml.core.orchestration;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class DefaultStepResultStore implements StepResultStore {

    private final ConcurrentHashMap<String, Map<String, Object>> results = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, io.casehub.yaml.core.error.YamlError> errors = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Boolean> completed = new ConcurrentHashMap<>();

    @Override
    public void recordSuccess(String stepName, Map<String, Object> result) {
        results.put(stepName, result);
        completed.put(stepName, true);
    }

    @Override
    public void recordFailure(String stepName, io.casehub.yaml.core.error.YamlError error) {
        errors.put(stepName, error);
        completed.put(stepName, true);
    }

    @Override
    public Map<String, Object> result(String stepName) {
        return results.get(stepName);
    }

    @Override
    public io.casehub.yaml.core.error.YamlError error(String stepName) {
        return errors.get(stepName);
    }

    @Override
    public boolean hasCompleted(String stepName) {
        return completed.containsKey(stepName);
    }
}
