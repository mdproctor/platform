package io.casehub.platform.identity.quarkus;

import io.casehub.platform.api.identity.ActorDIDProvider;
import io.casehub.platform.api.identity.ActorDIDSource;
import io.casehub.platform.api.identity.DIDMethod;
import io.casehub.platform.api.identity.DIDResolver;
import io.casehub.platform.identity.AgentIdentityVerificationService;
import io.casehub.platform.identity.CompositeActorDIDProvider;
import io.casehub.platform.identity.CompositeDIDResolver;
import io.casehub.platform.identity.ConfiguredActorDIDProvider;
import io.casehub.platform.identity.JwtVCValidator;
import io.casehub.platform.identity.KeyDIDResolver;
import io.casehub.platform.identity.ScimActorDIDProvider;
import io.casehub.platform.identity.ScimAgentLookup;
import io.casehub.platform.identity.ScimDIDResolver;
import io.casehub.platform.identity.WebDIDResolver;
import io.casehub.platform.identity.CdiPriorityUtils;
import io.casehub.platform.identity.config.IdentityConfig;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.enterprise.inject.Produces;

import java.time.Duration;
import java.util.List;

@ApplicationScoped
public class IdentityBeans {

    // --- DID Resolvers ---

    @Produces
    @ApplicationScoped
    @DIDMethod
    @Priority(100)
    public KeyDIDResolver keyDIDResolver() {
        return new KeyDIDResolver();
    }

    @Produces
    @ApplicationScoped
    @DIDMethod
    @Priority(100)
    public WebDIDResolver webDIDResolver(IdentityConfig config) {
        return new WebDIDResolver(config.webResolverTimeoutMs(), config.webResolverMaxResponseBytes());
    }

    @Produces
    @ApplicationScoped
    public ScimAgentLookup scimAgentLookup(IdentityConfig config) {
        return new ScimAgentLookup(
                config.scim().endpoint().orElse(""),
                config.scim().authToken().orElse(""),
                config.scim().timeoutMs(),
                Duration.ofMinutes(config.scim().cacheTtlMinutes()),
                config.scim().requireHttps());
    }

    @Produces
    @ApplicationScoped
    @DIDMethod
    @Priority(1000)
    public ScimDIDResolver scimDIDResolver(ScimAgentLookup lookup) {
        return new ScimDIDResolver(lookup);
    }

    @Produces
    @ApplicationScoped
    public CompositeDIDResolver compositeDIDResolver(@DIDMethod Instance<DIDResolver> methodResolvers) {
        return new CompositeDIDResolver(CdiPriorityUtils.toSortedList(methodResolvers));
    }

    // --- Actor DID Providers ---

    @Produces
    @ApplicationScoped
    @ActorDIDSource
    @Priority(100)
    public ConfiguredActorDIDProvider configuredActorDIDProvider(IdentityConfig config) {
        return new ConfiguredActorDIDProvider(config.dids());
    }

    @Produces
    @ApplicationScoped
    @ActorDIDSource
    @Priority(200)
    public ScimActorDIDProvider scimActorDIDProvider(ScimAgentLookup lookup) {
        return new ScimActorDIDProvider(lookup);
    }

    @Produces
    @ApplicationScoped
    public CompositeActorDIDProvider compositeActorDIDProvider(@ActorDIDSource Instance<ActorDIDProvider> sources) {
        return new CompositeActorDIDProvider(CdiPriorityUtils.toSortedList(sources));
    }

    // --- Verification ---

    @Produces
    @ApplicationScoped
    public AgentIdentityVerificationService agentIdentityVerificationService(DIDResolver resolver) {
        return new AgentIdentityVerificationService(resolver);
    }

    @Produces
    @ApplicationScoped
    public JwtVCValidator jwtVCValidator(IdentityConfig config, DIDResolver resolver) {
        return new JwtVCValidator(config.credentials(), resolver,
                Duration.ofMinutes(config.credentialCacheTtlMinutes()));
    }
}
