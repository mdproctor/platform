package io.casehub.platform.identity;

import io.casehub.platform.api.identity.DIDResolver;
import io.casehub.platform.api.identity.IdentityVerificationResult;

import java.util.Arrays;

public class AgentIdentityVerificationService {

    private final DIDResolver resolver;

    public AgentIdentityVerificationService(final DIDResolver resolver) {
        this.resolver = resolver;
    }

    public IdentityVerificationResult verifyIdentityBinding(
            final String actorId,
            final String actorDid,
            final byte[] agentPublicKey) {

        if (actorDid == null) return IdentityVerificationResult.UNVERIFIABLE;
        if (agentPublicKey == null) return IdentityVerificationResult.UNSIGNED;

        final var docOpt = resolver.resolve(actorId, actorDid);
        if (docOpt.isEmpty()) return IdentityVerificationResult.DID_UNRESOLVABLE;

        final var doc = docOpt.get();
        if (!doc.alsoKnownAs().contains(actorId)) {
            return IdentityVerificationResult.IDENTITY_MISMATCH;
        }

        final boolean keyMatch = doc.verificationMethods().stream()
                .anyMatch(vm -> Arrays.equals(vm.publicKeyBytes(), agentPublicKey));
        return keyMatch ? IdentityVerificationResult.VALID : IdentityVerificationResult.KEY_MISMATCH;
    }
}
