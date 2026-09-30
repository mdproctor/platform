package io.casehub.platform.agent.config;

import io.casehub.platform.api.identity.DIDDocument;
import io.casehub.platform.api.identity.DIDResolver;
import io.casehub.platform.api.identity.VerificationMethod;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.Signature;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class ManifestLoaderSecurityTest {

    @Test
    void verifyDigestAcceptsMatchingHash() throws Exception {
        var content = "models: []\n".getBytes(StandardCharsets.UTF_8);
        var hash = HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(content));
        var result = ManifestLoader.verifyDigest(content, "sha256:" + hash, "test-uri");
        assertThat(result).isTrue();
    }

    @Test
    void verifyDigestRejectsMismatch() throws Exception {
        var content = "models: []\n".getBytes(StandardCharsets.UTF_8);
        var wrongHash = "0".repeat(64);
        var result = ManifestLoader.verifyDigest(content, "sha256:" + wrongHash, "test-uri");
        assertThat(result).isFalse();
    }

    @Test
    void verifyDigestSkipsWhenNotDeclared() {
        var content = "models: []\n".getBytes(StandardCharsets.UTF_8);
        var result = ManifestLoader.verifyDigest(content, null, "test-uri");
        assertThat(result).isTrue();
    }

    @Test
    void verifySignatureAcceptsValid() throws Exception {
        var kpg = KeyPairGenerator.getInstance("Ed25519");
        var kp = kpg.generateKeyPair();
        var content = "models: []\n".getBytes(StandardCharsets.UTF_8);

        var sig = Signature.getInstance("Ed25519");
        sig.initSign(kp.getPrivate());
        sig.update(content);
        var sigBytes = sig.sign();

        var vm = new VerificationMethod("key-1", "Ed25519VerificationKey2020",
                kp.getPublic().getEncoded());
        var didDoc = new DIDDocument("did:web:test.example", List.of(vm), List.of());
        DIDResolver resolver = (actorId, did) -> Optional.of(didDoc);

        var signatureB64 = Base64.getUrlEncoder().withoutPadding().encodeToString(sigBytes);
        var result = ManifestLoader.verifySignature(content, signatureB64,
                "did:web:test.example", resolver, "test-uri");
        assertThat(result).isTrue();
    }

    @Test
    void verifySignatureRejectsInvalidSignature() throws Exception {
        var kpg = KeyPairGenerator.getInstance("Ed25519");
        var kp = kpg.generateKeyPair();
        var content = "models: []\n".getBytes(StandardCharsets.UTF_8);

        var vm = new VerificationMethod("key-1", "Ed25519VerificationKey2020",
                kp.getPublic().getEncoded());
        var didDoc = new DIDDocument("did:web:test.example", List.of(vm), List.of());
        DIDResolver resolver = (actorId, did) -> Optional.of(didDoc);

        var badSig = Base64.getUrlEncoder().withoutPadding().encodeToString(new byte[64]);
        var result = ManifestLoader.verifySignature(content, badSig,
                "did:web:test.example", resolver, "test-uri");
        assertThat(result).isFalse();
    }

    @Test
    void verifySignatureRejectsNullResolver() {
        var content = "models: []\n".getBytes(StandardCharsets.UTF_8);
        var result = ManifestLoader.verifySignature(content, "dGVzdA",
                "did:web:test.example", null, "test-uri");
        assertThat(result).isFalse();
    }

    @Test
    void verifySignatureRejectsUnresolvableDid() {
        var content = "models: []\n".getBytes(StandardCharsets.UTF_8);
        DIDResolver resolver = (actorId, did) -> Optional.empty();
        var result = ManifestLoader.verifySignature(content, "dGVzdA",
                "did:web:test.example", resolver, "test-uri");
        assertThat(result).isFalse();
    }

    @Test
    void verifySignatureRejectsEmptyVerificationMethods() {
        var content = "models: []\n".getBytes(StandardCharsets.UTF_8);
        var didDoc = new DIDDocument("did:web:test.example", List.of(), List.of());
        DIDResolver resolver = (actorId, did) -> Optional.of(didDoc);
        var result = ManifestLoader.verifySignature(content, "dGVzdA",
                "did:web:test.example", resolver, "test-uri");
        assertThat(result).isFalse();
    }

    @Test
    void verifySignatureSkipsWhenNotDeclared() {
        var result = ManifestLoader.verifySignature("data".getBytes(), null, null, null, "test-uri");
        assertThat(result).isTrue();
    }
}
