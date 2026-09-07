package io.casehub.platform.callback;

public class CallbackInvocationException extends RuntimeException {

    public CallbackInvocationException(String message) {
        super(message);
    }

    public CallbackInvocationException(String message, Throwable cause) {
        super(message, cause);
    }
}
