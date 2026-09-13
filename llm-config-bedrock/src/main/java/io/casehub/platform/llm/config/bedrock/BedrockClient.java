package io.casehub.platform.llm.config.bedrock;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.casehub.platform.api.model.ModelCapabilities;
import io.casehub.platform.api.model.ModelDescriptor;
import io.casehub.platform.api.model.ModelLocality;
import io.casehub.platform.api.model.ModelQuery;
import io.casehub.platform.api.model.ModelRegistry;
import io.casehub.platform.api.model.ModelTier;
import io.casehub.platform.llm.config.ValidationResult;
import io.casehub.platform.llm.config.VendorClient;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import software.amazon.awssdk.auth.credentials.AwsCredentials;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

@ApplicationScoped
public class BedrockClient implements VendorClient {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final DateTimeFormatter DATETIME_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'");
    private final HttpClient httpClient = HttpClient.newHttpClient();
    private final Function<ModelQuery, List<ModelDescriptor>> seedLookup;

    @Inject
    BedrockClient(ModelRegistry registry) {
        this.seedLookup = registry::query;
    }

    BedrockClient(Function<ModelQuery, List<ModelDescriptor>> seedLookup) {
        this.seedLookup = seedLookup;
    }

    @Override public String vendorKey() { return "bedrock"; }
    @Override public String backendKey() { return "claude"; }
    @Override public String displayName() { return "Amazon Bedrock (Anthropic)"; }
    @Override public String authMethod() { return "aws-sigv4"; }
    @Override public List<String> requiredFields() { return List.of("region"); }

    @Override
    public ValidationResult listModels(Map<String, String> credentials) {
        String region = credentials.getOrDefault("region", "us-east-1");
        try {
            AwsCredentials awsCreds = DefaultCredentialsProvider.create().resolveCredentials();
            String host = "bedrock." + region + ".amazonaws.com";
            String url = "https://" + host + "/foundation-models";

            Instant now = Instant.now();
            String dateStamp = DATE_FORMAT.format(now.atZone(ZoneOffset.UTC));
            String amzDate = DATETIME_FORMAT.format(now.atZone(ZoneOffset.UTC));

            String payloadHash = sha256Hex("");
            String canonicalRequest = buildCanonicalRequest(host, amzDate, payloadHash);
            String stringToSign = buildStringToSign(amzDate, dateStamp, region, canonicalRequest);
            String signature = calculateSignature(awsCreds.secretAccessKey(), dateStamp, region, stringToSign);
            String authHeader = buildAuthHeader(awsCreds.accessKeyId(), dateStamp, region, signature);

            var request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Host", host)
                .header("X-Amz-Date", amzDate)
                .header("X-Amz-Content-Sha256", payloadHash)
                .header("Authorization", authHeader)
                .GET()
                .build();
            var response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 401 || response.statusCode() == 403) {
                return ValidationResult.failure("AWS authentication failed — check credentials");
            }
            if (response.statusCode() != 200) {
                return ValidationResult.failure("Bedrock returned status " + response.statusCode());
            }
            return ValidationResult.success(parseModelsResponse(response.body()));
        } catch (Exception e) {
            return ValidationResult.failure("Failed to connect to Bedrock: " + e.getMessage());
        }
    }

    List<ModelDescriptor> parseModelsResponse(String json) {
        try {
            var seedModels = seedLookup.apply(
                ModelQuery.builder().vendor("anthropic").build());
            Map<String, ModelDescriptor> seedIndex = new HashMap<>();
            for (var m : seedModels) {
                seedIndex.put(m.apiModelId(), m);
            }

            JsonNode root = MAPPER.readTree(json);
            JsonNode models = root.path("modelSummaries");
            if (!models.isArray()) return List.of();

            List<ModelDescriptor> result = new ArrayList<>();
            for (JsonNode node : models) {
                String providerName = node.path("providerName").asText("");
                if (!"Anthropic".equalsIgnoreCase(providerName)) {
                    continue;
                }
                String modelId = node.get("modelId").asText();
                String name = node.has("modelName") ? node.get("modelName").asText() : modelId;

                ModelDescriptor seed = seedIndex.get(modelId);
                if (seed != null) {
                    result.add(new ModelDescriptor(
                        modelId, modelId, seed.backendKey(), null, seed.vendor(), seed.family(), name,
                        seed.tier(), seed.capabilities(), seed.contextWindow(), seed.maxOutput(),
                        seed.locality(), seed.costTier(), seed.authMethod(), seed.properties()));
                } else {
                    result.add(new ModelDescriptor(
                        modelId, modelId, "claude", null, "anthropic", "claude", name,
                        ModelTier.STANDARD, Set.of(ModelCapabilities.TEXT), 0, 0,
                        ModelLocality.CLOUD, null, "aws-sigv4", Map.of()));
                }
            }
            return result;
        } catch (Exception e) {
            return List.of();
        }
    }

    private String buildCanonicalRequest(String host, String amzDate, String payloadHash) {
        return "GET\n/foundation-models\n\nhost:" + host + "\nx-amz-content-sha256:" + payloadHash
            + "\nx-amz-date:" + amzDate + "\n\nhost;x-amz-content-sha256;x-amz-date\n" + payloadHash;
    }

    private String buildStringToSign(String amzDate, String dateStamp, String region, String canonicalRequest) {
        String scope = dateStamp + "/" + region + "/bedrock/aws4_request";
        return "AWS4-HMAC-SHA256\n" + amzDate + "\n" + scope + "\n" + sha256Hex(canonicalRequest);
    }

    private String calculateSignature(String secretKey, String dateStamp, String region, String stringToSign) {
        try {
            byte[] kDate = hmacSha256(("AWS4" + secretKey).getBytes(StandardCharsets.UTF_8), dateStamp);
            byte[] kRegion = hmacSha256(kDate, region);
            byte[] kService = hmacSha256(kRegion, "bedrock");
            byte[] kSigning = hmacSha256(kService, "aws4_request");
            return hexEncode(hmacSha256(kSigning, stringToSign));
        } catch (Exception e) {
            throw new RuntimeException("SigV4 signing failed", e);
        }
    }

    private String buildAuthHeader(String accessKey, String dateStamp, String region, String signature) {
        String scope = dateStamp + "/" + region + "/bedrock/aws4_request";
        return "AWS4-HMAC-SHA256 Credential=" + accessKey + "/" + scope
            + ", SignedHeaders=host;x-amz-content-sha256;x-amz-date, Signature=" + signature;
    }

    private static byte[] hmacSha256(byte[] key, String data) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(key, "HmacSHA256"));
        return mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
    }

    private static String sha256Hex(String data) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(data.getBytes(StandardCharsets.UTF_8));
            return hexEncode(hash);
        } catch (Exception e) {
            throw new RuntimeException("SHA-256 failed", e);
        }
    }

    private static String hexEncode(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }
}
