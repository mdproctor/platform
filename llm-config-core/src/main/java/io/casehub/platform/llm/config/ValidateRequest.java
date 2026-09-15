package io.casehub.platform.llm.config;

import java.util.Map;

public record ValidateRequest(String vendorKey, Map<String, String> credentials) {}
