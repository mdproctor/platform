package io.casehub.yaml.core.error;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class YamlErrorCollectorTest {

    @Test
    void empty_hasNoErrors() {
        var collector = new YamlErrorCollector();
        assertThat(collector.hasErrors()).isFalse();
        assertThat(collector.errors()).isEmpty();
    }

    @Test
    void add_collectsErrors() {
        var collector = new YamlErrorCollector();
        collector.add(new ParseError.UnknownStepError("s1", "action-a", SourceLocation.UNKNOWN, null));
        collector.add(new ParseError.DuplicateStepError("s2", SourceLocation.UNKNOWN, null));
        assertThat(collector.hasErrors()).isTrue();
        assertThat(collector.errors()).hasSize(2);
    }

    @Test
    void throwIfErrors_noErrors_doesNotThrow() {
        new YamlErrorCollector().throwIfErrors();
    }

    @Test
    void throwIfErrors_withErrors_throwsValidationException() {
        var collector = new YamlErrorCollector();
        collector.add(new ParseError.UnknownStepError("s1", "x", SourceLocation.UNKNOWN, null));
        assertThatThrownBy(collector::throwIfErrors)
                .isInstanceOf(YamlValidationException.class)
                .satisfies(ex -> {
                    var ve = (YamlValidationException) ex;
                    assertThat(ve.errors()).hasSize(1);
                    assertThat(ve.getMessage()).contains("1 validation error");
                });
    }

    @Test
    void validationException_messageIncludesSummaries() {
        var collector = new YamlErrorCollector();
        collector.add(new ParseError.UnknownStepError("s1", "a", SourceLocation.UNKNOWN, null));
        collector.add(new ParseError.DuplicateStepError("s2", SourceLocation.UNKNOWN, null));
        assertThatThrownBy(collector::throwIfErrors)
                .hasMessageContaining("2 validation error")
                .hasMessageContaining("unknown action")
                .hasMessageContaining("Duplicate step");
    }

    @Test
    void errors_returnsDefensiveCopy() {
        var collector = new YamlErrorCollector();
        collector.add(new ParseError.DuplicateStepError("s1", SourceLocation.UNKNOWN, null));
        var list = collector.errors();
        collector.add(new ParseError.DuplicateStepError("s2", SourceLocation.UNKNOWN, null));
        assertThat(list).hasSize(1);
    }
}
