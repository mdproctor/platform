package io.casehub.platform.llm.config;

import java.time.Instant;

public record ProviderConfig(String providerId, String vendorKey, String backendKey,
                             String displayName, int modelCount, Instant configuredAt) {}
