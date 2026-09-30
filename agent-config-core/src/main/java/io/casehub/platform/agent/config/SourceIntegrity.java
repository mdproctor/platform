package io.casehub.platform.agent.config;

public record SourceIntegrity(
        String digest,
        String signature,
        String signer
) {}
