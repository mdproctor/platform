package io.casehub.platform.identity;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

class ScimAgentLookupTest {

    @Test
    void unconfigured_returns_empty() {
        var lookup = ScimAgentLookup.unconfigured();
        assertFalse(lookup.isConfigured());
        assertTrue(lookup.get("claude:reviewer@v1").isEmpty());
    }

    @Test
    void isConfigured_true_when_endpoint_set() {
        var lookup = new ScimAgentLookup(
                "https://scim.example.com", "token", 5000, Duration.ofMinutes(5), true);
        assertTrue(lookup.isConfigured());
    }

    @Test
    void validate_throws_when_endpoint_blank() {
        var lookup = new ScimAgentLookup("", "token", 5000, Duration.ofMinutes(5), true);
        assertThrows(IllegalArgumentException.class, lookup::validate);
    }

    @Test
    void validate_throws_when_http_with_requireHttps() {
        var lookup = new ScimAgentLookup(
                "http://scim.example.com", "token", 5000, Duration.ofMinutes(5), true);
        var ex = assertThrows(IllegalArgumentException.class, lookup::validate);
        assertTrue(ex.getMessage().contains("HTTPS"));
    }

    @Test
    void validate_allows_http_when_requireHttps_false() {
        var lookup = new ScimAgentLookup(
                "http://localhost:8080", "token", 5000, Duration.ofMinutes(5), false);
        assertDoesNotThrow(lookup::validate);
    }

    @Test
    void validate_throws_when_authToken_blank() {
        var lookup = new ScimAgentLookup(
                "https://scim.example.com", "", 5000, Duration.ofMinutes(5), true);
        var ex = assertThrows(IllegalArgumentException.class, lookup::validate);
        assertTrue(ex.getMessage().contains("auth-token"));
    }

    @Test
    void get_validates_lazily_on_first_use() {
        var lookup = new ScimAgentLookup(
                "http://scim.example.com", "token", 5000, Duration.ofMinutes(5), true);
        assertTrue(lookup.isConfigured());
        var ex = assertThrows(IllegalArgumentException.class,
                () -> lookup.get("claude:reviewer@v1"));
        assertTrue(ex.getMessage().contains("HTTPS"));
    }

    @Test
    void get_validates_authToken_lazily() {
        var lookup = new ScimAgentLookup(
                "https://scim.example.com", "", 5000, Duration.ofMinutes(5), true);
        assertTrue(lookup.isConfigured());
        var ex = assertThrows(IllegalArgumentException.class,
                () -> lookup.get("claude:reviewer@v1"));
        assertTrue(ex.getMessage().contains("auth-token"));
    }
}
