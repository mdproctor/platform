package io.casehub.platform.llm.config;

import java.util.Map;

public record ConfigureRequest(String vendorKey, Map<String, String> credentials, String displayName) {}
