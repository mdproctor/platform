package io.casehub.platform.governance;

public class PolicyEnforcementException extends RuntimeException {
    public PolicyEnforcementException(String message) { super(message); }
    public PolicyEnforcementException(String message, Throwable cause) { super(message, cause); }
}
