package io.casehub.platform.api.model;

public record RefreshResult(
    int sourcesRefreshed,
    int totalModels,
    int added,
    int removed,
    int updated
) {}
