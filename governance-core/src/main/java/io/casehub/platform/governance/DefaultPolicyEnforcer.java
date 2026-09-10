package io.casehub.platform.governance;

import io.casehub.platform.api.governance.BackoffStrategy;
import io.casehub.platform.api.governance.ExecutionPolicy;
import io.casehub.platform.api.governance.RetryPolicy;

import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

public class DefaultPolicyEnforcer implements PolicyEnforcer, AutoCloseable {

    private final ExecutorService timeoutExecutor = Executors.newVirtualThreadPerTaskExecutor();

    @Override
    public <T> T execute(ExecutionPolicy policy, Supplier<T> action) {
        int maxAttempts = 1;
        int delayMs = 0;
        BackoffStrategy backoff = BackoffStrategy.FIXED;
        Integer maxDelayMs = null;

        if (policy.retries() != null) {
            RetryPolicy retry = policy.retries();
            if (retry.maxAttempts() != null) maxAttempts = retry.maxAttempts();
            if (retry.delayMs() != null) delayMs = retry.delayMs();
            if (retry.backoffStrategy() != null) backoff = retry.backoffStrategy();
            maxDelayMs = retry.maxDelayMs();
        }

        Exception lastException = null;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                return executeWithTimeout(policy.timeoutMs(), action);
            } catch (Exception e) {
                lastException = e;
                if (e instanceof InterruptedPolicyException) break;
                if (attempt < maxAttempts) {
                    sleep(computeDelay(delayMs, backoff, attempt, maxDelayMs));
                }
            }
        }
        if (lastException instanceof PolicyEnforcementException pe) {
            throw pe;
        }
        throw new RetryExhaustedException(
            "All " + maxAttempts + " attempts failed", lastException);
    }

    @Override
    public void close() {
        timeoutExecutor.shutdownNow();
    }

    private <T> T executeWithTimeout(Integer timeoutMs, Supplier<T> action) {
        if (timeoutMs == null) {
            try {
                return action.get();
            } catch (Exception e) {
                if (Thread.currentThread().isInterrupted()) {
                    throw new InterruptedPolicyException("Interrupted during execution", e);
                }
                throw e;
            }
        }
        Callable<T> callable = action::get;
        Future<T> future = timeoutExecutor.submit(callable);
        try {
            return future.get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            throw new TimeoutPolicyException("Action timed out after " + timeoutMs + "ms");
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException re) throw re;
            throw new PolicyEnforcementException("Action failed", cause);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new InterruptedPolicyException("Interrupted during execution", e);
        }
    }

    private long computeDelay(int baseDelayMs, BackoffStrategy strategy, int attempt, Integer maxDelayMs) {
        long delay = switch (strategy) {
            case FIXED -> baseDelayMs;
            case EXPONENTIAL -> (long) (baseDelayMs * Math.pow(2, attempt - 1));
            case EXPONENTIAL_WITH_JITTER -> {
                long exponential = (long) (baseDelayMs * Math.pow(2, attempt - 1));
                yield exponential + ThreadLocalRandom.current().nextLong(exponential / 2 + 1);
            }
        };
        if (maxDelayMs != null && delay > maxDelayMs) {
            delay = maxDelayMs;
        }
        return delay;
    }

    private void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new InterruptedPolicyException("Interrupted during backoff", e);
        }
    }
}
