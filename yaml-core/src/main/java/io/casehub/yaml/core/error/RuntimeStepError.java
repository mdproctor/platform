package io.casehub.yaml.core.error;

import java.time.Duration;
import java.util.Map;

public sealed interface RuntimeStepError extends YamlError {

    record StepActionError(String stepName, String actionName, String rootCause,
                           SourceLocation location, Throwable cause) implements RuntimeStepError {
        @Override public ErrorCategory category() { return ErrorCategory.STEP_ACTION_FAILED; }
        @Override public String summary() {
            return "Step '" + stepName + "' action '" + actionName + "' failed: " + rootCause;
        }
    }

    record TimeoutError(String stepName, Duration timeout,
                        SourceLocation location, Throwable cause) implements RuntimeStepError {
        @Override public ErrorCategory category() { return ErrorCategory.TIMEOUT; }
        @Override public String summary() {
            return "Step '" + stepName + "' timed out after " + timeout;
        }
    }

    record RetryExhaustedError(String stepName, int maxAttempts, String lastError,
                               String backoffStrategy, Duration delay,
                               SourceLocation location, Throwable cause) implements RuntimeStepError {
        @Override public ErrorCategory category() { return ErrorCategory.RETRY_EXHAUSTED; }
        @Override public String summary() {
            return "Step '" + stepName + "' failed after " + maxAttempts
                    + " retry attempts. Last error: " + lastError;
        }
    }

    record GuardRejectedError(String stepName, String guardExpression, String reason,
                              SourceLocation location, Throwable cause) implements RuntimeStepError {
        @Override public ErrorCategory category() { return ErrorCategory.GUARD_REJECTED; }
        @Override public String summary() {
            return "Step '" + stepName + "' guard rejected: " + reason;
        }
    }

    record ExpressionEvalError(String stepName, String expressionText, String engineType,
                               String engineMessage, Map<String, Object> resolvedVariables,
                               SourceLocation location, Throwable cause) implements RuntimeStepError {
        public ExpressionEvalError {
            resolvedVariables = resolvedVariables != null ? Map.copyOf(resolvedVariables) : Map.of();
        }
        @Override public ErrorCategory category() { return ErrorCategory.EXPRESSION_EVAL; }
        @Override public String summary() {
            return engineType + " expression failed on step '" + stepName
                    + "': " + engineMessage;
        }
    }
}
