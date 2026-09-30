package io.casehub.platform.agent.config;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import io.casehub.platform.api.identity.DIDResolver;
import io.casehub.platform.api.model.ModelDescriptor;
import io.casehub.platform.api.signing.SignatureVerifier;
import io.casehub.platform.api.signing.VerificationOutcome;
import org.jboss.logging.Logger;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class ManifestLoader {

    private static final Logger LOG = Logger.getLogger(ManifestLoader.class);
    private static final int MAX_DEPTH = 3;
    private static final int MAX_SOURCES = 20;
    private static final int MAX_BODY_SIZE = 1_048_576;
    private static final Duration FETCH_TIMEOUT = Duration.ofSeconds(10);
    private static final Set<String> ALLOWED_CONTENT_TYPES = Set.of(
            "application/yaml", "application/x-yaml", "text/yaml", "application/json");

    private final ObjectMapper mapper;
    private final ManifestCredentialResolver credentialResolver;
    private final DIDResolver didResolver;
    private final ManifestSecurityConfig securityConfig;
    private final java.net.http.HttpClient httpClient = java.net.http.HttpClient.newBuilder()
            .connectTimeout(FETCH_TIMEOUT)
            .followRedirects(java.net.http.HttpClient.Redirect.NEVER)
            .build();

    public ManifestLoader(ManifestCredentialResolver credentialResolver,
                          DIDResolver didResolver,
                          ManifestSecurityConfig securityConfig) {
        this.mapper = new ObjectMapper(new YAMLFactory())
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        this.credentialResolver = credentialResolver;
        this.didResolver = didResolver;
        this.securityConfig = securityConfig != null ? securityConfig : new ManifestSecurityConfig(false);
    }

    public ManifestLoader() {
        this(null, null, new ManifestSecurityConfig(false));
    }

    public Manifest loadResource(URI uri) {
        if ("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme())) {
            throw new IllegalArgumentException(
                    "HTTP/HTTPS URIs must be fetched via SourceDeclaration — use sources: in the manifest");
        }
        try (InputStream is = uri.toURL().openStream()) {
            return mapper.readValue(is, Manifest.class);
        } catch (IOException e) {
            LOG.warnf("Failed to load manifest from %s: %s", uri, e.getMessage());
            return emptyManifest();
        }
    }

    public Manifest loadFile(Path path) {
        if (!Files.exists(path)) return null;
        try {
            return mapper.readValue(path.toFile(), Manifest.class);
        } catch (IOException e) {
            LOG.warnf("Failed to parse manifest %s: %s", path, e.getMessage());
            return emptyManifest();
        }
    }

    public Manifest load(Path projectDir, String profile) {
        var entries = new ArrayList<PrioritizedManifest>();

        addClasspathResource(entries, "models/seed-catalog.yaml", 0);
        addFileIfExists(entries, Path.of("/etc/casehub/agent-config.yaml"), 10);
        addFileIfExists(entries, Path.of(System.getProperty("user.home"), ".casehub", "agent-config.yaml"), 20);
        if (profile != null) {
            addFileIfExists(entries, Path.of(System.getProperty("user.home"), ".casehub", "agent-config-" + profile + ".yaml"), 25);
        }
        if (projectDir != null) {
            addFileIfExists(entries, projectDir.resolve("agent-config.yaml"), 30);
            if (profile != null) {
                addFileIfExists(entries, projectDir.resolve("agent-config-" + profile + ".yaml"), 35);
            }
        }

        var loadedUris = new LinkedHashSet<String>();
        for (var entry : new ArrayList<>(entries)) {
            followSources(entry.manifest, entries, loadedUris, 1);
        }

        return merge(entries);
    }

    private void followSources(Manifest manifest, List<PrioritizedManifest> entries,
                               Set<String> loadedUris, int depth) {
        if (depth > MAX_DEPTH) return;
        for (var source : manifest.sources()) {
            if (loadedUris.size() >= MAX_SOURCES) {
                LOG.warnf("Max source limit (%d) reached — skipping %s", MAX_SOURCES, source.uri());
                return;
            }
            if (!loadedUris.add(source.uri())) {
                LOG.warnf("Cycle detected — already loaded %s", source.uri());
                continue;
            }
            var fetched = fetchRemote(source);
            if (fetched != null) {
                entries.add(new PrioritizedManifest(fetched, source.priority()));
                followSources(fetched, entries, loadedUris, depth + 1);
            }
        }
    }

    Manifest fetchRemote(SourceDeclaration source) {
        var uri = URI.create(source.uri());

        // Step 1: HTTPS enforcement
        if ("http".equalsIgnoreCase(uri.getScheme())) {
            if (!isPrivateAddress(uri)) {
                if (!securityConfig.allowInsecure()) {
                    LOG.errorf("Rejected insecure HTTP source: %s (set casehub.agent.manifest.allow-insecure=true to override)", source.uri());
                    return null;
                }
                LOG.warnf("Insecure HTTP source allowed by configuration: %s", source.uri());
            }
        }

        // Step 2: Validation
        var errors = SourceValidator.validate(source);
        if (!errors.isEmpty()) {
            errors.forEach(e -> LOG.errorf("Source validation failed: %s", e));
            return null;
        }

        // Step 3: Build request
        var requestBuilder = HttpRequest.newBuilder().uri(uri).timeout(FETCH_TIMEOUT).GET();
        if (source.auth() != null && credentialResolver == null) {
            LOG.errorf("Auth declared for source %s but no credential resolver configured (ref: %s)",
                    source.uri(), source.auth().credential());
            return null;
        }
        if (source.auth() != null) {
            try {
                var ref = CredentialRef.parse(source.auth().credential());
                var value = credentialResolver.resolve(ref);
                switch (source.auth().type()) {
                    case "bearer" -> requestBuilder.header("Authorization", "Bearer " + value);
                    case "basic" -> requestBuilder.header("Authorization",
                            "Basic " + Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8)));
                    case "header" -> requestBuilder.header(source.auth().headerName(), value);
                }
            } catch (Exception e) {
                LOG.errorf("Credential resolution failed for source %s (ref: %s): %s",
                        source.uri(), source.auth().credential(), e.getMessage());
                return null;
            }
        }

        try {
            // Step 4: Send
            var response = httpClient.send(requestBuilder.build(), HttpResponse.BodyHandlers.ofInputStream());

            // Step 5: Redirect detection
            if (response.statusCode() >= 300 && response.statusCode() < 400) {
                var location = response.headers().firstValue("Location").orElse("(none)");
                LOG.warnf("HTTP %d redirect from %s to %s — update source URI to the final target",
                        response.statusCode(), source.uri(), location);
                return null;
            }

            // Step 6: HTTP error
            if (response.statusCode() >= 400) {
                LOG.warnf("HTTP %d from %s", response.statusCode(), source.uri());
                return null;
            }

            // Step 7: Content-Type validation
            var contentType = response.headers().firstValue("Content-Type").orElse(null);
            if (contentType != null) {
                var mediaType = contentType.contains(";")
                        ? contentType.substring(0, contentType.indexOf(';')).trim()
                        : contentType.trim();
                if (!ALLOWED_CONTENT_TYPES.contains(mediaType.toLowerCase())) {
                    LOG.warnf("Unexpected Content-Type '%s' from %s (expected: %s)",
                            contentType, source.uri(), ALLOWED_CONTENT_TYPES);
                    return null;
                }
            }

            // Step 8: Size-limited read
            try (var body = response.body()) {
                var bodyBytes = readBounded(body, MAX_BODY_SIZE, source.uri());
                if (bodyBytes == null) return null;

                // Step 9: Digest verification
                if (source.integrity() != null
                        && !verifyDigest(bodyBytes, source.integrity().digest(), source.uri())) {
                    return null;
                }

                // Step 10: Signature verification
                if (source.integrity() != null
                        && !verifySignature(bodyBytes, source.integrity().signature(),
                                source.integrity().signer(), didResolver, source.uri())) {
                    return null;
                }

                // Step 11: Parse
                return mapper.readValue(bodyBytes, Manifest.class);
            }
        } catch (Exception e) {
            LOG.warnf("Failed to fetch remote source %s: %s", source.uri(), e.getMessage());
            return null;
        }
    }

    static boolean verifyDigest(byte[] bodyBytes, String declaredDigest, String uri) {
        if (declaredDigest == null) return true;
        try {
            var hex = declaredDigest.substring("sha256:".length());
            var actual = HexFormat.of().formatHex(
                    java.security.MessageDigest.getInstance("SHA-256").digest(bodyBytes));
            if (!actual.equalsIgnoreCase(hex)) {
                LOG.errorf("Digest mismatch for source %s: expected sha256:%s, actual sha256:%s",
                        uri, hex, actual);
                return false;
            }
            return true;
        } catch (Exception e) {
            LOG.errorf("Digest verification error for source %s: %s", uri, e.getMessage());
            return false;
        }
    }

    static boolean verifySignature(byte[] bodyBytes, String signatureB64, String signerDid,
                                    DIDResolver didResolver, String uri) {
        if (signatureB64 == null) return true;

        if (didResolver == null) {
            LOG.errorf("Signature declared for source %s but no DID resolver configured (signer: %s)",
                    uri, signerDid);
            return false;
        }

        var didDoc = didResolver.resolve(null, signerDid);
        if (didDoc.isEmpty()) {
            LOG.errorf("DID unresolvable: %s for source %s", signerDid, uri);
            return false;
        }

        var verificationMethods = didDoc.get().verificationMethods();
        if (verificationMethods.isEmpty()) {
            LOG.errorf("No verification methods in DID document for signer: %s (source %s)",
                    signerDid, uri);
            return false;
        }

        var sigBytes = Base64.getUrlDecoder().decode(signatureB64);
        VerificationOutcome lastOutcome = null;

        for (var vm : verificationMethods) {
            var outcome = SignatureVerifier.verify(
                    bodyBytes, sigBytes, vm.publicKeyBytes());
            if (outcome == VerificationOutcome.VALID) {
                return true;
            }
            lastOutcome = outcome;
        }

        LOG.errorf("Signature verification failed for source %s (signer: %s, outcome: %s)",
                uri, signerDid, lastOutcome);
        return false;
    }

    private static boolean isPrivateAddress(URI uri) {
        try {
            var host = uri.getHost();
            if ("localhost".equalsIgnoreCase(host)) return true;
            var addr = InetAddress.getByName(host);
            return addr.isLoopbackAddress() || addr.isLinkLocalAddress() || addr.isSiteLocalAddress();
        } catch (Exception e) {
            return false;
        }
    }

    private byte[] readBounded(InputStream is, int limit, String uri) throws IOException {
        var buffer = new ByteArrayOutputStream();
        var chunk = new byte[8192];
        int total = 0;
        int read;
        while ((read = is.read(chunk)) != -1) {
            total += read;
            if (total > limit) {
                LOG.warnf("Response body exceeds %d bytes from %s — skipping", limit, uri);
                return null;
            }
            buffer.write(chunk, 0, read);
        }
        return buffer.toByteArray();
    }

    private void addClasspathResource(List<PrioritizedManifest> entries, String resource, int priority) {
        var is = Thread.currentThread().getContextClassLoader().getResourceAsStream(resource);
        if (is == null) return;
        try (is) {
            var manifest = mapper.readValue(is, Manifest.class);
            entries.add(new PrioritizedManifest(manifest, priority));
            LOG.debugf("Loaded classpath resource: %s (priority %d)", resource, priority);
        } catch (IOException e) {
            LOG.warnf("Failed to parse classpath resource %s: %s", resource, e.getMessage());
        }
    }

    private void addFileIfExists(List<PrioritizedManifest> entries, Path path, int priority) {
        var manifest = loadFile(path);
        if (manifest != null) {
            entries.add(new PrioritizedManifest(manifest, priority));
            LOG.infof("Loaded manifest: %s (priority %d)", path, priority);
        }
    }

    Manifest merge(List<PrioritizedManifest> entries) {
        entries.sort(Comparator.comparingInt(PrioritizedManifest::priority));

        var models = new LinkedHashMap<String, ModelDescriptor>();
        var providers = new LinkedHashMap<String, ProviderDeclaration>();
        var aliases = new LinkedHashMap<String, AliasDeclaration>();
        var localModels = new LinkedHashMap<String, LocalModelDeclaration>();
        var sources = new LinkedHashMap<String, SourceDeclaration>();
        ManifestDefaults defaults = null;

        for (var entry : entries) {
            var m = entry.manifest;
            for (var model : m.models()) models.put(model.id(), model);
            for (var provider : m.providers()) providers.put(provider.vendor(), provider);
            for (var alias : m.aliases().entrySet()) aliases.put(alias.getKey(), alias.getValue());
            for (var local : m.localModels()) localModels.put(local.id(), local);
            for (var source : m.sources()) sources.put(source.uri(), source);
            if (m.defaults() != null) defaults = m.defaults();
        }

        return new Manifest(
                new ArrayList<>(models.values()),
                new ArrayList<>(providers.values()),
                new ArrayList<>(sources.values()),
                aliases,
                new ArrayList<>(localModels.values()),
                defaults
        );
    }

    private static Manifest emptyManifest() {
        return new Manifest(List.of(), List.of(), List.of(), Map.of(), List.of(), null);
    }

    record PrioritizedManifest(Manifest manifest, int priority) {}
}
