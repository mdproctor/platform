package io.casehub.yaml.statemachine.generator;

import java.util.Map;
import java.util.regex.Pattern;

public final class GuardCompiler {

    private static final Pattern FIELD_REF =
        Pattern.compile("\\b([a-zA-Z_][a-zA-Z0-9_]*)\\b");

    private GuardCompiler() {}

    public static String compile(String guard, String patternVar,
            Map<String, String> fields) {
        if (guard == null || guard.isBlank()) return "true";

        // Handle string equality: field == 'value'
        var stringEq = Pattern.compile(
            "([a-zA-Z_]\\w*)\\s*==\\s*'([^']*)'");
        var sm = stringEq.matcher(guard);
        if (sm.matches() && fields.containsKey(sm.group(1))) {
            return "\"" + sm.group(2) + "\".equals("
                + patternVar + "." + sm.group(1) + "())";
        }

        // Handle bare boolean: just a field name
        var stripped = guard.strip();
        if (fields.containsKey(stripped)
                && "boolean".equals(fields.get(stripped))) {
            return patternVar + "." + stripped + "()";
        }

        // General case: replace known field names with accessor calls
        var result = new StringBuilder();
        var matcher = FIELD_REF.matcher(guard);
        int last = 0;
        while (matcher.find()) {
            result.append(guard, last, matcher.start());
            var fieldName = matcher.group(1);
            if (fields.containsKey(fieldName)) {
                result.append(patternVar).append(".")
                      .append(fieldName).append("()");
            } else {
                result.append(fieldName);
            }
            last = matcher.end();
        }
        result.append(guard, last, guard.length());
        return result.toString();
    }
}
