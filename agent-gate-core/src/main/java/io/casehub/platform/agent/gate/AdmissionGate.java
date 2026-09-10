package io.casehub.platform.agent.gate;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

public final class AdmissionGate {

    private final List<AdmissionStrategy> strategies;

    private AdmissionGate(List<AdmissionStrategy> strategies) {
        this.strategies = List.copyOf(strategies);
    }

    public boolean tryAcquire(Duration timeout) {
        try {
            AdmissionUtils.acquireAll(strategies, timeout);
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }

    public void release() {
        AdmissionUtils.releaseAll(strategies);
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {

        private final List<AdmissionStrategy> strategies = new ArrayList<>();

        private Builder() {}

        public Builder slidingWindow(int maxActions, Duration windowSize) {
            strategies.add(new SlidingWindowStrategy(maxActions, windowSize));
            return this;
        }

        public Builder tokenBucket(double permitsPerSecond, int burstCapacity) {
            strategies.add(new TokenBucketStrategy(permitsPerSecond, burstCapacity));
            return this;
        }

        public Builder concurrency(int max) {
            strategies.add(new ConcurrencyStrategy(max));
            return this;
        }

        public Builder strategy(AdmissionStrategy strategy) {
            strategies.add(strategy);
            return this;
        }

        public AdmissionGate build() {
            return new AdmissionGate(strategies);
        }
    }
}
