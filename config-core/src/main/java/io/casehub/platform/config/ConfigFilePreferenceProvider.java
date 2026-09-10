package io.casehub.platform.config;

import io.casehub.platform.api.path.Path;
import io.casehub.platform.api.preferences.MapPreferences;
import io.casehub.platform.api.preferences.PreferenceProvider;
import io.casehub.platform.api.preferences.Preferences;
import io.casehub.platform.api.preferences.SettingsScope;

import java.io.FileInputStream;
import java.io.InputStream;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class ConfigFilePreferenceProvider implements PreferenceProvider {

    private static final Pattern VAR_PATTERN = Pattern.compile("\\$\\{([^}]+)}");

    private final Map<Path, Map<String, String>> loaded = new HashMap<>();
    private final Map<String, String> smDefaults;

    public ConfigFilePreferenceProvider(List<String> configFiles, Map<String, String> smDefaults) {
        this.smDefaults = smDefaults != null ? smDefaults : Map.of();
        if (configFiles != null) {
            for (String fileSpec : configFiles) {
                try (InputStream is = openStream(fileSpec)) {
                    Map<Path, Map<String, String>> parsed = YamlPreferenceLoader.load(is);
                    parsed.forEach((scope, prefs) ->
                        loaded.computeIfAbsent(scope, k -> new HashMap<>()).putAll(prefs));
                } catch (Exception e) {
                    throw new RuntimeException("Failed to load preferences from: " + fileSpec, e);
                }
            }
            loaded.forEach((scope, prefs) -> prefs.replaceAll((k, v) -> interpolate(v)));
        }
    }

    @Override
    public Preferences resolve(SettingsScope scope) {
        Map<String, Object> merged = new HashMap<>();

        Map<String, String> unscoped = loaded.get(null);
        if (unscoped != null) merged.putAll(unscoped);

        collectScoped(merged, scope.scope());

        if (!smDefaults.isEmpty()) merged.putAll(smDefaults);

        return new MapPreferences(merged);
    }

    private void collectScoped(Map<String, Object> merged, Path path) {
        Path parent = path.parent();
        if (parent != null) collectScoped(merged, parent);
        Map<String, String> level = loaded.get(path);
        if (level != null) merged.putAll(level);
    }

    static InputStream openStream(String fileSpec) throws Exception {
        if (fileSpec.startsWith("classpath:")) {
            String resource = fileSpec.substring("classpath:".length());
            InputStream is = Thread.currentThread().getContextClassLoader()
                    .getResourceAsStream(resource);
            if (is == null) {
                throw new IllegalArgumentException("Classpath resource not found: " + resource);
            }
            return is;
        }
        return new FileInputStream(fileSpec);
    }

    static String interpolate(String value) {
        if (value == null || !value.contains("${")) return value;
        Matcher m = VAR_PATTERN.matcher(value);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String key = m.group(1);
            String replacement = System.getProperty(key);
            if (replacement == null) replacement = System.getenv(key);
            if (replacement == null) replacement = m.group(0);
            m.appendReplacement(sb, Matcher.quoteReplacement(replacement));
        }
        m.appendTail(sb);
        return sb.toString();
    }
}
