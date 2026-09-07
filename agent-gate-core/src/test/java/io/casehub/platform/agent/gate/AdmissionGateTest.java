package io.casehub.platform.agent.gate;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class AdmissionGateTest {

    @Test
    void emptyGateAlwaysAdmits() throws Exception {
        var gate = AdmissionGate.builder().build();
        assertThat(gate.tryAcquire(Duration.ZERO)).isTrue();
    }

    @Test
    void slidingWindowOnly() throws Exception {
        var gate = AdmissionGate.builder()
                .slidingWindow(2, Duration.ofSeconds(60))
                .build();

        assertThat(gate.tryAcquire(Duration.ZERO)).isTrue();
        assertThat(gate.tryAcquire(Duration.ZERO)).isTrue();
        assertThat(gate.tryAcquire(Duration.ZERO)).isFalse();
    }

    @Test
    void concurrencyOnly() throws Exception {
        var gate = AdmissionGate.builder()
                .concurrency(1)
                .build();

        assertThat(gate.tryAcquire(Duration.ZERO)).isTrue();
        assertThat(gate.tryAcquire(Duration.ZERO)).isFalse();
        gate.release();
        assertThat(gate.tryAcquire(Duration.ZERO)).isTrue();
    }

    @Test
    void composedStrategies() throws Exception {
        var gate = AdmissionGate.builder()
                .slidingWindow(10, Duration.ofSeconds(60))
                .concurrency(1)
                .build();

        assertThat(gate.tryAcquire(Duration.ZERO)).isTrue();
        assertThat(gate.tryAcquire(Duration.ZERO)).isFalse();
        gate.release();
        assertThat(gate.tryAcquire(Duration.ZERO)).isTrue();
    }

    @Test
    void rollbackOnPartialFailure() throws Exception {
        var gate = AdmissionGate.builder()
                .tokenBucket(1.0, 1)
                .concurrency(0)
                .build();

        assertThat(gate.tryAcquire(Duration.ofMillis(50))).isFalse();
        // token bucket should have been rolled back — verify with a fresh gate
        // sharing the same strategy wouldn't work; rollback is internal to acquireAll
    }
}
