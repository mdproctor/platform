package io.casehub.platform.preferences.editor;

import java.util.Map;

public record ResolvedPreferencesResponse(String scope, Map<String, String> values) {}
