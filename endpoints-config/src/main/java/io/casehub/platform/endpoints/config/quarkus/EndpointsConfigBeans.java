package io.casehub.platform.endpoints.config.quarkus;

import io.casehub.platform.api.endpoints.EndpointRegistry;
import io.casehub.platform.endpoints.config.EndpointConfigLoader;
import io.quarkus.runtime.Startup;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.util.List;
import java.util.Optional;

@ApplicationScoped
public class EndpointsConfigBeans {

    @Startup
    @Produces
    @ApplicationScoped
    public EndpointConfigLoader endpointConfigLoader(
            EndpointRegistry registry,
            @ConfigProperty(name = "casehub.platform.endpoints.files") Optional<List<String>> endpointFiles,
            @ConfigProperty(name = "casehub.platform.path.separator", defaultValue = "/") String pathSeparator) {
        return new EndpointConfigLoader(registry, endpointFiles.orElse(null), pathSeparator);
    }
}
