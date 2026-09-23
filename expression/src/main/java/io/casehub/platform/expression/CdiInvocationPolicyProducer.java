package io.casehub.platform.expression;

import io.casehub.platform.api.expression.InvocationPolicy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.util.Optional;
import java.util.Set;

@ApplicationScoped
public class CdiInvocationPolicyProducer {

    @Produces
    @ApplicationScoped
    public InvocationPolicy allowListInvocationPolicy(
            @ConfigProperty(name = "casehub.expression.invoke.allowed-packages") Optional<Set<String>> allowedPackages) {
        return new AllowListInvocationPolicy(allowedPackages.orElse(Set.of()));
    }
}
