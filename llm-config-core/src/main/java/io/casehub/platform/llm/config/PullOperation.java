package io.casehub.platform.llm.config;

public record PullOperation(String operationId, String modelRef, PullStatus status) {

    public enum PullStatus { PULLING, COMPLETED, FAILED, CANCELLED }
}
