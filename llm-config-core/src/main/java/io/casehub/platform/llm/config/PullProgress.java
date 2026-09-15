package io.casehub.platform.llm.config;

public record PullProgress(
    String operationId,
    String modelRef,
    PullOperation.PullStatus status,
    long totalBytes,
    long completedBytes,
    String digest,
    String errorMessage
) {}
