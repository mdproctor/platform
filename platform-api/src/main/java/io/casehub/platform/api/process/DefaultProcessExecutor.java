package io.casehub.platform.api.process;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/** Default {@link ProcessExecutor} backed by {@link ProcessBuilder}. */
public class DefaultProcessExecutor implements ProcessExecutor {

    @Override
    public ProcessResult execute(String... command) {
        return execute(ProcessCommand.of(command));
    }

    @Override
    public ProcessResult execute(ProcessCommand command) {
        var pb = new ProcessBuilder(command.command());
        if (command.workingDir() != null) {
            pb.directory(new File(command.workingDir()));
        }
        pb.redirectErrorStream(command.mergeStderr());

        Process process;
        try {
            process = pb.start();
        } catch (IOException e) {
            throw new ProcessExecutionException(
                    "Failed to start process: " + String.join(" ", command.command()), e);
        }

        var stdoutFuture = readAsync(process.getInputStream());
        var stderrFuture = command.mergeStderr() ? CompletableFuture.completedFuture("") : readAsync(process.getErrorStream());

        try {
            boolean completed;
            if (command.timeout() != null) {
                completed = process.waitFor(command.timeout().toMillis(), TimeUnit.MILLISECONDS);
            } else {
                process.waitFor();
                completed = true;
            }

            if (!completed) {
                process.destroyForcibly();
                process.waitFor(5, TimeUnit.SECONDS);
                String stdout = stdoutFuture.getNow("").trim();
                String stderr = stderrFuture.getNow("").trim();
                return new ProcessResult(-1, stdout, stderr, true);
            }

            String stdout = stdoutFuture.get().trim();
            String stderr = stderrFuture.get().trim();
            return new ProcessResult(process.exitValue(), stdout, stderr, false);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
            throw new ProcessExecutionException("Process interrupted", e);
        } catch (java.util.concurrent.ExecutionException e) {
            throw new ProcessExecutionException(
                    "Error reading process output: " + String.join(" ", command.command()), e);
        }
    }

    private static CompletableFuture<String> readAsync(InputStream stream) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return new String(stream.readAllBytes());
            } catch (IOException e) {
                return "";
            }
        });
    }
}
