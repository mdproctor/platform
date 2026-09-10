package io.casehub.platform.identity;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

class ScimActorDIDProviderUnconfiguredTest {

    @Test
    void didFor_returns_empty_when_scim_unconfigured() {
        ScimAgentLookup unconfigured = ScimAgentLookup.unconfigured();
        ScimActorDIDProvider provider = new ScimActorDIDProvider(unconfigured);
        assertTrue(provider.didFor("claude:reviewer@v1").isEmpty());
    }

    @Test
    void didFor_returns_empty_when_endpoint_is_blank() {
        var lookup = new ScimAgentLookup(
                "", "valid-token", 1000, Duration.ofMinutes(1), true);
        var provider = new ScimActorDIDProvider(lookup);
        assertTrue(provider.didFor("claude:reviewer@v1").isEmpty());
    }

    @Test
    void invalidate_does_not_throw_when_scim_unconfigured() {
        ScimAgentLookup unconfigured = ScimAgentLookup.unconfigured();
        ScimActorDIDProvider provider = new ScimActorDIDProvider(unconfigured);
        assertDoesNotThrow(() -> provider.invalidate("claude:reviewer@v1"));
    }
}
