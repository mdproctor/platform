package io.casehub.platform.endpoints.config;

import org.yaml.snakeyaml.Yaml;
import java.io.InputStream;
import java.util.List;
import java.util.Map;

public final class YamlEndpointLoader {
    private YamlEndpointLoader() {}

    @SuppressWarnings("unchecked")
    public static List<Map<String, Object>> load(InputStream is) {
        if (is == null) return List.of();
        Yaml yaml = new Yaml();
        Map<String, Object> doc = yaml.load(is);
        if (doc == null) return List.of();
        List<Map<String, Object>> entries = (List<Map<String, Object>>) doc.get("endpoints");
        return entries != null ? entries : List.of();
    }
}
