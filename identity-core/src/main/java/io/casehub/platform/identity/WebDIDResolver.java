package io.casehub.platform.identity;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.casehub.platform.api.identity.DIDDocument;
import io.casehub.platform.api.identity.DIDResolver;
import io.casehub.platform.api.identity.VerificationMethod;
import org.jboss.logging.Logger;

import java.net.URI;
import java.net.URLDecoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

public class WebDIDResolver implements DIDResolver {

    private static final Logger LOG = Logger.getLogger(WebDIDResolver.class);

    private static final Pattern BLOCKED_HOSTS = Pattern.compile(
            "^(localhost|127\\..*|::1|0\\.0\\.0\\.0|10\\..*|" +
            "172\\.(1[6-9]|2[0-9]|3[01])\\..*|192\\.168\\..*)$");

    private static final String DID_WEB_PREFIX = "did:web:";
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final int timeoutMs;
    private final int maxResponseBytes;
    private final HttpClient httpClient;

    public WebDIDResolver(final int timeoutMs, final int maxResponseBytes) {
        this.timeoutMs = timeoutMs;
        this.maxResponseBytes = maxResponseBytes;
        this.httpClient = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(Duration.ofMillis(timeoutMs))
                .build();
    }

    @Override
    public Optional<DIDDocument> resolve(final String actorId, final String did) {
        if (did == null || !did.startsWith(DID_WEB_PREFIX)) {
            return Optional.empty();
        }
        try {
            final String url = toUrl(did);
            final URI uri = URI.create(url);
            if (!isAllowedHost(uri.getHost())) {
                LOG.warnf("WebDIDResolver: blocked SSRF attempt for host %s in DID %s", uri.getHost(), did);
                return Optional.empty();
            }
            final HttpRequest request = HttpRequest.newBuilder(uri)
                    .GET()
                    .timeout(Duration.ofMillis(timeoutMs))
                    .build();
            final HttpResponse<String> response = httpClient.send(
                    request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                LOG.debugf("WebDIDResolver: HTTP %d for %s", response.statusCode(), did);
                return Optional.empty();
            }
            final String body = response.body();
            if (body.length() > maxResponseBytes) {
                LOG.warnf("WebDIDResolver: response for %s exceeds max size (%d bytes)", did, body.length());
                return Optional.empty();
            }
            return Optional.of(parseDocument(body));
        } catch (final Exception e) {
            LOG.debugf("WebDIDResolver: failed to resolve %s: %s", did, e.getMessage());
            return Optional.empty();
        }
    }

    protected boolean isAllowedHost(final String host) {
        return host != null && !BLOCKED_HOSTS.matcher(host).matches();
    }

    protected String scheme() {
        return "https";
    }

    private String toUrl(final String did) {
        final String hostAndPath = did.substring(DID_WEB_PREFIX.length());
        final String[] parts = hostAndPath.split(":", -1);
        final String authority = URLDecoder.decode(parts[0], StandardCharsets.UTF_8);
        final String path;
        if (parts.length > 1) {
            final String[] pathSegments = Arrays.copyOfRange(parts, 1, parts.length);
            path = "/" + String.join("/", pathSegments) + "/did.json";
        } else {
            path = "/.well-known/did.json";
        }
        return scheme() + "://" + authority + path;
    }

    private DIDDocument parseDocument(final String json) throws Exception {
        final JsonNode                 root    = OBJECT_MAPPER.readTree(json);
        final String                   id      = root.path("id").asText("");
        final List<VerificationMethod> vms     = new ArrayList<>();
        final JsonNode                 vmArray = root.path("verificationMethod");
        if (vmArray.isArray()) {
            for (final JsonNode vmNode : vmArray) {
                final String vmId      = vmNode.path("id").asText("");
                final String type      = vmNode.path("type").asText("");
                final String multibase = vmNode.path("publicKeyMultibase").asText("");
                byte[]       keyBytes  = new byte[0];
                if (!multibase.isEmpty()) {
                    try {
                        keyBytes = Multibase.decode(multibase);
                    } catch (final IllegalArgumentException ex) {
                        LOG.debugf("WebDIDResolver: unsupported multibase encoding in publicKeyMultibase: %s", ex.getMessage());
                    }
                }
                vms.add(new VerificationMethod(vmId, type, keyBytes));
            }
        }
        final List<String> alsoKnownAs = new ArrayList<>();
        final JsonNode     akaArray    = root.path("alsoKnownAs");
        if (akaArray.isArray()) {
            for (final JsonNode akaNode : akaArray) {
                alsoKnownAs.add(akaNode.asText());
            }
        }
        return new DIDDocument(id, vms, alsoKnownAs);
    }
}
