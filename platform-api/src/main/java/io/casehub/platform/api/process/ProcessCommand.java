package io.casehub.platform.api.process;

import java.time.Duration;
import java.util.List;

/** Immutable command descriptor with optional working directory, timeout, and stderr merge. */
public final class ProcessCommand {

    private final List<String> command;
    private final String workingDir;
    private final Duration timeout;
    private final boolean mergeStderr;

    private ProcessCommand(List<String> command, String workingDir, Duration timeout, boolean mergeStderr) {
        this.command = List.copyOf(command);
        this.workingDir = workingDir;
        this.timeout = timeout;
        this.mergeStderr = mergeStderr;
    }

    public static ProcessCommand of(String... command) {
        return new ProcessCommand(List.of(command), null, null, false);
    }

    public ProcessCommand workingDir(String workingDir) {
        return new ProcessCommand(command, workingDir, timeout, mergeStderr);
    }

    public ProcessCommand timeout(Duration timeout) {
        return new ProcessCommand(command, workingDir, timeout, mergeStderr);
    }

    public ProcessCommand mergeStderr(boolean merge) {
        return new ProcessCommand(command, workingDir, timeout, merge);
    }

    public List<String> command() { return command; }
    public String workingDir() { return workingDir; }
    public Duration timeout() { return timeout; }
    public boolean mergeStderr() { return mergeStderr; }
}
