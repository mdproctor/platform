package io.casehub.platform.delivery.digest.inmem.quarkus;

import io.casehub.platform.api.preferences.PreferenceProvider;
import io.casehub.platform.delivery.digest.inmem.InMemoryDigestBuffer;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Alternative;
import jakarta.enterprise.inject.Produces;
import org.eclipse.microprofile.config.inject.ConfigProperty;

@ApplicationScoped
public class DigestInmemBeans {

    @Produces
    @Alternative
    @Priority(100)
    @ApplicationScoped
    public InMemoryDigestBuffer inMemoryDigestBuffer(
            @ConfigProperty(name = "casehub.notification.digest.max-buffer-size", defaultValue = "500") int maxBufferSize,
            PreferenceProvider preferenceProvider) {
        return new InMemoryDigestBuffer(maxBufferSize, preferenceProvider);
    }
}
