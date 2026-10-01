package io.casehub.yaml.core.error;

import java.time.Duration;

public sealed interface CoordinationError extends YamlError {

    record ChannelError(String stepName, String channelName,
                        SourceLocation location, Throwable cause) implements CoordinationError {
        @Override public ErrorCategory category() { return ErrorCategory.CHANNEL_CLOSED; }
        @Override public String summary() {
            return "Channel '" + channelName + "' closed"
                    + (stepName != null ? " while step '" + stepName + "' was waiting" : "");
        }
    }

    record DeadlineError(String stepName, String scopeName, Duration deadline,
                         SourceLocation location, Throwable cause) implements CoordinationError {
        @Override public ErrorCategory category() { return ErrorCategory.DEADLINE_EXCEEDED; }
        @Override public String summary() {
            return "Deadline exceeded in scope '" + scopeName + "' after " + deadline
                    + (stepName != null ? " (step '" + stepName + "')" : "");
        }
    }

    record CorrelationError(String stepName, Object correlationKey, Duration timeout,
                            SourceLocation location, Throwable cause) implements CoordinationError {
        @Override public ErrorCategory category() { return ErrorCategory.CORRELATION_TIMEOUT; }
        @Override public String summary() {
            return "Correlation timeout for key '" + correlationKey + "' after " + timeout
                    + (stepName != null ? " (step '" + stepName + "')" : "");
        }
    }

    record SemaphoreError(String stepName, String semaphoreName,
                          SourceLocation location, Throwable cause) implements CoordinationError {
        @Override public ErrorCategory category() { return ErrorCategory.SEMAPHORE_REENTRANCY; }
        @Override public String summary() {
            return "Reentrant acquire on semaphore '" + semaphoreName + "'"
                    + (stepName != null ? " by step '" + stepName + "'" : "");
        }
    }

    record TransitionError(String stepName, String machineName, Object fromState, Object toState,
                           SourceLocation location, Throwable cause) implements CoordinationError {
        @Override public ErrorCategory category() { return ErrorCategory.ILLEGAL_TRANSITION; }
        @Override public String summary() {
            return "Invalid transition in '" + machineName + "': " + fromState + " -> " + toState;
        }
    }
}
