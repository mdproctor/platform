package io.casehub.yaml.core.error;

public sealed interface ParseError extends YamlError {

    record UnknownStepError(String stepName, String referencedAction,
                            SourceLocation location, Throwable cause) implements ParseError {
        @Override public ErrorCategory category() { return ErrorCategory.UNKNOWN_STEP; }
        @Override public String summary() {
            return "Step '" + stepName + "' references unknown action '" + referencedAction + "'";
        }
    }

    record ParameterError(String stepName, String parameterName, String constraint, String value,
                          SourceLocation location, Throwable cause) implements ParseError {
        @Override public ErrorCategory category() { return ErrorCategory.PARAMETER_VIOLATION; }
        @Override public String summary() {
            return "Parameter '" + parameterName + "' on step '" + stepName
                    + "' violates constraint: " + constraint;
        }
    }

    record ExpressionParseError(String stepName, String expressionText, String engineType,
                                String engineMessage,
                                SourceLocation location, Throwable cause) implements ParseError {
        @Override public ErrorCategory category() { return ErrorCategory.EXPRESSION_PARSE; }
        @Override public String summary() {
            return engineType + " expression failed to parse on step '" + stepName
                    + "': " + engineMessage;
        }
    }

    record StructureError(String stepName, String detail,
                          SourceLocation location, Throwable cause) implements ParseError {
        @Override public ErrorCategory category() { return ErrorCategory.INVALID_STRUCTURE; }
        @Override public String summary() {
            return "Invalid YAML structure"
                    + (stepName != null ? " at step '" + stepName + "'" : "")
                    + ": " + detail;
        }
    }

    record DuplicateStepError(String stepName,
                              SourceLocation location, Throwable cause) implements ParseError {
        @Override public ErrorCategory category() { return ErrorCategory.DUPLICATE_STEP; }
        @Override public String summary() {
            return "Duplicate step name '" + stepName + "'";
        }
    }
}
