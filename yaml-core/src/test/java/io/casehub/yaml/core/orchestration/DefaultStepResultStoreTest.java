package io.casehub.yaml.core.orchestration;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class DefaultStepResultStoreTest {

    @Test
    void recordSuccess_retrievable() {
        var store = new DefaultStepResultStore();
        var result = Map.<String, Object>of("price", 42.5);
        store.recordSuccess("step1", result);
        assertThat(store.result("step1")).isEqualTo(result);
    }

    @Test
    void recordFailure_retrievable() {
        var store = new DefaultStepResultStore();
        var error = new io.casehub.yaml.core.error.RuntimeStepError.StepActionError("step1", null, "failed", io.casehub.yaml.core.error.SourceLocation.UNKNOWN, null);
        store.recordFailure("step1", error);
        assertThat(store.error("step1")).isEqualTo(error);
    }

    @Test
    void result_unknownStep_returnsNull() {
        var store = new DefaultStepResultStore();
        assertThat(store.result("unknown")).isNull();
    }

    @Test
    void error_succeededStep_returnsNull() {
        var store = new DefaultStepResultStore();
        store.recordSuccess("step1", Map.of());
        assertThat(store.error("step1")).isNull();
    }

    @Test
    void hasCompleted_afterSuccess_true() {
        var store = new DefaultStepResultStore();
        store.recordSuccess("step1", Map.of());
        assertThat(store.hasCompleted("step1")).isTrue();
    }

    @Test
    void hasCompleted_afterFailure_true() {
        var store = new DefaultStepResultStore();
        store.recordFailure("step1", new io.casehub.yaml.core.error.RuntimeStepError.StepActionError("step1", null, "err", io.casehub.yaml.core.error.SourceLocation.UNKNOWN, null));
        assertThat(store.hasCompleted("step1")).isTrue();
    }

    @Test
    void hasCompleted_unknown_false() {
        var store = new DefaultStepResultStore();
        assertThat(store.hasCompleted("unknown")).isFalse();
    }
}
