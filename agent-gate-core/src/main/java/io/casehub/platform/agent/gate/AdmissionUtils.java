package io.casehub.platform.agent.gate;

import io.casehub.platform.agent.AgentRateLimitException;
import io.casehub.platform.agent.AgentSessionLimitException;

import java.time.Duration;
import java.util.List;

public final class AdmissionUtils {

    private AdmissionUtils() {}

    public static void acquireAll(List<AdmissionStrategy> strategies, Duration timeout) {
        long deadlineNanos = System.nanoTime() + timeout.toNanos();
        for (int i = 0; i < strategies.size(); i++) {
            long remainingNanos = deadlineNanos - System.nanoTime();
            Duration remaining = remainingNanos > 0
                    ? Duration.ofNanos(remainingNanos) : Duration.ZERO;
            try {
                if (!strategies.get(i).tryAcquire(remaining)) {
                    rollbackPrior(strategies, i);
                    throw exceptionFor(strategies.get(i));
                }
            } catch (InterruptedException e) {
                rollbackPrior(strategies, i);
                Thread.currentThread().interrupt();
                throw new RuntimeException(
                        "Interrupted during admission acquisition", e);
            }
        }
    }

    public static void releaseAll(List<AdmissionStrategy> strategies) {
        for (int i = strategies.size() - 1; i >= 0; i--) {
            strategies.get(i).release();
        }
    }

    private static void rollbackPrior(List<AdmissionStrategy> strategies, int failedIndex) {
        for (int j = failedIndex - 1; j >= 0; j--) {
            strategies.get(j).rollback();
        }
    }

    private static RuntimeException exceptionFor(AdmissionStrategy strategy) {
        if (strategy instanceof ConcurrencyStrategy) {
            return new AgentSessionLimitException(0);
        }
        return new AgentRateLimitException(0);
    }
}
