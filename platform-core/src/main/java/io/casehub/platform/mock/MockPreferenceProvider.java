package io.casehub.platform.mock;

import io.casehub.platform.api.preferences.MapPreferences;
import io.casehub.platform.api.preferences.PreferenceProvider;
import io.casehub.platform.api.preferences.Preferences;
import io.casehub.platform.api.preferences.SettingsScope;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.Collectors;

public class MockPreferenceProvider implements PreferenceProvider {

    private final Map<String, String> defaults;

    public MockPreferenceProvider() { this(Map.of()); }

    public MockPreferenceProvider(Map<String, String> defaults) {
        this.defaults = defaults != null ? defaults : Map.of();
    }

    @Override
    public Preferences resolve(SettingsScope scope) {
        Map<String, Object> objectMap = new HashMap<>();
        defaults.forEach((k, v) -> objectMap.put(k, parseValue(v)));
        return new MapPreferences(objectMap);
    }

    private static Object parseValue(String s) {
        if ("true".equalsIgnoreCase(s)) return Boolean.TRUE;
        if ("false".equalsIgnoreCase(s)) return Boolean.FALSE;
        try { return Integer.parseInt(s); } catch (NumberFormatException ignored) {}
        try { return Long.parseLong(s); } catch (NumberFormatException ignored) {}
        try { return Double.parseDouble(s); } catch (NumberFormatException ignored) {}
        if (s.contains(",")) {
            return Arrays.stream(s.split(",")).map(String::strip).filter(p -> !p.isEmpty()).collect(Collectors.toList());
        }
        return s;
    }
}
