package io.casehub.platform.governance;

public class RetryExhaustedException extends PolicyEnforcementException {
    public RetryExhaustedException(String message, Throwable cause) { super(message, cause); }
}
