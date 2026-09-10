package io.casehub.platform.agent.runtime;

import io.casehub.platform.agent.AgentProcess;

import java.io.InputStream;
import java.io.OutputStream;
import java.util.concurrent.CompletableFuture;

class LocalAgentProcess implements AgentProcess {

    private final Process process;

    LocalAgentProcess(Process process) {
        this.process = process;
    }

    @Override
    public OutputStream stdin() { return process.getOutputStream(); }

    @Override
    public InputStream stdout() { return process.getInputStream(); }

    @Override
    public InputStream stderr() { return process.getErrorStream(); }

    @Override
    public CompletableFuture<Integer> exitCode() {
        return process.onExit().thenApply(Process::exitValue);
    }

    @Override
    public void destroy() { process.destroy(); }

    @Override
    public void destroyForcibly() { process.destroyForcibly(); }

    @Override
    public void close() { destroy(); }
}
