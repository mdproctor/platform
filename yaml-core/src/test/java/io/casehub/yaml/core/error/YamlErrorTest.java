package io.casehub.yaml.core.error;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class YamlErrorTest {

    @Test
    void sourceLocation_unknown_isNotKnown() {
        assertThat(SourceLocation.UNKNOWN.isKnown()).isFalse();
        assertThat(SourceLocation.UNKNOWN.toString()).isEqualTo("<unknown>");
    }

    @Test
    void sourceLocation_withFile_isKnown() {
        var loc = new SourceLocation("workflow.yaml", 42, 5);
        assertThat(loc.isKnown()).isTrue();
        assertThat(loc.toString()).isEqualTo("workflow.yaml:42:5");
    }

    @Test
    void sourceLocation_noColumn_omitsColumn() {
        var loc = new SourceLocation("workflow.yaml", 42, -1);
        assertThat(loc.toString()).isEqualTo("workflow.yaml:42");
    }

    @Test
    void stepActionError_summary_includesStepAndCause() {
        var err = new RuntimeStepError.StepActionError(
                "submit-order", "rest-call", "Connection refused to /api/orders",
                SourceLocation.UNKNOWN, null);
        assertThat(err.category()).isEqualTo(ErrorCategory.STEP_ACTION_FAILED);
        assertThat(err.summary()).contains("submit-order").contains("Connection refused");
    }

    @Test
    void retryExhaustedError_summary_includesAttemptCount() {
        var err = new RuntimeStepError.RetryExhaustedError(
                "submit-order", 3, "timeout", "exponential", Duration.ofSeconds(1),
                SourceLocation.UNKNOWN, null);
        assertThat(err.summary()).contains("3 retry attempts");
        assertThat(err.category()).isEqualTo(ErrorCategory.RETRY_EXHAUSTED);
    }

    @Test
    void deadlineError_summary_includesScopeAndDuration() {
        var err = new CoordinationError.DeadlineError(
                "step-a", "trading-scope", Duration.ofSeconds(30),
                new SourceLocation("flow.yaml", 10, -1), null);
        assertThat(err.summary()).contains("trading-scope").contains("PT30S");
        assertThat(err.location().toString()).isEqualTo("flow.yaml:10");
    }

    @Test
    void unknownStepError_isParseCategoryError() {
        var err = new ParseError.UnknownStepError(
                "process-payment", "nonexistent-action",
                SourceLocation.UNKNOWN, null);
        assertThat(err.category()).isEqualTo(ErrorCategory.UNKNOWN_STEP);
        assertThat(err.summary()).contains("nonexistent-action");
    }

    @Test
    void expressionEvalError_carriesVariableSnapshot() {
        var vars = Map.<String, Object>of("orderId", 42, "status", "PENDING");
        var err = new RuntimeStepError.ExpressionEvalError(
                "check-status", "status == 'ACTIVE'", "MVEL",
                "Null pointer", vars,
                SourceLocation.UNKNOWN, null);
        assertThat(err.resolvedVariables()).containsEntry("orderId", 42);
        assertThat(err.category()).isEqualTo(ErrorCategory.EXPRESSION_EVAL);
    }

    @Test
    void channelError_nullStepName_omitsStepInSummary() {
        var err = new CoordinationError.ChannelError(
                null, "order-channel", SourceLocation.UNKNOWN, null);
        assertThat(err.summary()).contains("order-channel");
        assertThat(err.summary()).doesNotContain("step");
    }
}
