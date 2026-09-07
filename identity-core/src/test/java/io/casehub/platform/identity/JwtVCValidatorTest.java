package io.casehub.platform.identity;

import io.casehub.platform.api.identity.CredentialValidationResult;
import io.casehub.platform.api.identity.DIDDocument;
import io.casehub.platform.api.identity.DIDResolver;
import io.casehub.platform.api.identity.VerificationMethod;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class JwtVCValidatorTest {

    @TempDir
    Path tempDir;

    private KeyPair issuerKeyPair;
    private static final String ISSUER_DID = "did:key:zTestIssuer";
    private static final String ACTOR_ID = "claude:reviewer@v1";
    private static final String ACTOR_DID = "did:web:example.com:agents:reviewer";

    @BeforeEach
    void setUp() throws Exception {
        issuerKeyPair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
    }

    private String signJwt(final String headerJson, final String payloadJson) throws Exception {
        final String header = base64url(headerJson.getBytes());
        final String payload = base64url(payloadJson.getBytes());
        final String signingInput = header + "." + payload;
        final Signature sig = Signature.getInstance("Ed25519");
        sig.initSign(issuerKeyPair.getPrivate());
        sig.update(signingInput.getBytes());
        final String signature = base64url(sig.sign());
        return signingInput + "." + signature;
    }

    private String base64url(final byte[] data) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(data);
    }

    private String validHeaderJson() {
        return """
                {"alg":"EdDSA","typ":"vc+jwt"}""";
    }

    private String validPayloadJson(final long expEpochSecond) {
        return """
                {"iss":"%s","sub":"%s","exp":%d,"vc":{"credentialSubject":{"id":"%s","actorId":"%s"}}}"""
                .formatted(ISSUER_DID, ACTOR_DID, expEpochSecond, ACTOR_DID, ACTOR_ID);
    }

    private Path writeCredentialFile(final String jwt) throws IOException {
        final Path file = tempDir.resolve("credential.jwt");
        Files.writeString(file, jwt);
        return file;
    }

    private DIDResolver resolverReturning(final DIDDocument doc) {
        return (actorId, did) -> ISSUER_DID.equals(did) ? Optional.ofNullable(doc) : Optional.empty();
    }

    private DIDDocument issuerDocument() {
        final byte[] spkiBytes = issuerKeyPair.getPublic().getEncoded();
        final var vm = new VerificationMethod(ISSUER_DID + "#key-0", "Ed25519VerificationKey2020", spkiBytes);
        return new DIDDocument(ISSUER_DID, List.of(vm), List.of());
    }

    private JwtVCValidator createValidator(final Map<String, String> credentials,
                                           final DIDResolver resolver) {
        return new JwtVCValidator(credentials, resolver, Duration.ofHours(24));
    }

    // ── Tests ────────────────────────────────────────────────────────────────────

    @Test
    void unconfigured_actor_returns_empty() {
        final var validator = createValidator(Map.of(), (actorId, did) -> Optional.empty());
        final var result = validator.validate(ACTOR_ID, ACTOR_DID);
        assertTrue(result.isEmpty());
    }

    @Test
    void valid_credential_returns_VALID() throws Exception {
        final long exp = Instant.now().plusSeconds(3600).getEpochSecond();
        final String jwt = signJwt(validHeaderJson(), validPayloadJson(exp));
        final Path file = writeCredentialFile(jwt);

        final var validator = createValidator(
                Map.of(ACTOR_ID, file.toString()),
                resolverReturning(issuerDocument()));

        final var result = validator.validate(ACTOR_ID, ACTOR_DID);
        assertTrue(result.isPresent());
        assertEquals(CredentialValidationResult.VALID, result.get());
    }

    @Test
    void expired_credential_returns_EXPIRED() throws Exception {
        final long exp = Instant.now().minusSeconds(3600).getEpochSecond();
        final String jwt = signJwt(validHeaderJson(), validPayloadJson(exp));
        final Path file = writeCredentialFile(jwt);

        final var validator = createValidator(
                Map.of(ACTOR_ID, file.toString()),
                resolverReturning(issuerDocument()));

        final var result = validator.validate(ACTOR_ID, ACTOR_DID);
        assertTrue(result.isPresent());
        assertEquals(CredentialValidationResult.EXPIRED, result.get());
    }

    @Test
    void tampered_signature_returns_INVALID_SIGNATURE() throws Exception {
        final long exp = Instant.now().plusSeconds(3600).getEpochSecond();
        final String jwt = signJwt(validHeaderJson(), validPayloadJson(exp));
        // Tamper with the payload (change actorId) while keeping original signature
        final String[] parts = jwt.split("\\.");
        final String tamperedPayload = base64url(
                validPayloadJson(exp).replace(ACTOR_ID, "tampered:actor").getBytes());
        final String tamperedJwt = parts[0] + "." + tamperedPayload + "." + parts[2];
        final Path file = writeCredentialFile(tamperedJwt);

        final var validator = createValidator(
                Map.of(ACTOR_ID, file.toString()),
                resolverReturning(issuerDocument()));

        final var result = validator.validate(ACTOR_ID, ACTOR_DID);
        assertTrue(result.isPresent());
        assertEquals(CredentialValidationResult.INVALID_SIGNATURE, result.get());
    }

    @Test
    void unknown_issuer_returns_ISSUER_UNKNOWN() throws Exception {
        final long exp = Instant.now().plusSeconds(3600).getEpochSecond();
        final String jwt = signJwt(validHeaderJson(), validPayloadJson(exp));
        final Path file = writeCredentialFile(jwt);

        // Resolver returns empty for the issuer DID
        final var validator = createValidator(
                Map.of(ACTOR_ID, file.toString()),
                (actorId, did) -> Optional.empty());

        final var result = validator.validate(ACTOR_ID, ACTOR_DID);
        assertTrue(result.isPresent());
        assertEquals(CredentialValidationResult.ISSUER_UNKNOWN, result.get());
    }

    @Test
    void subject_mismatch_returns_INVALID_SIGNATURE() throws Exception {
        final long exp = Instant.now().plusSeconds(3600).getEpochSecond();
        final String jwt = signJwt(validHeaderJson(), validPayloadJson(exp));
        final Path file = writeCredentialFile(jwt);

        final var validator = createValidator(
                Map.of(ACTOR_ID, file.toString()),
                resolverReturning(issuerDocument()));

        // Pass a different DID than what's in the credential
        final var result = validator.validate(ACTOR_ID, "did:web:wrong.com:other");
        assertTrue(result.isPresent());
        assertEquals(CredentialValidationResult.INVALID_SIGNATURE, result.get());
    }

    @Test
    void missing_credential_file_returns_NOT_FOUND() {
        final var validator = createValidator(
                Map.of(ACTOR_ID, "/nonexistent/path/credential.jwt"),
                (actorId, did) -> Optional.empty());

        final var result = validator.validate(ACTOR_ID, ACTOR_DID);
        assertTrue(result.isPresent());
        assertEquals(CredentialValidationResult.NOT_FOUND, result.get());
    }

    @Test
    void cached_result_does_not_reread_file() throws Exception {
        final long exp = Instant.now().plusSeconds(3600).getEpochSecond();
        final String jwt = signJwt(validHeaderJson(), validPayloadJson(exp));
        final Path file = writeCredentialFile(jwt);

        final var validator = createValidator(
                Map.of(ACTOR_ID, file.toString()),
                resolverReturning(issuerDocument()));

        assertEquals(CredentialValidationResult.VALID, validator.validate(ACTOR_ID, ACTOR_DID).orElseThrow());

        // Delete the file — cached result should still return VALID
        Files.delete(file);
        assertEquals(CredentialValidationResult.VALID, validator.validate(ACTOR_ID, ACTOR_DID).orElseThrow());
    }

    @Test
    void expired_result_is_not_cached() throws Exception {
        final long exp = Instant.now().minusSeconds(3600).getEpochSecond();
        final String jwt = signJwt(validHeaderJson(), validPayloadJson(exp));
        final Path file = writeCredentialFile(jwt);

        final var validator = createValidator(
                Map.of(ACTOR_ID, file.toString()),
                resolverReturning(issuerDocument()));

        assertEquals(CredentialValidationResult.EXPIRED, validator.validate(ACTOR_ID, ACTOR_DID).orElseThrow());

        // Replace with a valid credential — should re-read since EXPIRED was not cached
        final long newExp = Instant.now().plusSeconds(3600).getEpochSecond();
        final String newJwt = signJwt(validHeaderJson(), validPayloadJson(newExp));
        Files.writeString(file, newJwt);

        assertEquals(CredentialValidationResult.VALID, validator.validate(ACTOR_ID, ACTOR_DID).orElseThrow());
    }
}
