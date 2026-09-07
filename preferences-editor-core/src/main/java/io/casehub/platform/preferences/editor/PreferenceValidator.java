package io.casehub.platform.preferences.editor;

import io.casehub.platform.api.preferences.EnumOption;
import io.casehub.platform.api.preferences.PreferenceConstraintKeys;
import io.casehub.platform.api.preferences.PreferenceSchemaDescriptor;

import java.time.Duration;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.PatternSyntaxException;

public class PreferenceValidator {

    private static final Logger LOG = Logger.getLogger(PreferenceValidator.class.getName());

    public List<String> validate(PreferenceSchemaDescriptor descriptor, String value) {
        return switch (descriptor.type()) {
            case "integer" -> validateInteger(descriptor.constraints(), value);
            case "number" -> validateNumber(descriptor.constraints(), value);
            case "boolean" -> validateBoolean(value);
            case "duration" -> validateDuration(value);
            case "string" -> validateString(descriptor.constraints(), value);
            case "enum" -> validateEnum(descriptor.options(), value);
            default -> {
                LOG.log(Level.WARNING, "Unknown preference type ''{0}'' for ''{1}'' — skipping validation",
                        new Object[]{descriptor.type(), descriptor.qualifiedName()});
                yield List.of();
            }
        };
    }

    private List<String> validateInteger(Map<String, Object> constraints, String value) {
        int parsed;
        try {
            parsed = Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return List.of("value '" + value + "' is not a valid integer");
        }
        return validateNumericRange(constraints, parsed, value);
    }

    private List<String> validateNumber(Map<String, Object> constraints, String value) {
        double parsed;
        try {
            parsed = Double.parseDouble(value);
        } catch (NumberFormatException e) {
            return List.of("value '" + value + "' is not a valid number");
        }
        return validateNumericRange(constraints, parsed, value);
    }

    private List<String> validateNumericRange(Map<String, Object> constraints, double parsed, String value) {
        List<String> violations = new ArrayList<>();
        Object minObj = constraints.get(PreferenceConstraintKeys.MIN);
        if (minObj instanceof Number min && parsed < min.doubleValue()) {
            violations.add("value '" + value + "' is below minimum " + min);
        }
        Object maxObj = constraints.get(PreferenceConstraintKeys.MAX);
        if (maxObj instanceof Number max && parsed > max.doubleValue()) {
            violations.add("value '" + value + "' exceeds maximum " + max);
        }
        return violations;
    }

    private List<String> validateBoolean(String value) {
        if ("true".equalsIgnoreCase(value) || "false".equalsIgnoreCase(value)) {
            return List.of();
        }
        return List.of("value '" + value + "' is not a valid boolean (expected 'true' or 'false')");
    }

    private List<String> validateDuration(String value) {
        try {
            Duration.parse(value);
            return List.of();
        } catch (DateTimeParseException e) {
            return List.of("value '" + value + "' is not a valid ISO-8601 duration");
        }
    }

    private List<String> validateString(Map<String, Object> constraints, String value) {
        List<String> violations = new ArrayList<>();
        Object minLenObj = constraints.get(PreferenceConstraintKeys.MIN_LENGTH);
        if (minLenObj instanceof Number minLen && value.length() < minLen.intValue()) {
            violations.add("value length " + value.length() + " is below minimum length " + minLen);
        }
        Object maxLenObj = constraints.get(PreferenceConstraintKeys.MAX_LENGTH);
        if (maxLenObj instanceof Number maxLen && value.length() > maxLen.intValue()) {
            violations.add("value length " + value.length() + " exceeds maximum length " + maxLen);
        }
        Object patternObj = constraints.get(PreferenceConstraintKeys.PATTERN);
        if (patternObj instanceof String pattern) {
            try {
                if (!value.matches(pattern)) {
                    violations.add("value must match pattern '" + pattern + "'");
                }
            } catch (PatternSyntaxException e) {
                LOG.log(Level.WARNING, "Invalid regex pattern ''{0}'' in schema — skipping pattern check", pattern);
            }
        }
        return violations;
    }

    private List<String> validateEnum(List<EnumOption> options, String value) {
        boolean valid = options.stream().anyMatch(o -> o.value().equals(value));
        if (valid) {
            return List.of();
        }
        List<String> allowed = options.stream().map(EnumOption::value).toList();
        return List.of("value '" + value + "' is not one of the allowed options: " + allowed);
    }
}
