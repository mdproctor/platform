package io.casehub.yaml.jackson;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.casehub.yaml.core.step.MatchCase;
import io.casehub.yaml.core.step.MatchPattern;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class MatchPatternDeserializerTest {

    private ObjectMapper mapper;

    @BeforeEach
    void setUp() {
        mapper = YamlMappers.createWithCoreModule();
    }

    @Test
    void deserializesValuePatternFromScalar() throws Exception {
        String yaml = """
                pattern: "ACTIVE"
                steps:
                  - action: activate
                """;
        MatchCase mc = mapper.readValue(yaml, MatchCase.class);
        assertThat(mc.pattern()).isInstanceOf(MatchPattern.ValuePattern.class);
        assertThat(((MatchPattern.ValuePattern) mc.pattern()).value()).isEqualTo("ACTIVE");
        assertThat(mc.steps()).hasSize(1);
    }

    @Test
    void deserializesValuePatternFromInteger() throws Exception {
        String yaml = """
                pattern: 42
                steps:
                  - action: handle
                """;
        MatchCase mc = mapper.readValue(yaml, MatchCase.class);
        assertThat(mc.pattern()).isInstanceOf(MatchPattern.ValuePattern.class);
        assertThat(((MatchPattern.ValuePattern) mc.pattern()).value()).isEqualTo(42);
    }

    @Test
    void deserializesStructuralPattern() throws Exception {
        String yaml = """
                pattern:
                  type: trade
                  priority: HIGH
                steps:
                  - action: escalate
                """;
        MatchCase mc = mapper.readValue(yaml, MatchCase.class);
        assertThat(mc.pattern()).isInstanceOf(MatchPattern.StructuralPattern.class);
        var sp = (MatchPattern.StructuralPattern) mc.pattern();
        assertThat(sp.fields()).containsEntry("type", "trade");
        assertThat(sp.fields()).containsEntry("priority", "HIGH");
    }

    @Test
    void deserializesGuard() throws Exception {
        String yaml = """
                pattern:
                  type: trade
                guard: "${match.amount} > 1000000"
                steps:
                  - action: escalate
                """;
        MatchCase mc = mapper.readValue(yaml, MatchCase.class);
        assertThat(mc.guard()).isEqualTo("${match.amount} > 1000000");
    }

    @Test
    void deserializesDefaultCase() throws Exception {
        String yaml = """
                default:
                  - action: log
                """;
        MatchCase mc = mapper.readValue(yaml, MatchCase.class);
        assertThat(mc.pattern()).isInstanceOf(MatchPattern.DefaultPattern.class);
        assertThat(mc.guard()).isNull();
        assertThat(mc.steps()).hasSize(1);
    }

    @Test
    void deserializesNullGuardWhenAbsent() throws Exception {
        String yaml = """
                pattern: "ACTIVE"
                steps:
                  - action: activate
                """;
        MatchCase mc = mapper.readValue(yaml, MatchCase.class);
        assertThat(mc.guard()).isNull();
    }

    @Test
    void deserializesMatchPatternDirectly() throws Exception {
        String yaml = "\"ACTIVE\"";
        MatchPattern mp = mapper.readValue(yaml, MatchPattern.class);
        assertThat(mp).isInstanceOf(MatchPattern.ValuePattern.class);
        assertThat(((MatchPattern.ValuePattern) mp).value()).isEqualTo("ACTIVE");
    }

    @Test
    void deserializesMatchPatternAsMap() throws Exception {
        String yaml = """
                type: trade
                """;
        MatchPattern mp = mapper.readValue(yaml, MatchPattern.class);
        assertThat(mp).isInstanceOf(MatchPattern.StructuralPattern.class);
        assertThat(((MatchPattern.StructuralPattern) mp).fields()).containsEntry("type", "trade");
    }

    @Test
    void deserializesAnyOfPatternFromArray() throws Exception {
        String yaml = """
                      - "PENDING"
                      - "ACTIVE"
                      """;
        MatchPattern mp = mapper.readValue(yaml, MatchPattern.class);
        assertThat(mp).isInstanceOf(MatchPattern.AnyOfPattern.class);
        var aop = (MatchPattern.AnyOfPattern) mp;
        assertThat(aop.values()).containsExactly("PENDING", "ACTIVE");
    }

    @Test
    void deserializesAnyOfPatternWithMixedTypes() throws Exception {
        String yaml = """
                      - "text"
                      - 42
                      """;
        MatchPattern mp = mapper.readValue(yaml, MatchPattern.class);
        assertThat(mp).isInstanceOf(MatchPattern.AnyOfPattern.class);
        var aop = (MatchPattern.AnyOfPattern) mp;
        assertThat(aop.values()).containsExactly("text", 42);
    }

    @Test
    void deserializesDefaultPatternFromAnyKeyword() throws Exception {
        String       yaml = "\"any\"";
        MatchPattern mp   = mapper.readValue(yaml, MatchPattern.class);
        assertThat(mp).isInstanceOf(MatchPattern.DefaultPattern.class);
    }

    @Test
    void deserializesAnyOfPatternInMatchCase() throws Exception {
        String yaml = """
                      pattern:
                        - "PENDING"
                        - "ACTIVE"
                      steps:
                        - action: handle
                      """;
        MatchCase mc = mapper.readValue(yaml, MatchCase.class);
        assertThat(mc.pattern()).isInstanceOf(MatchPattern.AnyOfPattern.class);
        var aop = (MatchPattern.AnyOfPattern) mc.pattern();
        assertThat(aop.values()).containsExactly("PENDING", "ACTIVE");
    }

}
