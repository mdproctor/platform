package io.casehub.platform.api.process;

/** Structured result of a process execution: exit code, captured output, and timeout status. */
public record ProcessResult(int exitCode, String stdout, String stderr, boolean isTimedOut) {

    public boolean isSuccess() {
        return !isTimedOut && exitCode == 0;
    }

    public ProcessResult successOrThrow() {
        if (isSuccess()) return this;
        String detail = stderr != null && !stderr.isBlank() ? stderr : stdout;
        throw new ProcessExecutionException(
                "Process exited with code " + exitCode + (isTimedOut ? " (timed out)" : "") +
                (detail != null && !detail.isBlank() ? ": " + detail.trim() : ""));
    }
}
