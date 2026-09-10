package io.casehub.platform.config;

import io.casehub.platform.api.path.Path;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class YamlPreferenceLoaderTest {

    private InputStream yaml(String name) {
        InputStream is = getClass().getClassLoader().getResourceAsStream(name);
        assertNotNull(is, "test resource not found: " + name);
        return is;
    }

    @Test
    void unscoped_entry_stored_under_null_key() {
        Map<Path, Map<String, String>> result = YamlPreferenceLoader.load(yaml("loader-test.yaml"));
        assertTrue(result.containsKey(null));
        assertEquals("true", result.get(null).get("devtown.globalDefault"));
    }

    @Test
    void scoped_entry_stored_under_path_key() {
        Map<Path, Map<String, String>> result = YamlPreferenceLoader.load(yaml("loader-test.yaml"));
        Path devtown = Path.of("casehubio", "devtown");
        assertTrue(result.containsKey(devtown));
        assertEquals("500", result.get(devtown).get("devtown.humanApprovalThreshold"));
        assertEquals("false", result.get(devtown).get("devtown.securityReviewRequired"));
    }

    @Test
    void child_scope_stored_independently_from_parent() {
        Map<Path, Map<String, String>> result = YamlPreferenceLoader.load(yaml("loader-test.yaml"));
        Path prReview = Path.of("casehubio", "devtown", "pr-review");
        assertTrue(result.containsKey(prReview));
        assertEquals("100", result.get(prReview).get("devtown.humanApprovalThreshold"));
        // securityReviewRequired not set at pr-review level — must come from parent at resolve time
        assertNull(result.get(prReview).get("devtown.securityReviewRequired"));
    }

    @Test
    void empty_entries_list_returns_empty_map() {
        InputStream empty = new java.io.ByteArrayInputStream("entries: []\n".getBytes());
        Map<Path, Map<String, String>> result = YamlPreferenceLoader.load(empty);
        assertTrue(result.isEmpty());
    }

    @Test
    void null_yaml_content_returns_empty_map() {
        InputStream empty = new java.io.ByteArrayInputStream("".getBytes());
        Map<Path, Map<String, String>> result = YamlPreferenceLoader.load(empty);
        assertTrue(result.isEmpty());
    }
}
