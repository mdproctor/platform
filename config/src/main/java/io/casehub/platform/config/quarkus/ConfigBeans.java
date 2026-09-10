package io.casehub.platform.config.quarkus;

import io.casehub.platform.config.ConfigFilePreferenceProvider;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.util.List;
import java.util.Map;
import java.util.Optional;

@ApplicationScoped
public class ConfigBeans {

    @Produces
    @ApplicationScoped
    public ConfigFilePreferenceProvider configFilePreferenceProvider(
            @ConfigProperty(name = "casehub.platform.config.files") Optional<List<String>> configFiles,
            @ConfigProperty(name = "casehub.platform.preferences.defaults") Optional<Map<String, String>> smDefaults) {
        return new ConfigFilePreferenceProvider(
                configFiles.orElse(null),
                smDefaults.orElse(null));
    }
}
