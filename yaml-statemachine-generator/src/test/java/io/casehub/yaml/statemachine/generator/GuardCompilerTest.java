package io.casehub.yaml.statemachine.generator;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class GuardCompilerTest {

    @Test
    void compile_numericComparison() {
        var result = GuardCompiler.compile("amount > 0", "s",
            Map.of("amount", "number"));
        assertThat(result).isEqualTo("s.amount() > 0");
    }

    @Test
    void compile_integerComparison() {
        var result = GuardCompiler.compile("count >= 2", "a",
            Map.of("count", "integer"));
        assertThat(result).isEqualTo("a.count() >= 2");
    }

    @Test
    void compile_stringEquality() {
        var result = GuardCompiler.compile("status == 'active'", "s",
            Map.of("status", "string"));
        assertThat(result).isEqualTo("\"active\".equals(s.status())");
    }

    @Test
    void compile_booleanField() {
        var result = GuardCompiler.compile("enabled", "s",
            Map.of("enabled", "boolean"));
        assertThat(result).isEqualTo("s.enabled()");
    }

    @Test
    void compile_andCombinator() {
        var result = GuardCompiler.compile("amount > 0 && count >= 1",
            "s", Map.of("amount", "number", "count", "integer"));
        assertThat(result).isEqualTo("s.amount() > 0 && s.count() >= 1");
    }

    @Test
    void compile_unknownField_passesThrough() {
        var result = GuardCompiler.compile("unknown > 5", "s", Map.of());
        assertThat(result).isEqualTo("unknown > 5");
    }
}
