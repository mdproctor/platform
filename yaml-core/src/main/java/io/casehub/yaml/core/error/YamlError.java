package io.casehub.yaml.core.error;

public sealed interface YamlError permits ParseError, RuntimeStepError, CoordinationError {
    String stepName();
    ErrorCategory category();
    String summary();
    SourceLocation location();
    Throwable cause();
}
