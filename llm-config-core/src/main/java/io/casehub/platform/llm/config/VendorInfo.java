package io.casehub.platform.llm.config;

import java.util.List;

public record VendorInfo(String vendorKey, String backendKey, String displayName,
                         String authMethod, List<String> requiredFields) {}
