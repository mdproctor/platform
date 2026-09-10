package io.casehub.platform.signing;

import io.casehub.platform.api.signing.SignatureResult;
import io.casehub.platform.api.signing.SigningProvider;

import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

public class NoOpSigningProvider implements SigningProvider {
    private static final Logger LOG = Logger.getLogger(NoOpSigningProvider.class.getName());
    private final Set<String> warned = ConcurrentHashMap.newKeySet();

    @Override
    public Optional<SignatureResult> sign(final String actorId, final byte[] data) {
        Objects.requireNonNull(actorId, "actorId must not be null");
        Objects.requireNonNull(data, "data must not be null");
        if (warned.add(actorId)) {
            LOG.warning("No signing backend configured — returning unsigned for actor " + actorId);
        }
        return Optional.empty();
    }
}
