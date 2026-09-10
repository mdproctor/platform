package io.casehub.platform.agent.runtime.quarkus;

import io.casehub.platform.agent.runtime.SubprocessRuntime;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;

@ApplicationScoped
public class RuntimeBeans {

    @Produces
    @ApplicationScoped
    public SubprocessRuntime subprocessRuntime() {
        return new SubprocessRuntime();
    }
}
