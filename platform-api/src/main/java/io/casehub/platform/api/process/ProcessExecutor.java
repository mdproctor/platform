package io.casehub.platform.api.process;

/**
 * Structured process execution SPI. Wraps {@link ProcessBuilder} with timeout enforcement,
 * separate stdout/stderr capture, and exit code classification.
 *
 * <pre>{@code
 * ProcessExecutor executor = new DefaultProcessExecutor();
 *
 * // Simple — varargs command
 * ProcessResult result = executor.execute("tmux", "has-session", "-t", "my-session");
 *
 * // Structured — with working directory and timeout
 * ProcessResult result = executor.execute(
 *     ProcessCommand.of("claude", "--model", "opus")
 *         .workingDir("/workspace")
 *         .timeout(Duration.ofSeconds(30)));
 *
 * // Check success or throw
 * result.successOrThrow();
 * }</pre>
 */
public interface ProcessExecutor {

    ProcessResult execute(String... command);

    ProcessResult execute(ProcessCommand command);
}
