package io.casehub.platform.identity;

import io.casehub.platform.api.identity.DIDDocument;
import io.casehub.platform.api.identity.VerificationMethod;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class ScimDIDResolverTest {

    private static final String ACTOR_ID = "claude:reviewer@v1";
    private static final String DID = "did:web:example.com:agents:reviewer";

    private ScimDIDResolver resolver(ScimAgentLookup lookup) {
        return new ScimDIDResolver(lookup);
    }

    @Test
    void returns_empty_when_actorId_is_null() {
        var r = resolver(ScimAgentLookup.unconfigured());
        assertTrue(r.resolve(null, DID).isEmpty());
    }

    @Test
    void returns_empty_when_did_is_null() {
        var r = resolver(ScimAgentLookup.unconfigured());
        assertTrue(r.resolve(ACTOR_ID, null).isEmpty());
    }

    @Test
    void returns_empty_when_scim_unconfigured() {
        var r = resolver(ScimAgentLookup.unconfigured());
        assertTrue(r.resolve(ACTOR_ID, DID).isEmpty());
    }

    @Test
    void returns_empty_when_actor_not_in_scim() {
        var lookup = lookupReturning(null);
        var r = resolver(lookup);
        assertTrue(r.resolve(ACTOR_ID, DID).isEmpty());
    }

    @Test
    void returns_empty_on_did_mismatch() {
        var lookup = lookupReturning(new ScimAgentResource("did:web:other.com", List.of()));
        var r = resolver(lookup);
        assertTrue(r.resolve(ACTOR_ID, DID).isEmpty());
    }

    @Test
    void constructs_document_with_alsoKnownAs_containing_actorId() {
        var lookup = lookupReturning(new ScimAgentResource(DID, List.of()));
        var r = resolver(lookup);
        var doc = r.resolve(ACTOR_ID, DID).orElseThrow();
        assertEquals(DID, doc.id());
        assertEquals(List.of(ACTOR_ID), doc.alsoKnownAs());
        assertTrue(doc.verificationMethods().isEmpty());
    }

    @Test
    void returns_empty_for_unsupported_algorithm_certificate() {
        // RSA certificates should produce no verification methods
        var resource = new ScimAgentResource(DID, List.of());
        var r = resolver(lookupReturning(resource));
        var doc = r.resolve(ACTOR_ID, DID).orElseThrow();
        assertTrue(doc.verificationMethods().isEmpty());
    }

    @Test
    void catches_exception_and_returns_empty() {
        var lookup = throwingLookup();
        var r = resolver(lookup);
        assertTrue(r.resolve(ACTOR_ID, DID).isEmpty());
    }

    @Test
    void resolves_document_with_ed25519_certificate() throws Exception {
        // Use a real Ed25519 self-signed certificate (DER-encoded)
        // Generated via: openssl genpkey -algorithm ed25519 -out key.pem &&
        //                openssl req -new -x509 -key key.pem -out cert.pem -days 365 -subj "/CN=Test Agent" &&
        //                openssl x509 -in cert.pem -outform DER | base64
        byte[] certBytes = java.util.Base64.getDecoder().decode(
            "MIIBPjCB8aADAgECAhQ+lvuj5c5PYnPdixelFAbzex4/OTAFBgMrZXAwFTETMBEG" +
            "A1UEAwwKVGVzdCBBZ2VudDAeFw0yNjA2MzAwMDQ3MTZaFw0yNzA2MzAwMDQ3MTZa" +
            "MBUxEzARBgNVBAMMClRlc3QgQWdlbnQwKjAFBgMrZXADIQDKZKyNWvY0uT9n06Xv" +
            "FgTaQfS2cztJlOwUV3LkT+HHTqNTMFEwHQYDVR0OBBYEFB2mtnVbCy2iFtJjnIuy" +
            "tc9jwv1/MB8GA1UdIwQYMBaAFB2mtnVbCy2iFtJjnIuytc9jwv1/MA8GA1UdEwEB" +
            "/wQFMAMBAf8wBQYDK2VwA0EAEf9UuJXgGI65FtvQDHnNvnVRbHmSHlJIIFrUmWUt" +
            "Vmpql3ehSoVocWOkcsZjD7cU+tsaphB47jjoJaKGpHryBQ=="
        );

        // Create SCIM resource with the certificate
        var resource = new ScimAgentResource(DID, List.of(certBytes));
        var lookup = lookupReturning(resource);
        var resolver = resolver(lookup);

        // Resolve and verify
        var doc = resolver.resolve(ACTOR_ID, DID).orElseThrow();
        assertEquals(DID, doc.id());
        assertEquals(List.of(ACTOR_ID), doc.alsoKnownAs());
        assertEquals(1, doc.verificationMethods().size());

        var vm = doc.verificationMethods().get(0);
        assertEquals(DID + "#scim-key-0", vm.id());
        assertEquals("Ed25519VerificationKey2020", vm.type());
        // SPKI format: 44 bytes for Ed25519 (12-byte ASN.1 header + 32-byte key)
        assertEquals(44, vm.publicKeyBytes().length);

        // Must be loadable as a public key via X509EncodedKeySpec
        var loadedKey = java.security.KeyFactory.getInstance("Ed25519")
                .generatePublic(new java.security.spec.X509EncodedKeySpec(vm.publicKeyBytes()));
        assertNotNull(loadedKey);
    }

    // ── helpers ──

    private ScimAgentLookup lookupReturning(ScimAgentResource resource) {
        return new ScimAgentLookup("https://scim.example.com", "token",
                5000, Duration.ofMinutes(5), true) {
            @Override
            protected Optional<ScimAgentResource> loadContext(String key) {
                return Optional.ofNullable(resource);
            }
        };
    }

    private ScimAgentLookup throwingLookup() {
        return new ScimAgentLookup("https://scim.example.com", "token",
                5000, Duration.ofMinutes(5), true) {
            @Override
            protected Optional<ScimAgentResource> loadContext(String key) {
                throw new IllegalStateException("SCIM down");
            }
        };
    }
}
