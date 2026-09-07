package io.casehub.platform.identity;

import io.casehub.platform.api.identity.DIDDocument;
import io.casehub.platform.api.identity.DIDResolver;

import java.util.Optional;

public class NoOpDIDResolver implements DIDResolver {
    @Override
    public Optional<DIDDocument> resolve(final String actorId, final String did) {
        return Optional.empty();
    }
}
