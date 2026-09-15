package io.casehub.platform.llm.config;

import java.util.List;

public record ConfigureResult(String providerId, int modelsRegistered, List<String> modelIds) {}
