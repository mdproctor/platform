package io.casehub.yaml.step.catalog;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.casehub.yaml.jackson.YamlMappers;
import io.casehub.platform.api.process.DefaultProcessExecutor;
import io.casehub.platform.api.process.ProcessExecutor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ScriptSourceTest {

    private Path testDir;
    private final ObjectMapper yamlMapper = YamlMappers.create();
    private final ProcessExecutor executor = new DefaultProcessExecutor();

    @BeforeEach
    void setUp() {
        testDir = Path.of(getClass().getClassLoader().getResource("scripts/sentiment.py").getPath()).getParent();
    }

    @Test
    void discoversScriptsWithCompanionSchema() {
        var source = new ScriptSource(List.of(testDir), yamlMapper, executor);
        var registry = new CompositePluginRegistry();
        source.populate(registry);

        assertThat(registry.resolve("sentiment")).isPresent();
        var def = registry.resolve("sentiment").get();
        assertThat(def.action()).isNotNull();
    }

    @Test
    void detectsNodeRuntimeFromExtension() {
        var source = new ScriptSource(List.of(testDir), yamlMapper, executor);
        var registry = new CompositePluginRegistry();
        source.populate(registry);

        assertThat(registry.resolve("transform")).isPresent();
    }

    @Test
    void skipsScriptsWithoutSchema() {
        var source = new ScriptSource(List.of(testDir), yamlMapper, executor);
        var registry = new CompositePluginRegistry();
        source.populate(registry);

        assertThat(registry.resolve("orphan")).isEmpty();
    }

    @Test
    void parsesSchemaInputsAndOutputs() {
        var source = new ScriptSource(List.of(testDir), yamlMapper, executor);
        var registry = new CompositePluginRegistry();
        source.populate(registry);

        var def = registry.resolve("sentiment").get();
        assertThat(def.inputs()).containsKey("text");
        assertThat(def.inputs().get("text").required()).isTrue();
        assertThat(def.outputs()).containsKey("score");
        assertThat(def.description()).isEqualTo("Analyze text sentiment");
    }

    @Test
    void emptyPathsProducesNoEntries() {
        var source = new ScriptSource(List.of(), yamlMapper, executor);
        var registry = new CompositePluginRegistry();
        source.populate(registry);
        assertThat(registry.availableActions()).isEmpty();
    }

    @Test
    void nonExistentPathIsSkipped() {
        var source = new ScriptSource(List.of(Path.of("/nonexistent/path")), yamlMapper, executor);
        var registry = new CompositePluginRegistry();
        source.populate(registry);
        assertThat(registry.availableActions()).isEmpty();
    }
}
