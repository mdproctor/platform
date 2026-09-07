package io.casehub.platform.governance;

public class TimeoutPolicyException extends PolicyEnforcementException {
    public TimeoutPolicyException(String message) { super(message); }
}
