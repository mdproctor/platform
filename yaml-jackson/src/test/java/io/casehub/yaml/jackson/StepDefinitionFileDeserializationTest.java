package io.casehub.yaml.jackson;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.casehub.yaml.core.step.InvokeBinding;
import io.casehub.yaml.core.step.Declaration;
import io.casehub.yaml.core.step.DeclarationFile;
import io.casehub.yaml.core.step.DeclarationParser;
import io.casehub.yaml.plugin.api.ParameterType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class DeclarationFileDeserializationTest {

    private ObjectMapper yamlMapper;

    @BeforeEach
    void setUp() {
        yamlMapper = YamlMappers.createWithCoreModule();
    }

    private static final String STEP_YAML = """
            namespace: fsi
            assess-risk:
              description: Evaluate risk
              inputs:
                instrumentId:
                  type: string
                  required: true
              outputs:
                level:
                  type: string
                  enum:
                    - LOW
                    - MEDIUM
                    - HIGH
              invoke:
                mcp: fsi.risk.assess
            notify:
              invoke:
                rest:
                  method: POST
                  url: /api/notifications
                  body:
                    message: "${message}"
            """;

    @Test
    void deserializesDeclarationFile() throws Exception {
        DeclarationFile file = yamlMapper.readValue(STEP_YAML, DeclarationFile.class);

        assertThat(file.namespace()).isEqualTo("fsi");
        assertThat(file.actions()).hasSize(2);

        Declaration assessRisk = file.actions().get("assess-risk");
        assertThat(assessRisk.name()).isEqualTo("assess-risk");
        assertThat(assessRisk.description()).isEqualTo("Evaluate risk");
        assertThat(assessRisk.inputs().get("instrumentId").type()).isEqualTo(ParameterType.STRING);
        assertThat(assessRisk.inputs().get("instrumentId").required()).isTrue();
        assertThat(assessRisk.outputs().get("level").allowedValues()).containsExactly("LOW", "MEDIUM", "HIGH");
        assertThat(assessRisk.invoke()).isInstanceOf(InvokeBinding.Mcp.class);

        Declaration notify = file.actions().get("notify");
        assertThat(notify.invoke()).isInstanceOf(InvokeBinding.Rest.class);
        InvokeBinding.Rest rest = (InvokeBinding.Rest) notify.invoke();
        assertThat(rest.method()).isEqualTo("POST");
        assertThat(rest.url()).isEqualTo("/api/notifications");
    }

    @Test
    @SuppressWarnings("unchecked")
    void jacksonAndParserProduceSameModel() throws Exception {
        DeclarationFile fromJackson = yamlMapper.readValue(STEP_YAML, DeclarationFile.class);

        Map<String, Object> rawMap = yamlMapper.readValue(STEP_YAML, Map.class);
        Map<String, Object> actionsMap = new java.util.LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : rawMap.entrySet()) {
            if (!"namespace".equals(entry.getKey())) {
                actionsMap.put(entry.getKey(), entry.getValue());
            }
        }
        Map<String, Object> parserInput = Map.of(
                "namespace", rawMap.getOrDefault("namespace", ""),
                "actions", actionsMap);
        DeclarationFile fromParser = DeclarationParser.parse(parserInput);

        assertThat(fromJackson.namespace()).isEqualTo(fromParser.namespace());
        assertThat(fromJackson.actions().keySet()).isEqualTo(fromParser.actions().keySet());

        for (String actionName : fromJackson.actions().keySet()) {
            Declaration jacksonDef = fromJackson.actions().get(actionName);
            Declaration parserDef = fromParser.actions().get(actionName);

            assertThat(jacksonDef.name()).isEqualTo(parserDef.name());
            assertThat(jacksonDef.description()).isEqualTo(parserDef.description());
            assertThat(jacksonDef.inputs().keySet()).isEqualTo(parserDef.inputs().keySet());
            assertThat(jacksonDef.outputs().keySet()).isEqualTo(parserDef.outputs().keySet());
            assertThat(jacksonDef.invoke().getClass()).isEqualTo(parserDef.invoke().getClass());
        }
    }

    @Test
    void deserializesAgentBinding() throws Exception {
        String yaml = """
                analyse:
                  invoke:
                    agent:
                      descriptor: trade-analyst
                      model: claude-sonnet-5
                      structured-output: true
                """;

        DeclarationFile file = yamlMapper.readValue(yaml, DeclarationFile.class);
        InvokeBinding.Agent agent = (InvokeBinding.Agent) file.actions().get("analyse").invoke();
        assertThat(agent.descriptor()).isEqualTo("trade-analyst");
        assertThat(agent.model()).isEqualTo("claude-sonnet-5");
        assertThat(agent.structuredOutput()).isTrue();
    }

    @Test
    void deserializesProcessBinding() throws Exception {
        String yaml = """
                calc:
                  invoke:
                    process:
                      command: /opt/calc
                      args:
                        - "--mode"
                        - "fast"
                      output: json
                      timeout: 30s
                      working-dir: /opt/build
                """;

        DeclarationFile file = yamlMapper.readValue(yaml, DeclarationFile.class);
        InvokeBinding.Process proc = (InvokeBinding.Process) file.actions().get("calc").invoke();
        assertThat(proc.command()).isEqualTo("/opt/calc");
        assertThat(proc.args()).containsExactly("--mode", "fast");
        assertThat(proc.output()).isEqualTo("json");
        assertThat(proc.workingDir()).isEqualTo("/opt/build");
    }

    @Test
    void defaultNamespaceIsEmpty() throws Exception {
        String yaml = """
                test:
                  invoke:
                    mcp: test.tool
                """;

        DeclarationFile file = yamlMapper.readValue(yaml, DeclarationFile.class);
        assertThat(file.namespace()).isEmpty();
    }
}
