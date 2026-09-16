package io.casehub.platform.generator;

import com.palantir.javapoet.TypeName;
import org.jboss.jandex.AnnotationInstance;
import org.jboss.jandex.AnnotationTarget;
import org.jboss.jandex.ClassInfo;
import org.jboss.jandex.DotName;
import org.jboss.jandex.Index;
import org.jboss.jandex.IndexView;
import org.jboss.jandex.MethodInfo;
import org.jboss.jandex.Type;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class McpDomainJandexScanner {

    private static final DotName MCP_DOMAIN = DotName.createSimple("io.casehub.platform.api.mcp.McpDomain");
    private static final DotName PLATFORM_QUERY = DotName.createSimple("io.casehub.platform.api.mcp.PlatformQuery");
    private static final DotName PLATFORM_MUTATION = DotName.createSimple("io.casehub.platform.api.mcp.PlatformMutation");
    private static final DotName PLATFORM_STREAM = DotName.createSimple("io.casehub.platform.api.mcp.PlatformStream");
    private static final DotName PATH_PARAM_ANN = DotName.createSimple("io.casehub.platform.api.mcp.PathParam");
    private static final DotName REST_METHOD_ANN = DotName.createSimple("io.casehub.platform.api.mcp.RestMethod");
    private static final DotName REST_PATH_ANN = DotName.createSimple("io.casehub.platform.api.mcp.RestPath");
    private static final DotName REST_STATUS_ANN = DotName.createSimple("io.casehub.platform.api.mcp.RestStatus");
    private static final DotName REST_NAME_ANN = DotName.createSimple("io.casehub.platform.api.mcp.RestName");
    private static final DotName CONTEXT_PARAM_ANN = DotName.createSimple("io.casehub.platform.api.mcp.ContextParam");
    private static final DotName ROLES_ALLOWED_ANN = DotName.createSimple("jakarta.annotation.security.RolesAllowed");
    private static final DotName PAGINATED_ANN = DotName.createSimple("io.casehub.platform.api.mcp.PaginatedResponse");

    public List<DomainScanResult> scan(IndexView index) {
        Map<String, DomainScanResult> domains = new LinkedHashMap<>();

        for (AnnotationInstance ann : index.getAnnotations(MCP_DOMAIN)) {
            if (ann.target().kind() != AnnotationTarget.Kind.CLASS) { continue; }

            ClassInfo classInfo = ann.target().asClass();


            String domainName = ann.value().asString();
            String basePath = null;
            if (ann.value("basePath") != null && !ann.value("basePath").asString().isEmpty()) {
                basePath = ann.value("basePath").asString();
            }

            String finalBasePath = basePath;
            DomainScanResult domain = domains.computeIfAbsent(domainName,
                    d -> DomainScanResult.of(d, classInfo.name().toString(), classInfo.simpleName(), finalBasePath));

            for (MethodInfo method : classInfo.methods()) {
                AnnotationInstance queryAnn = method.annotation(PLATFORM_QUERY);
                AnnotationInstance mutAnn = method.annotation(PLATFORM_MUTATION);
                AnnotationInstance streamAnn = method.annotation(PLATFORM_STREAM);

                if (queryAnn == null && mutAnn == null && streamAnn == null) { continue; }

                OperationType opType = streamAnn != null ? OperationType.STREAM
                        : queryAnn != null ? OperationType.QUERY : OperationType.MUTATION;
                AnnotationInstance descAnn = streamAnn != null ? streamAnn : queryAnn != null ? queryAnn : mutAnn;
                String desc = descAnn.value() != null ? descAnn.value().asString() : "";

                String restMethodOverride = extractStringAnnotation(method, REST_METHOD_ANN);
                String restPathOverride = extractStringAnnotation(method, REST_PATH_ANN);

                int restStatusOverride = -1;
                AnnotationInstance restStatusAnn = method.annotation(REST_STATUS_ANN);
                if (restStatusAnn != null && restStatusAnn.value() != null) {
                    restStatusOverride = restStatusAnn.value().asInt();
                }

                List<String> rolesAllowed = List.of();
                AnnotationInstance raAnn = method.annotation(ROLES_ALLOWED_ANN);
                if (raAnn != null && raAnn.value() != null) {
                    rolesAllowed = List.of(raAnn.value().asStringArray());
                }

                boolean paginated = method.hasAnnotation(PAGINATED_ANN);
                String totalCountMethod = "totalCount";
                if (paginated) {
                    AnnotationInstance pgAnn = method.annotation(PAGINATED_ANN);
                    if (pgAnn != null && pgAnn.value("totalCountMethod") != null) {
                        totalCountMethod = pgAnn.value("totalCountMethod").asString();
                    }
                }

                Set<String> typeImports = new HashSet<>();
                String returnTypeStr = typeToJava(method.returnType());
                TypeName returnTypeName = JandexTypeConverter.toTypeName(method.returnType());
                addTypeImport(typeImports, method.returnType());

                List<ResolvedParam> params = new ArrayList<>();
                for (int i = 0; i < method.parameterTypes().size(); i++) {
                    Type paramType = method.parameterTypes().get(i);
                    String paramName = method.parameterName(i) != null ? method.parameterName(i) : "arg" + i;
                    String typeStr = typeToJava(paramType);
                    String typeFqcn = paramType.name().toString();
                    TypeName paramTypeName = JandexTypeConverter.toTypeName(paramType);
                    addTypeImport(typeImports, paramType);

                    AnnotationInstance ppAnn = findParameterAnnotation(method, i, PATH_PARAM_ANN);
                    boolean isPathParam = ppAnn != null;
                    String pathParamName = null;
                    if (ppAnn != null && ppAnn.value() != null && !ppAnn.value().asString().isEmpty()) {
                        pathParamName = ppAnn.value().asString();
                    }

                    AnnotationInstance rnAnn = findParameterAnnotation(method, i, REST_NAME_ANN);
                    String restName = (rnAnn != null && rnAnn.value() != null) ? rnAnn.value().asString() : null;

                    AnnotationInstance cpAnn = findParameterAnnotation(method, i, CONTEXT_PARAM_ANN);
                    boolean isContextParam = cpAnn != null;
                    String contextParamKey = (cpAnn != null && cpAnn.value() != null) ? cpAnn.value().asString() : null;

                    boolean simple = GeneratorUtils.isSimpleType(typeFqcn, index);
                    params.add(new ResolvedParam(paramName, typeStr, typeFqcn, paramTypeName, isPathParam, pathParamName, simple, restName, isContextParam, contextParamKey));
                }

                typeImports.add(classInfo.name().toString());

                domain.operations().add(new ResolvedOperation(
                        method.name(), returnTypeStr, returnTypeName, params, typeImports,
                        classInfo.name().toString(), classInfo.simpleName(),
                        opType, desc, restMethodOverride, restPathOverride, restStatusOverride,
                        rolesAllowed, paginated, totalCountMethod));
            }
        }

        return new ArrayList<>(domains.values());
    }

    private String extractStringAnnotation(MethodInfo method, DotName annotationName) {
        AnnotationInstance ann = method.annotation(annotationName);
        if (ann != null && ann.value() != null) {
            String val = ann.value().asString();
            return val.isEmpty() ? null : val;
        }
        return null;
    }

    private static AnnotationInstance findParameterAnnotation(MethodInfo method, int paramIndex, DotName annotationName) {
        for (AnnotationInstance ann : method.annotations()) {
            if (ann.target().kind() == AnnotationTarget.Kind.METHOD_PARAMETER
                && ann.target().asMethodParameter().position() == paramIndex
                && ann.name().equals(annotationName)) {
                return ann;
            }
        }
        return null;
    }

    private String typeToJava(Type type) {
        return switch (type.kind()) {
            case VOID -> "void";
            case PRIMITIVE -> type.asPrimitiveType().primitive().name().toLowerCase();
            case CLASS -> type.asClassType().name().local();
            case PARAMETERIZED_TYPE -> {
                StringBuilder sb = new StringBuilder(type.asParameterizedType().name().local());
                sb.append("<");
                List<Type> args = type.asParameterizedType().arguments();
                for (int i = 0; i < args.size(); i++) {
                    if (i > 0) { sb.append(", "); }
                    sb.append(typeToJava(args.get(i)));
                }
                sb.append(">");
                yield sb.toString();
            }
            case ARRAY -> typeToJava(type.asArrayType().constituent()) + "[]";
            default -> type.name().toString();
        };
    }

    private void addTypeImport(Set<String> imports, Type type) {
        switch (type.kind()) {
            case CLASS -> imports.add(type.name().toString());
            case PARAMETERIZED_TYPE -> {
                imports.add(type.asParameterizedType().name().toString());
                for (Type arg : type.asParameterizedType().arguments()) {
                    addTypeImport(imports, arg);
                }
            }
            case ARRAY -> addTypeImport(imports, type.asArrayType().constituent());
            default -> {}
        }
    }
}
