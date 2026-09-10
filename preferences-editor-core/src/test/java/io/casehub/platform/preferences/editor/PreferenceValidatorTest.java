package io.casehub.platform.preferences.editor;

import io.casehub.platform.api.preferences.EnumOption;
import io.casehub.platform.api.preferences.IntPreference;
import io.casehub.platform.api.preferences.DoublePreference;
import io.casehub.platform.api.preferences.BooleanPreference;
import io.casehub.platform.api.preferences.DurationPreference;
import io.casehub.platform.api.preferences.PreferenceConstraintKeys;
import io.casehub.platform.api.preferences.PreferenceKey;
import io.casehub.platform.api.preferences.PreferenceSchemaDescriptor;
import io.casehub.platform.api.preferences.SingleValuePreference;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class PreferenceValidatorTest {

    private final PreferenceValidator validator = new PreferenceValidator();

    // --- helper to build descriptors quickly ---

    private static final record StringPref(String value) implements SingleValuePreference {
        @Override public String toSerializedValue() { return value; }
    }

    private static PreferenceSchemaDescriptor intDescriptor(Map<String, Object> constraints) {
        return PreferenceSchemaDescriptor.of(
                new PreferenceKey<>("test", "intPref", IntPreference.of(0), IntPreference::parse))
                .label("Int Pref")
                .constraints(constraints)
                .build();
    }

    private static PreferenceSchemaDescriptor numberDescriptor(Map<String, Object> constraints) {
        return PreferenceSchemaDescriptor.of(
                new PreferenceKey<>("test", "numPref", DoublePreference.of(0.0), DoublePreference::parse))
                .label("Num Pref")
                .constraints(constraints)
                .build();
    }

    private static PreferenceSchemaDescriptor booleanDescriptor() {
        return PreferenceSchemaDescriptor.of(
                new PreferenceKey<>("test", "boolPref", BooleanPreference.of(false), BooleanPreference::parse))
                .label("Bool Pref")
                .build();
    }

    private static PreferenceSchemaDescriptor durationDescriptor() {
        return PreferenceSchemaDescriptor.of(
                new PreferenceKey<>("test", "durPref", new DurationPreference(Duration.ofHours(1)), s -> new DurationPreference(Duration.parse(s))))
                .label("Dur Pref")
                .build();
    }

    private static PreferenceSchemaDescriptor stringDescriptor(Map<String, Object> constraints) {
        return PreferenceSchemaDescriptor.of(
                new PreferenceKey<>("test", "strPref", new StringPref(""), s -> new StringPref(s)))
                .label("Str Pref")
                .constraints(constraints)
                .build();
    }

    private static PreferenceSchemaDescriptor enumDescriptor(List<EnumOption> options) {
        return PreferenceSchemaDescriptor.of(
                new PreferenceKey<>("test", "enumPref", new StringPref("a"), s -> new StringPref(s)))
                .label("Enum Pref")
                .type("enum")
                .options(options)
                .build();
    }

    private static PreferenceSchemaDescriptor unknownTypeDescriptor() {
        return PreferenceSchemaDescriptor.of(
                new PreferenceKey<>("test", "unkPref", new StringPref(""), s -> new StringPref(s)))
                .label("Unknown Pref")
                .type("custom-widget")
                .build();
    }

    // --- integer ---

    @Test
    void integer_valid() {
        assertThat(validator.validate(intDescriptor(Map.of()), "42")).isEmpty();
    }

    @Test
    void integer_unparseable() {
        List<String> violations = validator.validate(intDescriptor(Map.of()), "abc");
        assertThat(violations).hasSize(1);
        assertThat(violations.get(0)).contains("abc").containsIgnoringCase("integer");
    }

    @Test
    void integer_below_min() {
        var desc = intDescriptor(Map.of(PreferenceConstraintKeys.MIN, 1));
        List<String> violations = validator.validate(desc, "0");
        assertThat(violations).hasSize(1);
        assertThat(violations.get(0)).contains("0").contains("1");
    }

    @Test
    void integer_above_max() {
        var desc = intDescriptor(Map.of(PreferenceConstraintKeys.MAX, 720));
        List<String> violations = validator.validate(desc, "999");
        assertThat(violations).hasSize(1);
        assertThat(violations.get(0)).contains("999").contains("720");
    }

    @Test
    void integer_within_range() {
        var desc = intDescriptor(Map.of(
                PreferenceConstraintKeys.MIN, 1,
                PreferenceConstraintKeys.MAX, 720));
        assertThat(validator.validate(desc, "360")).isEmpty();
    }

    // --- number ---

    @Test
    void number_valid() {
        assertThat(validator.validate(numberDescriptor(Map.of()), "3.14")).isEmpty();
    }

    @Test
    void number_unparseable() {
        List<String> violations = validator.validate(numberDescriptor(Map.of()), "xyz");
        assertThat(violations).hasSize(1);
        assertThat(violations.get(0)).contains("xyz").containsIgnoringCase("number");
    }

    @Test
    void number_below_min() {
        var desc = numberDescriptor(Map.of(PreferenceConstraintKeys.MIN, 0.0));
        List<String> violations = validator.validate(desc, "-1.5");
        assertThat(violations).hasSize(1);
    }

    @Test
    void number_above_max() {
        var desc = numberDescriptor(Map.of(PreferenceConstraintKeys.MAX, 100.0));
        List<String> violations = validator.validate(desc, "100.1");
        assertThat(violations).hasSize(1);
    }

    // --- boolean ---

    @Test
    void boolean_valid_true() {
        assertThat(validator.validate(booleanDescriptor(), "true")).isEmpty();
    }

    @Test
    void boolean_valid_false_case_insensitive() {
        assertThat(validator.validate(booleanDescriptor(), "FALSE")).isEmpty();
    }

    @Test
    void boolean_invalid() {
        List<String> violations = validator.validate(booleanDescriptor(), "yes");
        assertThat(violations).hasSize(1);
        assertThat(violations.get(0)).contains("yes").containsIgnoringCase("boolean");
    }

    // --- duration ---

    @Test
    void duration_valid() {
        assertThat(validator.validate(durationDescriptor(), "PT2H30M")).isEmpty();
    }

    @Test
    void duration_invalid() {
        List<String> violations = validator.validate(durationDescriptor(), "2hours");
        assertThat(violations).hasSize(1);
        assertThat(violations.get(0)).contains("2hours").containsIgnoringCase("duration");
    }

    // --- string ---

    @Test
    void string_valid_no_constraints() {
        assertThat(validator.validate(stringDescriptor(Map.of()), "anything")).isEmpty();
    }

    @Test
    void string_too_short() {
        var desc = stringDescriptor(Map.of(PreferenceConstraintKeys.MIN_LENGTH, 3));
        List<String> violations = validator.validate(desc, "ab");
        assertThat(violations).hasSize(1);
        assertThat(violations.get(0)).contains("2").contains("3");
    }

    @Test
    void string_too_long() {
        var desc = stringDescriptor(Map.of(PreferenceConstraintKeys.MAX_LENGTH, 5));
        List<String> violations = validator.validate(desc, "abcdef");
        assertThat(violations).hasSize(1);
        assertThat(violations.get(0)).contains("6").contains("5");
    }

    @Test
    void string_pattern_mismatch() {
        var desc = stringDescriptor(Map.of(PreferenceConstraintKeys.PATTERN, "^[a-z]+$"));
        List<String> violations = validator.validate(desc, "ABC");
        assertThat(violations).hasSize(1);
        assertThat(violations.get(0)).contains("^[a-z]+$");
    }

    @Test
    void string_pattern_match() {
        var desc = stringDescriptor(Map.of(PreferenceConstraintKeys.PATTERN, "^[a-z]+$"));
        assertThat(validator.validate(desc, "abc")).isEmpty();
    }

    // --- enum ---

    @Test
    void enum_valid_option() {
        var desc = enumDescriptor(List.of(new EnumOption("low", "Low"), new EnumOption("high", "High")));
        assertThat(validator.validate(desc, "low")).isEmpty();
    }

    @Test
    void enum_invalid_option() {
        var desc = enumDescriptor(List.of(new EnumOption("low", "Low"), new EnumOption("high", "High")));
        List<String> violations = validator.validate(desc, "medium");
        assertThat(violations).hasSize(1);
        assertThat(violations.get(0)).contains("medium");
    }

    // --- unknown type ---

    @Test
    void unknown_type_passes_validation() {
        assertThat(validator.validate(unknownTypeDescriptor(), "anything")).isEmpty();
    }

    // --- multiple violations ---

    @Test
    void string_multiple_violations() {
        var desc = stringDescriptor(Map.of(
                PreferenceConstraintKeys.MIN_LENGTH, 10,
                PreferenceConstraintKeys.PATTERN, "^[0-9]+$"));
        List<String> violations = validator.validate(desc, "abc");
        assertThat(violations).hasSize(2);
    }
}
