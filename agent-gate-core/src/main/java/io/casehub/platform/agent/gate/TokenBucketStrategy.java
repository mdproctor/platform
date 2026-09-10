package io.casehub.platform.agent.gate;

import java.time.Duration;

public final class TokenBucketStrategy implements AdmissionStrategy {

    private final TokenBucket bucket;

    public TokenBucketStrategy(double permitsPerSecond, int burstCapacity) {
        this.bucket = new TokenBucket(permitsPerSecond, burstCapacity);
    }

    @Override
    public Scope scope() {
        return Scope.INVOCATION;
    }

    @Override
    public boolean tryAcquire(Duration timeout) throws InterruptedException {
        return bucket.tryAcquire(timeout);
    }

    @Override
    public void release() {
    }

    @Override
    public void rollback() {
        bucket.release();
    }
}
