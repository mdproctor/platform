package io.casehub.yaml.step.error;

import io.casehub.yaml.core.condition.ConditionEvaluationException;
import io.casehub.yaml.core.error.*;
import io.casehub.yaml.core.module.ParameterValidationException;
import io.casehub.yaml.core.orchestration.*;
import io.casehub.yaml.core.resolver.UnresolvedVariableException;

import java.util.Map;

public final class YamlErrorMapper {

    private YamlErrorMapper() {}

    public static YamlError from(Throwable t, String stepName, SourceLocation location) {
        return switch (t) {
            case DeadlineExceededException e ->
                    new CoordinationError.DeadlineError(stepName, e.scopeName(), e.deadline(), location, e);
            case ChannelClosedException e ->
                    new CoordinationError.ChannelError(stepName, e.getMessage(), location, e);
            case CorrelationTimeoutException e ->
                    new CoordinationError.CorrelationError(stepName, null, null, location, e);
            case SemaphoreReentrancyException e ->
                    new CoordinationError.SemaphoreError(stepName, e.getMessage(), location, e);
            case IllegalTransitionException e ->
                    new CoordinationError.TransitionError(stepName, null, null, null, location, e);
            case UnresolvedVariableException e ->
                    new RuntimeStepError.ExpressionEvalError(
                            stepName, e.variableName(), "variable",
                            e.getMessage(), Map.of(), location, e);
            case ConditionEvaluationException e ->
                    new RuntimeStepError.ExpressionEvalError(
                            stepName, null, "condition",
                            e.getMessage(), Map.of(), location, e);
            case ParameterValidationException e ->
                    new ParseError.ParameterError(
                            stepName,
                            e.violations().isEmpty() ? "" : e.violations().get(0).parameterName(),
                            e.getMessage(), null, location, e);
            default ->
                    new RuntimeStepError.StepActionError(
                            stepName, null, t.getMessage(), location, t);
        };
    }
}
