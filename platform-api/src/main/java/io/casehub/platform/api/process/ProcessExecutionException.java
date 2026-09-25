package io.casehub.platform.api.process;

/** Thrown when a process cannot be started or fails in an unrecoverable way. */
public class ProcessExecutionException extends RuntimeException {

    public ProcessExecutionException(String message) {
        super(message);
    }

    public ProcessExecutionException(String message, Throwable cause) {
        super(message, cause);
    }
}
