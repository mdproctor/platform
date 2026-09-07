package io.casehub.platform.identity;

import io.casehub.platform.api.identity.ActorDIDProvider;

import java.util.Optional;

public class ScimActorDIDProvider implements ActorDIDProvider {

    private final ScimAgentLookup lookup;

    public ScimActorDIDProvider(final ScimAgentLookup lookup) {
        this.lookup = lookup;
    }

    @Override
    public Optional<String> didFor(final String actorId) {
        if (lookup == null || !lookup.isConfigured()) return Optional.empty();
        return lookup.get(actorId).map(ScimAgentResource::did);
    }

    @Override
    public void invalidate(final String actorId) {
        if (lookup != null) {
            lookup.invalidate(actorId);
        }
    }
}
