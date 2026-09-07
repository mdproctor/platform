package io.casehub.platform.identity;

import io.casehub.platform.api.identity.AgentCredentialValidator;
import io.casehub.platform.api.identity.CredentialValidationResult;

import java.util.Optional;

public class NoOpCredentialValidator implements AgentCredentialValidator {
    @Override
    public Optional<CredentialValidationResult> validate(final String actorId, final String did) {
        return Optional.empty();
    }
}
