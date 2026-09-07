package io.casehub.platform.identity;

import io.casehub.platform.api.identity.ActorDIDProvider;

import java.util.Optional;

public class NoOpActorDIDProvider implements ActorDIDProvider {
    @Override
    public Optional<String> didFor(final String actorId) {
        return Optional.empty();
    }
}
