package io.casehub.platform.identity;

import io.casehub.platform.api.identity.ActorDIDProvider;

import java.util.Map;
import java.util.Optional;

public class ConfiguredActorDIDProvider implements ActorDIDProvider {

    private final Map<String, String> dids;

    public ConfiguredActorDIDProvider(final Map<String, String> dids) {
        this.dids = dids;
    }

    @Override
    public Optional<String> didFor(final String actorId) {
        if (actorId == null) return Optional.empty();
        return Optional.ofNullable(dids.get(actorId));
    }
}
