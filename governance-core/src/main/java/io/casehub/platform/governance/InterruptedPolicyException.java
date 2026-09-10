package io.casehub.platform.governance;

public class InterruptedPolicyException extends PolicyEnforcementException {
    public InterruptedPolicyException(String message, Throwable cause) { super(message, cause); }
}
