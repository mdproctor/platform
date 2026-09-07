package io.casehub.platform.agent.runtime;

import io.casehub.platform.agent.AgentProcess;
import io.casehub.platform.agent.AgentRuntime;
import io.casehub.platform.agent.AgentRuntimeConfig;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public class SubprocessRuntime implements AgentRuntime {

    @Override
    public AgentProcess spawn(AgentRuntimeConfig config) throws IOException {
        List<String> cmd = new ArrayList<>();
        cmd.add(config.command());
        cmd.addAll(config.args());
        ProcessBuilder pb = new ProcessBuilder(cmd);
        if (config.workingDirectory() != null) {
            pb.directory(config.workingDirectory().toFile());
        }
        pb.environment().putAll(config.env());
        return new LocalAgentProcess(pb.start());
    }
}
