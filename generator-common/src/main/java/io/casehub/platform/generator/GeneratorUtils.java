package io.casehub.platform.generator;

import org.jboss.jandex.ClassInfo;
import org.jboss.jandex.DotName;
import org.jboss.jandex.IndexView;
import org.jboss.jandex.MethodInfo;

import java.util.Set;

public final class GeneratorUtils {

    private GeneratorUtils() {}

    private static final Set<String> SIMPLE_TYPES = Set.of(
            "java.lang.String",
            "java.lang.Integer", "java.lang.Long", "java.lang.Short", "java.lang.Byte",
            "java.lang.Float", "java.lang.Double", "java.lang.Boolean", "java.lang.Character",
            "java.util.UUID"
    );

    public static boolean isSimpleType(String fqcn, IndexView index) {
        if (SIMPLE_TYPES.contains(fqcn)) { return true; }
        if (fqcn.startsWith("java.time.")) { return true; }
        if (index != null) {
            ClassInfo ci = index.getClassByName(fqcn);
            if (ci != null) {
                if (ci.isEnum()) { return true; }
                if (hasStaticStringMethod(ci, "fromString")) { return true; }
                if (!ci.isEnum() && hasStaticStringMethod(ci, "valueOf")) { return true; }
            }
        }
        return false;
    }

    public static boolean isSimpleType(String fqcn) {
        return isSimpleType(fqcn, null);
    }

    public static boolean isCollectionType(String returnType) {
        return returnType.startsWith("List<") || returnType.startsWith("Set<")
               || returnType.startsWith("Collection<") || returnType.startsWith("Map<");
    }

    private static final Set<String> PRIMITIVE_TYPES = Set.of(
            "boolean", "byte", "short", "int", "long", "float", "double", "char"
                                                             );

    public static boolean isPrimitiveType(String returnType) {
        return PRIMITIVE_TYPES.contains(returnType);
    }


    public static String toKebabCase(String camelCase) {
        if (camelCase == null || camelCase.isEmpty()) { return camelCase; }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < camelCase.length(); i++) {
            char c = camelCase.charAt(i);
            if (Character.isUpperCase(c)) {
                if (i > 0) {
                    char prev = camelCase.charAt(i - 1);
                    if (Character.isLowerCase(prev) || Character.isDigit(prev)) {
                        sb.append('-');
                    } else if (Character.isUpperCase(prev) && i + 1 < camelCase.length()
                               && Character.isLowerCase(camelCase.charAt(i + 1))) {
                        sb.append('-');
                    }
                }
                sb.append(Character.toLowerCase(c));
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    public static String toPascalCase(String kebab) {
        if (kebab == null || kebab.isEmpty()) { return kebab; }
        kebab = kebab.replace('/', '-');
        StringBuilder sb = new StringBuilder();
        for (String part : kebab.split("-")) {
            if (!part.isEmpty()) {
                sb.append(Character.toUpperCase(part.charAt(0)));
                if (part.length() > 1) {
                    sb.append(part.substring(1));
                }
            }
        }
        return sb.toString();
    }

    public static String resolveHttpVerb(OperationType type, String restMethodOverride) {
        if (restMethodOverride != null) {
            return restMethodOverride;
        }
        return switch (type) {
            case QUERY, STREAM -> "GET";
            case MUTATION -> "POST";
        };
    }

    public static String resolveRestPath(String restPathOverride, String methodName) {
        if (restPathOverride != null) {
            return restPathOverride;
        }
        return toKebabCase(methodName);
    }

    public static String decapitalize(String s) {
        if (s == null || s.isEmpty()) { return s; }
        return Character.toLowerCase(s.charAt(0)) + s.substring(1);
    }

    public static String capitalize(String s) {
        if (s == null || s.isEmpty()) { return s; }
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    private static boolean hasStaticStringMethod(ClassInfo ci, String methodName) {
        DotName stringType = DotName.createSimple("java.lang.String");
        for (MethodInfo m : ci.methods()) {
            if (m.name().equals(methodName)
                && java.lang.reflect.Modifier.isStatic(m.flags())
                && m.parameterTypes().size() == 1
                && m.parameterTypes().get(0).name().equals(stringType)) {
                return true;
            }
        }
        return false;
    }
}
