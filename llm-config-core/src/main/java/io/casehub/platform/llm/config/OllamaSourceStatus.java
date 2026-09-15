package io.casehub.platform.llm.config;

import java.time.Instant;
import java.util.List;

public record OllamaSourceStatus(
    State state,
    String version,
    String message,
    List<LoadedModel> loadedModels
) {

    public enum State { ONLINE, OFFLINE }

    public record LoadedModel(
        String name,
        long sizeBytes,
        long sizeVramBytes,
        String quantization,
        Instant expiresAt
    ) {}

    public static OllamaSourceStatus online(String version, List<LoadedModel> loadedModels) {
        return new OllamaSourceStatus(State.ONLINE, version,
            loadedModels.size() + " models loaded", loadedModels);
    }

    public static OllamaSourceStatus offline(String errorMessage) {
        return new OllamaSourceStatus(State.OFFLINE, null, errorMessage, List.of());
    }
}
