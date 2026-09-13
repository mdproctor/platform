package io.casehub.platform.llm.config;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class OllamaClientExtensionsTest {

    @Test
    void parsePsResponseExtractsLoadedModels() {
        String json = """
            {"models":[{
              "name":"llama3:latest",
              "model":"llama3:latest",
              "size":4661224676,
              "size_vram":4661224676,
              "digest":"sha256:abc123",
              "details":{"quantization_level":"Q4_0"},
              "expires_at":"2026-09-13T02:00:00Z"
            }]}""";
        var client = new OllamaClient(q -> List.of());
        var models = client.parsePsResponse(json);
        assertThat(models).hasSize(1);
        assertThat(models.get(0).name()).isEqualTo("llama3:latest");
        assertThat(models.get(0).sizeVramBytes()).isEqualTo(4661224676L);
        assertThat(models.get(0).quantization()).isEqualTo("Q4_0");
        assertThat(models.get(0).expiresAt()).isNotNull();
    }

    @Test
    void parsePsResponseHandlesEmptyModels() {
        var client = new OllamaClient(q -> List.of());
        var models = client.parsePsResponse("{\"models\":[]}");
        assertThat(models).isEmpty();
    }

    @Test
    void parsePsResponseHandlesMalformedJson() {
        var client = new OllamaClient(q -> List.of());
        var models = client.parsePsResponse("not json");
        assertThat(models).isEmpty();
    }

    @Test
    void parseVersionResponse() {
        var client = new OllamaClient(q -> List.of());
        var version = client.parseVersionResponse("{\"version\":\"0.33.3\"}");
        assertThat(version).isEqualTo("0.33.3");
    }

    @Test
    void parseVersionResponseHandlesMissing() {
        var client = new OllamaClient(q -> List.of());
        var version = client.parseVersionResponse("{}");
        assertThat(version).isNull();
    }

    @Test
    void parsePullProgressLine() {
        String line = "{\"status\":\"pulling abc123\",\"digest\":\"sha256:abc123\",\"total\":4000000000,\"completed\":1500000000}";
        var client = new OllamaClient(q -> List.of());
        var progress = client.parsePullProgressLine(line);
        assertThat(progress).isNotNull();
        assertThat(progress.totalBytes()).isEqualTo(4000000000L);
        assertThat(progress.completedBytes()).isEqualTo(1500000000L);
        assertThat(progress.digest()).isEqualTo("sha256:abc123");
    }

    @Test
    void parsePullProgressLineHandlesMalformed() {
        var client = new OllamaClient(q -> List.of());
        var progress = client.parsePullProgressLine("not json");
        assertThat(progress).isNull();
    }

    @Test
    void parsePsResponseMultipleModels() {
        String json = """
            {"models":[
              {"name":"llama3:latest","size":4661224676,"size_vram":4661224676,"details":{"quantization_level":"Q4_0"}},
              {"name":"mistral:latest","size":3825819648,"size_vram":0,"details":{"quantization_level":"Q4_K_M"}}
            ]}""";
        var client = new OllamaClient(q -> List.of());
        var models = client.parsePsResponse(json);
        assertThat(models).hasSize(2);
        assertThat(models.get(1).name()).isEqualTo("mistral:latest");
        assertThat(models.get(1).sizeVramBytes()).isEqualTo(0);
    }
}
