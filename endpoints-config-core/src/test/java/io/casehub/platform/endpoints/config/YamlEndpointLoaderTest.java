package io.casehub.platform.endpoints.config;

import org.junit.jupiter.api.Test;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import static org.assertj.core.api.Assertions.assertThat;

class YamlEndpointLoaderTest {

    private static InputStream yaml(String content) {
        return new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void load_returns_raw_maps_for_valid_yaml() {
        InputStream is = yaml(
            "endpoints:\n" +
            "  - path: external/salesforce/prod\n" +
            "    tenancyId: tenant-a\n" +
            "    type: WORKER\n" +
            "    protocol: HTTP\n" +
            "    capabilities:\n" +
            "      - SEND\n");

        List<Map<String, Object>> result = YamlEndpointLoader.load(is);

        assertThat(result).hasSize(1);
        assertThat(result.get(0)).containsEntry("path", "external/salesforce/prod")
            .containsEntry("tenancyId", "tenant-a")
            .containsEntry("type", "WORKER")
            .containsEntry("capabilities", List.of("SEND"));
    }

    @Test
    void load_returns_empty_when_endpoints_key_absent() {
        assertThat(YamlEndpointLoader.load(yaml("other: value\n"))).isEmpty();
    }

    @Test
    void load_returns_empty_for_null_stream() {
        assertThat(YamlEndpointLoader.load(null)).isEmpty();
    }

    @Test
    void load_returns_empty_for_empty_endpoints_list() {
        // doc.get("endpoints") returns empty List, not null — distinct code path from absent key
        assertThat(YamlEndpointLoader.load(yaml("endpoints: []\n"))).isEmpty();
    }
}
