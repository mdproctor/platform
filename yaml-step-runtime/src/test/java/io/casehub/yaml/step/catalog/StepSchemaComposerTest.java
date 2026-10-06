package io.casehub.yaml.step.catalog;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.casehub.yaml.plugin.api.Definition;
import io.casehub.yaml.plugin.api.Parameter;
import io.casehub.yaml.plugin.api.ParameterType;
import io.casehub.yaml.plugin.api.PluginRegistry;
import io.casehub.yaml.plugin.api.Portability;
import io.casehub.yaml.plugin.api.Result;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class StepSchemaComposerTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void composesSchemaWithOneOfPerPlugin() {
        PluginRegistry registry = registryWith(
                "process", Map.of(
                        "command", new Parameter(ParameterType.STRING, true, null, null, null, null)),
                "assert", Map.of(
                        "expression", new Parameter(ParameterType.STRING, true, null, null, null, null)));

        ObjectNode schema = StepSchemaComposer.compose(registry, mapper);

        assertThat(schema.has("oneOf")).isTrue();
        assertThat(schema.get("oneOf").size()).isGreaterThanOrEqualTo(3);
    }

    @Test
    void includesDecoratorProperties() {
        PluginRegistry registry = registryWith("process", Map.of());

        ObjectNode schema = StepSchemaComposer.compose(registry, mapper);

        assertThat(schema.has("properties")).isTrue();
        var props = schema.get("properties");
        assertThat(props.has("step")).isTrue();
        assertThat(props.has("if")).isTrue();
        assertThat(props.has("timeout")).isTrue();
    }

    @Test
    void includesInvokeEscapeHatch() {
        PluginRegistry registry = registryWith("process", Map.of());

        ObjectNode schema = StepSchemaComposer.compose(registry, mapper);

        boolean hasInvoke = false;
        for (var variant : schema.get("oneOf")) {
            if (variant.has("properties") && variant.get("properties").has("invoke")) {
                hasInvoke = true;
                break;
            }
        }
        assertThat(hasInvoke).isTrue();
    }

    @Test
    void emptySchemaForEmptyRegistry() {
        PluginRegistry registry = new CompositePluginRegistry();

        ObjectNode schema = StepSchemaComposer.compose(registry, mapper);

        assertThat(schema.get("oneOf").size()).isEqualTo(5);
    }

    @Test
    void pluginSchemaIncludesRequiredFields() {
        PluginRegistry registry = registryWith(
                "process", Map.of(
                        "command", new Parameter(ParameterType.STRING, true, null, null, null, null),
                        "timeout", new Parameter(ParameterType.STRING, false, null, null, null, null)));

        ObjectNode schema = StepSchemaComposer.compose(registry, mapper);

        var oneOf = schema.get("oneOf");
        for (var variant : oneOf) {
            if (variant.has("properties") && variant.get("properties").has("process")) {
                var processSchema = variant.get("properties").get("process");
                assertThat(processSchema.get("properties").has("command")).isTrue();
                assertThat(processSchema.get("properties").has("timeout")).isTrue();
                assertThat(processSchema.get("required").toString()).contains("command");
                assertThat(processSchema.get("required").toString()).doesNotContain("timeout");
                return;
            }
        }
        org.assertj.core.api.Assertions.fail("process variant not found in oneOf");
    }


    @Test
    void schemaIncludesBlockVariant() {
        PluginRegistry registry = registryWith("process", Map.of());
        ObjectNode     schema   = StepSchemaComposer.compose(registry, mapper);
        assertThat(hasVariantWithKey(schema, "block")).isTrue();
    }

    @Test
    void schemaIncludesIfThenElseVariant() {
        PluginRegistry registry = registryWith("process", Map.of());
        ObjectNode     schema   = StepSchemaComposer.compose(registry, mapper);
        boolean     hasIfThen = false;
        for (var variant : schema.get("oneOf")) {
            if (variant.has("properties") && variant.get("properties").has("if")
                && variant.get("properties").has("then")) {
                hasIfThen = true;
                break;
            }
        }
        assertThat(hasIfThen).isTrue();
    }

    @Test
    void schemaIncludesMatchCasesVariant() {
        PluginRegistry registry = registryWith("process", Map.of());
        ObjectNode     schema   = StepSchemaComposer.compose(registry, mapper);
        boolean     hasMatch = false;
        for (var variant : schema.get("oneOf")) {
            if (variant.has("properties") && variant.get("properties").has("match")
                && variant.get("properties").has("cases")) {
                hasMatch = true;
                break;
            }
        }
        assertThat(hasMatch).isTrue();
    }

    @Test
    void schemaIncludesParallelVariant() {
        PluginRegistry registry = registryWith("process", Map.of());
        ObjectNode     schema   = StepSchemaComposer.compose(registry, mapper);
        assertThat(hasVariantWithKey(schema, "parallel")).isTrue();
    }

    private boolean hasVariantWithKey(ObjectNode schema, String key) {
        for (var variant : schema.get("oneOf")) {
            if (variant.has("properties") && variant.get("properties").has(key)) {
                return true;
            }
        }
        return false;
    }

    private PluginRegistry registryWith(String name, Map<String, Parameter> inputs) {
        var registry = new CompositePluginRegistry();
        registry.register(new Definition(name, null, inputs, Map.of(), Portability.JAVA,
                (p, s) -> Result.of(Map.of()), null));
        return registry;
    }

    private PluginRegistry registryWith(String name1, Map<String, Parameter> inputs1,
                                         String name2, Map<String, Parameter> inputs2) {
        var registry = new CompositePluginRegistry();
        registry.register(new Definition(name1, null, inputs1, Map.of(), Portability.JAVA,
                (p, s) -> Result.of(Map.of()), null));
        registry.register(new Definition(name2, null, inputs2, Map.of(), Portability.JAVA,
                (p, s) -> Result.of(Map.of()), null));
        return registry;
    }
}
