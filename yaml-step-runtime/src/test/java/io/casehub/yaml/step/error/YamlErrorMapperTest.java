package io.casehub.yaml.step.error;

import io.casehub.yaml.core.condition.ConditionEvaluationException;
import io.casehub.yaml.core.error.*;
import io.casehub.yaml.core.module.ParameterValidationException;
import io.casehub.yaml.core.module.ParameterViolation;
import io.casehub.yaml.core.orchestration.*;
import io.casehub.yaml.core.resolver.UnresolvedVariableException;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class YamlErrorMapperTest {

    private static final SourceLocation LOC = new SourceLocation("test.yaml", 10, -1);

    @Test
    void deadlineExceeded_mapsToDeadlineError() {
        var ex = new DeadlineExceededException("my-scope", Duration.ofSeconds(5));
        YamlError err = YamlErrorMapper.from(ex, "step-a", LOC);
        assertThat(err).isInstanceOf(CoordinationError.DeadlineError.class);
        assertThat(err.category()).isEqualTo(ErrorCategory.DEADLINE_EXCEEDED);
        var de = (CoordinationError.DeadlineError) err;
        assertThat(de.scopeName()).isEqualTo("my-scope");
        assertThat(de.deadline()).isEqualTo(Duration.ofSeconds(5));
    }

    @Test
    void channelClosed_mapsToChannelError() {
        var ex = new ChannelClosedException("order-ch");
        YamlError err = YamlErrorMapper.from(ex, "step-b", LOC);
        assertThat(err).isInstanceOf(CoordinationError.ChannelError.class);
        assertThat(err.category()).isEqualTo(ErrorCategory.CHANNEL_CLOSED);
    }

    @Test
    void semaphoreReentrancy_mapsToSemaphoreError() {
        var ex = new SemaphoreReentrancyException("gate", "step-c");
        YamlError err = YamlErrorMapper.from(ex, "step-c", LOC);
        assertThat(err).isInstanceOf(CoordinationError.SemaphoreError.class);
        assertThat(err.category()).isEqualTo(ErrorCategory.SEMAPHORE_REENTRANCY);
    }

    @Test
    void illegalTransition_mapsToTransitionError() {
        var ex = new IllegalTransitionException("fsm", "IDLE", "COMPLETED");
        YamlError err = YamlErrorMapper.from(ex, "step-d", LOC);
        assertThat(err).isInstanceOf(CoordinationError.TransitionError.class);
        assertThat(err.category()).isEqualTo(ErrorCategory.ILLEGAL_TRANSITION);
    }

    @Test
    void unresolvedVariable_mapsToExpressionEvalError() {
        var ex = new UnresolvedVariableException("orderId", "step-e", "No source found");
        YamlError err = YamlErrorMapper.from(ex, "step-e", LOC);
        assertThat(err).isInstanceOf(RuntimeStepError.ExpressionEvalError.class);
        assertThat(err.category()).isEqualTo(ErrorCategory.EXPRESSION_EVAL);
    }

    @Test
    void conditionEvaluation_mapsToExpressionEvalError() {
        var ex = new ConditionEvaluationException("Null pointer in expression");
        YamlError err = YamlErrorMapper.from(ex, "step-f", LOC);
        assertThat(err).isInstanceOf(RuntimeStepError.ExpressionEvalError.class);
    }

    @Test
    void parameterValidation_mapsToParameterError() {
        var ex = new ParameterValidationException(
                List.of(new ParameterViolation("retries", "min", "must be >= 1", 0, null)));
        YamlError err = YamlErrorMapper.from(ex, "step-g", LOC);
        assertThat(err).isInstanceOf(ParseError.ParameterError.class);
        assertThat(err.category()).isEqualTo(ErrorCategory.PARAMETER_VIOLATION);
    }

    @Test
    void unknownException_mapsToStepActionError() {
        var ex = new RuntimeException("Something unexpected");
        YamlError err = YamlErrorMapper.from(ex, "step-h", LOC);
        assertThat(err).isInstanceOf(RuntimeStepError.StepActionError.class);
        assertThat(err.category()).isEqualTo(ErrorCategory.STEP_ACTION_FAILED);
        assertThat(err.summary()).contains("Something unexpected");
    }

    @Test
    void nullStepName_acceptedGracefully() {
        var ex = new ChannelClosedException("ch");
        YamlError err = YamlErrorMapper.from(ex, null, LOC);
        assertThat(err.stepName()).isNull();
    }
}
