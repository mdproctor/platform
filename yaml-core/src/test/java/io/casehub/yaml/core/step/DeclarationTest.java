package io.casehub.yaml.core.step;

import io.casehub.yaml.plugin.api.Parameter;
import io.casehub.yaml.plugin.api.ParameterType;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class DeclarationTest {

    @Test
    void qualifiedNameWithNamespace() {
        var def = new Declaration("assess-risk", "Risk assessment", null, null,
                                  new InvokeBinding.Mcp("fsi.risk.assess"));
        assertThat(def.qualifiedName("fsitrading")).isEqualTo("fsitrading.assess-risk");
    }

    @Test
    void qualifiedNameWithoutNamespace() {
        var def = new Declaration("assess-risk", "Risk assessment", null, null,
                                  new InvokeBinding.Mcp("fsi.risk.assess"));
        assertThat(def.qualifiedName("")).isEqualTo("assess-risk");
    }

    @Test
    void inputsAndOutputsDefaultToEmptyMaps() {
        var def = new Declaration("test", null, null, null,
                                  new InvokeBinding.Mcp("test.tool"));
        assertThat(def.inputs()).isEmpty();
        assertThat(def.outputs()).isEmpty();
    }

    @Test
    void inputsAndOutputsArePreserved() {
        var inputs = Map.of("name", new Parameter(ParameterType.STRING, true, null, null, null, null));
        var outputs = Map.of("result", new Parameter(ParameterType.STRING, false, null, null, null, null));
        var def = new Declaration("test", "A test step", inputs, outputs,
                                  new InvokeBinding.Mcp("test.tool"));
        assertThat(def.inputs()).containsKey("name");
        assertThat(def.outputs()).containsKey("result");
        assertThat(def.description()).isEqualTo("A test step");
    }

    @Test
    void stepDefinitionFileWrapsActions() {
        var def = new Declaration("test", null, null, null,
                                  new InvokeBinding.Mcp("test.tool"));
        var file = new DeclarationFile("fsi", Map.of("test", def));
        assertThat(file.namespace()).isEqualTo("fsi");
        assertThat(file.actions()).containsKey("test");
    }

    @Test
    void stepDefinitionFileDefaultsNamespaceToEmpty() {
        var file = new DeclarationFile(null, Map.of());
        assertThat(file.namespace()).isEmpty();
    }

    @Test
    void stepDefinitionFileActionsAreImmutable() {
        var def = new Declaration("test", null, null, null,
                                  new InvokeBinding.Mcp("test.tool"));
        var mutable = new java.util.HashMap<String, Declaration>();
        mutable.put("test", def);
        var file = new DeclarationFile("ns", mutable);
        mutable.put("extra", def);
        assertThat(file.actions()).hasSize(1);
    }
}
