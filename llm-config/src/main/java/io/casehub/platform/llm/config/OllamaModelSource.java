package io.casehub.platform.llm.config;

import io.casehub.platform.api.model.ModelDescriptor;
import io.casehub.platform.api.model.ModelSource;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.List;
import java.util.Map;

@ApplicationScoped
public class OllamaModelSource implements ModelSource {

    private final OllamaClient client;
    private volatile OllamaSourceStatus lastStatus;

    @Inject
    OllamaModelSource(OllamaClient client) {
        this.client = client;
        this.lastStatus = OllamaSourceStatus.offline("Not yet refreshed");
    }

    OllamaModelSource(OllamaClient client, boolean ignored) {
        this.client = client;
        this.lastStatus = OllamaSourceStatus.offline("Not yet refreshed");
    }

    @Override
    public String sourceId() { return "local:ollama"; }

    @Override
    public int priority() { return 3; }

    @Override
    public List<ModelDescriptor> refresh() {
        var result = client.listModels(Map.of());
        if (result.valid()) {
            lastStatus = OllamaSourceStatus.online(null, List.of());
            return result.models();
        }
        lastStatus = OllamaSourceStatus.offline(result.errorMessage());
        return List.of();
    }

    OllamaSourceStatus status() {
        return lastStatus;
    }
}
