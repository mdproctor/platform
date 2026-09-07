package io.casehub.platform.endpoints.config;

import io.casehub.platform.api.endpoints.EndpointCapability;
import io.casehub.platform.api.endpoints.EndpointDescriptor;
import io.casehub.platform.api.endpoints.EndpointProtocol;
import io.casehub.platform.api.endpoints.EndpointType;
import io.casehub.platform.api.path.PathParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

class EndpointDescriptorParserTest {

    @TempDir
    Path tempDir;

    // ── openStream (tests 1–3) ──────────────────────────────────────────────

    @Test
    void openStream_classpath_loads_existing_resource() throws Exception {
        // test-endpoints.yaml exists in src/test/resources (placeholder from Task 2)
        try (InputStream is = EndpointConfigLoader.openStream("classpath:test-endpoints.yaml")) {
            assertThat(is).isNotNull();
        }
    }

    @Test
    void openStream_filesystem_loads_temp_file() throws Exception {
        Path file = Files.writeString(tempDir.resolve("ep.yaml"), "endpoints: []\n");
        try (InputStream is = EndpointConfigLoader.openStream(file.toString())) {
            assertThat(is).isNotNull();
        }
    }

    @Test
    void openStream_missing_classpath_resource_throws_illegal_argument() {
        assertThatThrownBy(() -> EndpointConfigLoader.openStream("classpath:no-such-file.yaml"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Classpath resource not found");
    }

    // ── parseDescriptor (tests 4–10) ────────────────────────────────────────

    private static Map<String, Object> fullEntry() {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("path",         "external/salesforce/prod");
        entry.put("tenancyId",    "tenant-a");
        entry.put("type",         "WORKER");
        entry.put("protocol",     "HTTP");
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("url", "https://salesforce.example.com/api");
        entry.put("properties",   props);
        entry.put("credentialRef","sf-bearer-token");
        entry.put("capabilities", List.of("SEND", "QUERY"));
        return entry;
    }

    @Test
    void parseDescriptor_all_fields_returns_correct_descriptor() {
        EndpointDescriptor d = EndpointConfigLoader.parseDescriptor(fullEntry(), PathParser.of("/"));

        assertThat(d.path().value()).isEqualTo("external/salesforce/prod");
        assertThat(d.tenancyId()).isEqualTo("tenant-a");
        assertThat(d.type()).isEqualTo(EndpointType.WORKER);
        assertThat(d.protocol()).isEqualTo(EndpointProtocol.HTTP);
        assertThat(d.properties()).isEqualTo(Map.of("url", "https://salesforce.example.com/api"));
        assertThat(d.credentialRef()).isEqualTo("sf-bearer-token");
        assertThat(d.capabilities()).containsExactlyInAnyOrder(
            EndpointCapability.SEND, EndpointCapability.QUERY);
    }

    @Test
    @SuppressWarnings("unchecked")
    void parseDescriptor_interpolates_system_property_in_properties_value() {
        System.setProperty("SALESFORCE_URL", "salesforce.example.com");
        try {
            Map<String, Object> entry = fullEntry();
            ((Map<String, Object>) entry.get("properties")).put("url", "https://${SALESFORCE_URL}/api");
            EndpointDescriptor d = EndpointConfigLoader.parseDescriptor(entry, PathParser.of("/"));
            assertThat(d.properties().get("url")).isEqualTo("https://salesforce.example.com/api");
        } finally {
            System.clearProperty("SALESFORCE_URL");
        }
    }

    @Test
    void parseDescriptor_unresolved_var_in_tenancyId_throws() {
        Map<String, Object> entry = fullEntry();
        entry.put("tenancyId", "${MISSING_TENANT}");
        assertThatThrownBy(() -> EndpointConfigLoader.parseDescriptor(entry, PathParser.of("/")))
            .isInstanceOf(RuntimeException.class)
            .hasMessageContaining("Unresolved variable in field 'tenancyId'");
    }

    @Test
    void parseDescriptor_missing_path_throws() {
        Map<String, Object> entry = fullEntry();
        entry.remove("path");
        assertThatThrownBy(() -> EndpointConfigLoader.parseDescriptor(entry, PathParser.of("/")))
            .isInstanceOf(RuntimeException.class)
            .hasMessageContaining("missing field: path");
    }

    @Test
    void parseDescriptor_unknown_type_throws_illegal_argument_directly() {
        // No outer catch here — IllegalArgumentException propagates from Enum.valueOf() uncaught
        Map<String, Object> entry = fullEntry();
        entry.put("type", "UNKNOWN_TYPE");
        assertThatThrownBy(() -> EndpointConfigLoader.parseDescriptor(entry, PathParser.of("/")))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void parseDescriptor_missing_capabilities_key_throws() {
        Map<String, Object> entry = fullEntry();
        entry.remove("capabilities");
        assertThatThrownBy(() -> EndpointConfigLoader.parseDescriptor(entry, PathParser.of("/")))
            .isInstanceOf(RuntimeException.class)
            .hasMessageContaining("missing field: capabilities");
    }

    @Test
    void parseDescriptor_empty_capabilities_list_returns_empty_set() {
        Map<String, Object> entry = fullEntry();
        entry.put("capabilities", List.of());
        EndpointDescriptor d = EndpointConfigLoader.parseDescriptor(entry, PathParser.of("/"));
        assertThat(d.capabilities()).isEmpty();
    }

    @Test
    void constructor_noop_when_endpointFiles_null() {
        assertDoesNotThrow(() -> new EndpointConfigLoader(null, null, "/"));
    }
}
