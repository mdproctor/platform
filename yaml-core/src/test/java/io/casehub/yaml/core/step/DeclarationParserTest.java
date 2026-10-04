package io.casehub.yaml.core.step;

import io.casehub.yaml.plugin.api.Parameter;
import io.casehub.yaml.plugin.api.ParameterType;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class DeclarationParserTest {

    @Test
    void parsesMinimalMcpAction() {
        Map<String, Object> yaml = Map.of(
                "namespace", "fsi",
                "actions", Map.of(
                        "assess-risk", Map.of(
                                "description", "Evaluate risk",
                                "inputs", Map.of(
                                        "instrumentId", Map.of("type", "string", "required", true)),
                                "outputs", Map.of(
                                        "level", Map.of("type", "string",
                                                "enum", List.of("LOW", "MEDIUM", "HIGH"))),
                                "invoke", Map.of("mcp", "fsi.risk.assess"))));

        DeclarationFile file = DeclarationParser.parse(yaml);
        assertThat(file.namespace()).isEqualTo("fsi");
        assertThat(file.actions()).hasSize(1);

        Declaration def = file.actions().get("assess-risk");
        assertThat(def.name()).isEqualTo("assess-risk");
        assertThat(def.description()).isEqualTo("Evaluate risk");
        assertThat(def.inputs()).containsKey("instrumentId");
        assertThat(def.inputs().get("instrumentId").type()).isEqualTo(ParameterType.STRING);
        assertThat(def.inputs().get("instrumentId").required()).isTrue();
        assertThat(def.outputs().get("level").allowedValues()).containsExactly("LOW", "MEDIUM", "HIGH");
        assertThat(def.invoke()).isInstanceOf(InvokeBinding.Mcp.class);
        assertThat(((InvokeBinding.Mcp) def.invoke()).tool()).isEqualTo("fsi.risk.assess");
    }

    @Test
    void parsesRestBinding() {
        Map<String, Object> yaml = Map.of(
                "actions", Map.of(
                        "notify", Map.of(
                                "invoke", Map.of("rest", mapOf(
                                        "method", "POST",
                                        "url", "/api/notifications",
                                        "body", Map.of("message", "${message}"))))));

        DeclarationFile    file = DeclarationParser.parse(yaml);
        InvokeBinding.Rest rest = (InvokeBinding.Rest) file.actions().get("notify").invoke();
        assertThat(rest.method()).isEqualTo("POST");
        assertThat(rest.url()).isEqualTo("/api/notifications");
        assertThat(rest.body()).containsEntry("message", "${message}");
    }

    @Test
    void parsesProcessBinding() {
        Map<String, Object> yaml = Map.of(
                "actions", Map.of(
                        "calc", Map.of(
                                "invoke", Map.of("process", mapOf(
                                        "command", "/opt/risk-engine/calc",
                                        "args", List.of("--portfolio", "${portfolio}"),
                                        "output", "json",
                                        "timeout", "30s")))));

        DeclarationFile       file = DeclarationParser.parse(yaml);
        InvokeBinding.Process proc = (InvokeBinding.Process) file.actions().get("calc").invoke();
        assertThat(proc.command()).isEqualTo("/opt/risk-engine/calc");
        assertThat(proc.args()).containsExactly("--portfolio", "${portfolio}");
        assertThat(proc.timeout()).isEqualTo("30s");
    }

    @Test
    void parsesAgentBinding() {
        Map<String, Object> yaml = Map.of(
                "actions", Map.of(
                        "analyse", Map.of(
                                "invoke", Map.of("agent", mapOf(
                                        "descriptor", "trade-analyst",
                                        "model", "claude-sonnet-5",
                                        "structured-output", true)))));

        DeclarationFile     file  = DeclarationParser.parse(yaml);
        InvokeBinding.Agent agent = (InvokeBinding.Agent) file.actions().get("analyse").invoke();
        assertThat(agent.descriptor()).isEqualTo("trade-analyst");
        assertThat(agent.model()).isEqualTo("claude-sonnet-5");
        assertThat(agent.structuredOutput()).isTrue();
    }

    @Test
    void parsesPythonShorthand() {
        Map<String, Object> yaml = Map.of(
                "actions", Map.of(
                        "sentiment", Map.of(
                                "invoke", Map.of("python", "steps/sentiment.py"))));

        DeclarationFile      file   = DeclarationParser.parse(yaml);
        InvokeBinding.Script script = (InvokeBinding.Script) file.actions().get("sentiment").invoke();
        assertThat(script.runtime()).isEqualTo("python3");
        assertThat(script.script()).isEqualTo("steps/sentiment.py");
    }

    @Test
    void parsesGraphqlBinding() {
        Map<String, Object> yaml = Map.of(
                "actions", Map.of(
                        "position", Map.of(
                                "invoke", Map.of("graphql", "{ position(symbol: \"${symbol}\") { quantity } }"))));

        DeclarationFile       file = DeclarationParser.parse(yaml);
        InvokeBinding.Graphql gql  = (InvokeBinding.Graphql) file.actions().get("position").invoke();
        assertThat(gql.query()).contains("position(symbol:");
    }

    @Test
    void rejectsUnknownInvokeType() {
        Map<String, Object> yaml = Map.of(
                "actions", Map.of(
                        "bad", Map.of("invoke", Map.of("unknown", "value"))));

        assertThatIllegalArgumentException()
                .isThrownBy(() -> DeclarationParser.parse(yaml))
                .withMessageContaining("Unknown invoke binding type");
    }

    @Test
    void defaultsNamespaceToEmpty() {
        Map<String, Object> yaml = Map.of(
                "actions", Map.of(
                        "test", Map.of("invoke", Map.of("mcp", "test"))));

        DeclarationFile file = DeclarationParser.parse(yaml);
        assertThat(file.namespace()).isEmpty();
    }

    @Test
    void parsesParameterWithFormatAndDefault() {
        Map<String, Object> yaml = Map.of(
                "actions", Map.of(
                        "test", Map.of(
                                "inputs", Map.of(
                                        "date", mapOf("type", "string", "format", "date", "default", "2026-01-01")),
                                "invoke", Map.of("mcp", "test"))));

        DeclarationFile file  = DeclarationParser.parse(yaml);
        Parameter   param = file.actions().get("test").inputs().get("date");
        assertThat(param.format()).isEqualTo("date");
        assertThat(param.defaultValue()).isEqualTo("2026-01-01");
    }

    @Test
    void parsesProcessBindingWithEnvAndWorkingDir() {
        Map<String, Object> yaml = Map.of(
                "actions", Map.of(
                        "build", Map.of(
                                "invoke", Map.of("process", mapOf(
                                        "command", "make",
                                        "env", Map.of("CC", "gcc"),
                                        "working-dir", "/opt/build",
                                        "on-error", "exit-code")))));

        DeclarationFile       file = DeclarationParser.parse(yaml);
        InvokeBinding.Process proc = (InvokeBinding.Process) file.actions().get("build").invoke();
        assertThat(proc.env()).containsEntry("CC", "gcc");
        assertThat(proc.workingDir()).isEqualTo("/opt/build");
        assertThat(proc.onError()).isEqualTo("exit-code");
    }

    @Test
    void parsesActionWithNoInputsOrOutputs() {
        Map<String, Object> yaml = Map.of(
                "actions", Map.of(
                        "ping", Map.of("invoke", Map.of("mcp", "system.ping"))));

        DeclarationFile file = DeclarationParser.parse(yaml);
        Declaration     def  = file.actions().get("ping");
        assertThat(def.inputs()).isEmpty();
        assertThat(def.outputs()).isEmpty();
    }

    @Test
    void parsesParameterRequiredDefaultsToFalse() {
        Map<String, Object> yaml = Map.of(
                "actions", Map.of(
                        "test", Map.of(
                                "inputs", Map.of(
                                        "name", Map.of("type", "string")),
                                "invoke", Map.of("mcp", "test"))));

        DeclarationFile file = DeclarationParser.parse(yaml);
        assertThat(file.actions().get("test").inputs().get("name").required()).isFalse();
    }

    @SuppressWarnings("unchecked")
    private static <K, V> Map<K, V> mapOf(Object... kvs) {
        var map = new LinkedHashMap<K, V>();
        for (int i = 0; i < kvs.length; i += 2) {
            map.put((K) kvs[i], (V) kvs[i + 1]);
        }
        return map;
    }
}
