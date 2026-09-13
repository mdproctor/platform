package io.casehub.platform.llm.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.casehub.platform.api.model.CostTier;
import io.casehub.platform.api.model.ModelCapabilities;
import io.casehub.platform.api.model.ModelDescriptor;
import io.casehub.platform.api.model.ModelLocality;
import io.casehub.platform.api.model.ModelQuery;
import io.casehub.platform.api.model.ModelRegistry;
import io.casehub.platform.api.model.ModelTier;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
@ApplicationScoped
public class OllamaClient implements VendorClient {

    private static final String DEFAULT_HOST = "http://localhost:11434";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final Function<ModelQuery, List<ModelDescriptor>> seedLookup;
    private final HttpClient httpClient = HttpClient.newHttpClient();

    @Inject
    OllamaClient(ModelRegistry registry) {
        this.seedLookup = registry::query;
    }

    OllamaClient(Function<ModelQuery, List<ModelDescriptor>> seedLookup) {
        this.seedLookup = seedLookup;
    }

    @Override public String vendorKey() { return "ollama"; }
    @Override public String backendKey() { return "ollama"; }
    @Override public String displayName() { return "Ollama"; }
    @Override public String authMethod() { return "local"; }
    @Override public List<String> requiredFields() { return List.of(); }

    @Override
    public ValidationResult listModels(Map<String, String> credentials) {
        String host = credentials.getOrDefault("host", DEFAULT_HOST);
        try {
            var request = HttpRequest.newBuilder()
                .uri(URI.create(host + "/api/tags"))
                .GET()
                .build();
            var response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                return ValidationResult.failure("Ollama returned status " + response.statusCode());
            }
            return ValidationResult.success(parseModelsResponse(response.body()));
        } catch (Exception e) {
            return ValidationResult.failure("Failed to connect to Ollama at " + host + ": " + e.getMessage());
        }
    }

    List<ModelDescriptor> parseModelsResponse(String json) {
        try {
            var seedModels = seedLookup.apply(
                ModelQuery.builder().locality(ModelLocality.LOCAL).build());
            Map<String, ModelDescriptor> seedIndex = new java.util.HashMap<>();
            for (var m : seedModels) {
                seedIndex.put(m.apiModelId(), m);
            }

            JsonNode root = MAPPER.readTree(json);
            JsonNode models = root.path("models");
            if (!models.isArray()) return List.of();

            List<ModelDescriptor> result = new ArrayList<>();
            for (JsonNode node : models) {
                String name = node.get("name").asText();
                String id = name.contains(":") ? name.substring(0, name.indexOf(':')) : name;

                ModelDescriptor seed = seedIndex.get(id);
                if (seed != null) {
                    result.add(new ModelDescriptor(
                        id, id, seed.backendKey(), null, seed.vendor(), seed.family(), seed.displayName(),
                        seed.tier(), seed.capabilities(), seed.contextWindow(), seed.maxOutput(),
                        seed.locality(), seed.costTier(), seed.authMethod(), seed.properties()));
                } else {
                    result.add(new ModelDescriptor(
                        id, id, "ollama", null, "local", "local", name,
                        ModelTier.STANDARD, Set.of(ModelCapabilities.TEXT), 0, 0,
                        ModelLocality.LOCAL, CostTier.FREE, "local", Map.of()));
                }
            }
            return result;
        } catch (Exception e) {
            return List.of();
        }
    }

    List<OllamaSourceStatus.LoadedModel> parsePsResponse(String json) {
        try {
            JsonNode root = MAPPER.readTree(json);
            JsonNode models = root.path("models");
            if (!models.isArray()) return List.of();

            List<OllamaSourceStatus.LoadedModel> result = new ArrayList<>();
            for (JsonNode node : models) {
                String name = node.path("name").asText();
                long size = node.path("size").asLong(0);
                long sizeVram = node.path("size_vram").asLong(0);
                String quant = node.path("details").path("quantization_level").asText(null);
                Instant expiresAt = node.has("expires_at")
                    ? Instant.parse(node.get("expires_at").asText())
                    : null;
                result.add(new OllamaSourceStatus.LoadedModel(name, size, sizeVram, quant, expiresAt));
            }
            return result;
        } catch (Exception e) {
            return List.of();
        }
    }

    String parseVersionResponse(String json) {
        try {
            JsonNode root = MAPPER.readTree(json);
            return root.has("version") ? root.get("version").asText() : null;
        } catch (Exception e) {
            return null;
        }
    }

    PullProgressData parsePullProgressLine(String line) {
        try {
            JsonNode node = MAPPER.readTree(line);
            String status = node.path("status").asText("");
            String digest = node.path("digest").asText(null);
            long total = node.path("total").asLong(0);
            long completed = node.path("completed").asLong(0);
            return new PullProgressData(status, digest, total, completed);
        } catch (Exception e) {
            return null;
        }
    }

    record PullProgressData(String status, String digest, long totalBytes, long completedBytes) {}

    boolean isReachable() {
        return isReachable(DEFAULT_HOST);
    }

    boolean isReachable(String host) {
        try {
            var request = HttpRequest.newBuilder()
                .uri(URI.create(host))
                .method("HEAD", HttpRequest.BodyPublishers.noBody())
                .timeout(java.time.Duration.ofSeconds(5))
                .build();
            var response = httpClient.send(request, HttpResponse.BodyHandlers.discarding());
            return response.statusCode() == 200;
        } catch (Exception e) {
            return false;
        }
    }

    List<OllamaSourceStatus.LoadedModel> ps() {
        return ps(DEFAULT_HOST);
    }

    List<OllamaSourceStatus.LoadedModel> ps(String host) {
        try {
            var request = HttpRequest.newBuilder()
                .uri(URI.create(host + "/api/ps"))
                .GET()
                .timeout(java.time.Duration.ofSeconds(10))
                .build();
            var response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) return List.of();
            return parsePsResponse(response.body());
        } catch (Exception e) {
            return List.of();
        }
    }

    String version() {
        return version(DEFAULT_HOST);
    }

    String version(String host) {
        try {
            var request = HttpRequest.newBuilder()
                .uri(URI.create(host + "/api/version"))
                .GET()
                .timeout(java.time.Duration.ofSeconds(5))
                .build();
            var response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) return null;
            return parseVersionResponse(response.body());
        } catch (Exception e) {
            return null;
        }
    }

    boolean delete(String host, String modelName) {
        try {
            String body = MAPPER.writeValueAsString(Map.of("name", modelName));
            var request = HttpRequest.newBuilder()
                .uri(URI.create(host + "/api/delete"))
                .method("DELETE", HttpRequest.BodyPublishers.ofString(body))
                .header("Content-Type", "application/json")
                .timeout(java.time.Duration.ofSeconds(30))
                .build();
            var response = httpClient.send(request, HttpResponse.BodyHandlers.discarding());
            return response.statusCode() == 200;
        } catch (Exception e) {
            return false;
        }
    }
}
