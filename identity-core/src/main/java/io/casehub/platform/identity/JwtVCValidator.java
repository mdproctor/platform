package io.casehub.platform.identity;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.casehub.platform.api.identity.AgentCredentialValidator;
import io.casehub.platform.api.identity.CredentialValidationResult;
import io.casehub.platform.api.identity.DIDDocument;
import io.casehub.platform.api.identity.DIDResolver;
import io.casehub.platform.api.identity.VerificationMethod;
import org.jboss.logging.Logger;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.Optional;

public class JwtVCValidator extends AbstractCachingIdentityProvider<CredentialValidationResult>
        implements AgentCredentialValidator {

    private static final Logger LOG = Logger.getLogger(JwtVCValidator.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final Map<String, String> credentials;
    private final DIDResolver resolver;

    public JwtVCValidator(final Map<String, String> credentials,
                   final DIDResolver resolver,
                   final Duration cacheTtl) {
        super(cacheTtl);
        this.credentials = credentials;
        this.resolver = resolver;
    }

    @Override
    public Optional<CredentialValidationResult> validate(final String actorId, final String did) {
        if (!credentials.containsKey(actorId)) {
            return Optional.empty();
        }
        final String cacheKey = actorId + "|" + did;
        final Optional<CredentialValidationResult> cached = get(cacheKey);
        if (cached.isPresent() && cached.get() == CredentialValidationResult.EXPIRED) {
            invalidate(cacheKey);
            return Optional.of(loadAndValidate(actorId, did));
        }
        if (cached.isPresent()) {
            return cached;
        }
        final CredentialValidationResult result = loadAndValidate(actorId, did);
        if (result != CredentialValidationResult.EXPIRED) {
            put(cacheKey, Optional.of(result));
        }
        return Optional.of(result);
    }

    @Override
    protected Optional<CredentialValidationResult> loadContext(final String key) {
        return Optional.empty();
    }

    private CredentialValidationResult loadAndValidate(final String actorId, final String did) {
        final String filePath = credentials.get(actorId);
        final String jwt;
        try {
            jwt = Files.readString(Path.of(filePath)).trim();
        } catch (final Exception e) {
            LOG.debugf("JwtVCValidator: cannot read credential file for %s at %s: %s",
                    actorId, filePath, e.getMessage());
            return CredentialValidationResult.NOT_FOUND;
        }

        final String[] parts = jwt.split("\\.");
        if (parts.length != 3) {
            LOG.debugf("JwtVCValidator: malformed JWT for %s — expected 3 parts, got %d",
                    actorId, parts.length);
            return CredentialValidationResult.INVALID_SIGNATURE;
        }

        try {
            final JsonNode header = MAPPER.readTree(base64urlDecode(parts[0]));
            final JsonNode payload = MAPPER.readTree(base64urlDecode(parts[1]));

            final String alg = header.path("alg").asText("");
            final String issuerDid = payload.path("iss").asText("");
            final long exp = payload.path("exp").asLong(0);
            final String subjectId = payload.path("vc").path("credentialSubject").path("id").asText("");

            if (exp > 0 && Instant.ofEpochSecond(exp).isBefore(Instant.now())) {
                return CredentialValidationResult.EXPIRED;
            }

            if (!subjectId.equals(did)) {
                return CredentialValidationResult.INVALID_SIGNATURE;
            }

            final Optional<DIDDocument> issuerDoc = resolver.resolve(null, issuerDid);
            if (issuerDoc.isEmpty()) {
                return CredentialValidationResult.ISSUER_UNKNOWN;
            }

            final Optional<VerificationMethod> vm = findMatchingKey(issuerDoc.get(), alg);
            if (vm.isEmpty()) {
                return CredentialValidationResult.ISSUER_UNKNOWN;
            }

            final String signingInput = parts[0] + "." + parts[1];
            final byte[] signatureBytes = Base64.getUrlDecoder().decode(parts[2]);

            if (!verifySignature(alg, vm.get().publicKeyBytes(), signingInput.getBytes(), signatureBytes)) {
                return CredentialValidationResult.INVALID_SIGNATURE;
            }

            return CredentialValidationResult.VALID;
        } catch (final Exception e) {
            LOG.debugf("JwtVCValidator: validation failed for %s: %s", actorId, e.getMessage());
            return CredentialValidationResult.INVALID_SIGNATURE;
        }
    }

    private static Optional<VerificationMethod> findMatchingKey(final DIDDocument doc, final String alg) {
        final String expectedType = switch (alg) {
            case "EdDSA" -> "Ed25519VerificationKey2020";
            case "ES256" -> "EcdsaSecp256r1VerificationKey2019";
            default -> "";
        };
        if (expectedType.isEmpty()) return Optional.empty();
        return doc.verificationMethods().stream()
                .filter(vm -> expectedType.equals(vm.type()))
                .findFirst();
    }

    private static boolean verifySignature(final String alg, final byte[] spkiPublicKey,
                                           final byte[] data, final byte[] signatureBytes) throws Exception {
        final String keyAlg = switch (alg) {
            case "EdDSA" -> "Ed25519";
            case "ES256" -> "EC";
            default -> null;
        };
        if (keyAlg == null) return false;

        final String sigAlg = switch (alg) {
            case "EdDSA" -> "Ed25519";
            case "ES256" -> "SHA256withECDSA";
            default -> throw new IllegalArgumentException(alg);
        };

        final PublicKey publicKey = KeyFactory.getInstance(keyAlg)
                .generatePublic(new X509EncodedKeySpec(spkiPublicKey));
        final Signature sig = Signature.getInstance(sigAlg);
        sig.initVerify(publicKey);
        sig.update(data);
        return sig.verify(signatureBytes);
    }

    private static byte[] base64urlDecode(final String encoded) {
        return Base64.getUrlDecoder().decode(encoded);
    }
}
