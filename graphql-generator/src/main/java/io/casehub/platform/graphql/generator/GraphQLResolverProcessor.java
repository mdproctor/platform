package io.casehub.platform.graphql.generator;

import org.jboss.jandex.AnnotationInstance;
import org.jboss.jandex.AnnotationTarget;
import org.jboss.jandex.ClassInfo;
import org.jboss.jandex.CompositeIndex;
import org.jboss.jandex.DotName;
import org.jboss.jandex.IndexReader;
import org.jboss.jandex.IndexView;
import org.jboss.jandex.MethodInfo;
import org.jboss.jandex.Type;

import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.RoundEnvironment;
import javax.annotation.processing.SupportedAnnotationTypes;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.TypeElement;
import javax.tools.Diagnostic;
import javax.tools.JavaFileObject;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintWriter;
import java.net.URL;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@SupportedAnnotationTypes("*")
public class GraphQLResolverProcessor extends AbstractProcessor {
    @Override
    public SourceVersion getSupportedSourceVersion() {
        return SourceVersion.latestSupported();
    }

    @Override
    public Set<String> getSupportedOptions() {
        return Set.of("generateGraphQL", "generateRest", "domainFilter",
                      "restPackage", "graphqlPackage");
    }


    private static final DotName MCP_DOMAIN        = DotName.createSimple("io.casehub.platform.api.mcp.McpDomain");
    private static final DotName PLATFORM_QUERY    = DotName.createSimple("io.casehub.platform.api.mcp.PlatformQuery");
    private static final DotName PLATFORM_MUTATION = DotName.createSimple("io.casehub.platform.api.mcp.PlatformMutation");
    private static final DotName PLATFORM_STREAM   = DotName.createSimple("io.casehub.platform.api.mcp.PlatformStream");
    private static final DotName PLATFORM_WEBHOOK  = DotName.createSimple("io.casehub.platform.api.mcp.PlatformWebhook");
    private static final DotName HEADER_PARAM_ANN  = DotName.createSimple("io.casehub.platform.api.mcp.HeaderParam");
    private static final DotName QUERY_PARAM_ANN   = DotName.createSimple("io.casehub.platform.api.mcp.QueryParam");
    private static final DotName PERMIT_ALL_ANN    = DotName.createSimple("jakarta.annotation.security.PermitAll");
    private static final DotName NAME_BINDING_ANN  = DotName.createSimple("jakarta.ws.rs.NameBinding");

    private static final DotName GRAPHQL_API       = DotName.createSimple("org.eclipse.microprofile.graphql.GraphQLApi");
    private static final DotName QUERY             = DotName.createSimple("org.eclipse.microprofile.graphql.Query");
    private static final DotName MUTATION          = DotName.createSimple("org.eclipse.microprofile.graphql.Mutation");
    private static final DotName REST_METHOD_ANN   = DotName.createSimple("io.casehub.platform.api.mcp.RestMethod");
    private static final DotName PATH_PARAM_ANN    = DotName.createSimple("io.casehub.platform.api.mcp.PathParam");
    private static final DotName PATH              = DotName.createSimple("jakarta.ws.rs.Path");
    private static final DotName JAX_GET           = DotName.createSimple("jakarta.ws.rs.GET");
    private static final DotName JAX_POST          = DotName.createSimple("jakarta.ws.rs.POST");
    private static final DotName JAX_PUT           = DotName.createSimple("jakarta.ws.rs.PUT");
    private static final DotName JAX_DELETE        = DotName.createSimple("jakarta.ws.rs.DELETE");
    private static final DotName JAX_PATCH         = DotName.createSimple("jakarta.ws.rs.PATCH");
    private static final DotName REST_PATH_ANN     = DotName.createSimple("io.casehub.platform.api.mcp.RestPath");
    private static final DotName REST_STATUS_ANN   = DotName.createSimple("io.casehub.platform.api.mcp.RestStatus");
    private static final DotName REST_NAME_ANN     = DotName.createSimple("io.casehub.platform.api.mcp.RestName");
    private static final DotName CONTEXT_PARAM_ANN = DotName.createSimple("io.casehub.platform.api.mcp.ContextParam");
    private static final DotName ROLES_ALLOWED_ANN = DotName.createSimple("jakarta.annotation.security.RolesAllowed");
    private static final DotName PAGINATED_ANN     = DotName.createSimple("io.casehub.platform.api.mcp.PaginatedResponse");
    private static final DotName VALID_ANN         = DotName.createSimple("jakarta.validation.Valid");
    private static final DotName DEFAULT_VALUE_ANN = DotName.createSimple("io.casehub.platform.api.mcp.DefaultValue");
    private static final DotName HAND_WRITTEN_ANN  = DotName.createSimple("io.casehub.platform.api.mcp.HandWrittenEndpoint");
    private static final DotName UNLESS_PROFILE    = DotName.createSimple("io.quarkus.arc.profile.UnlessBuildProfile");


    private boolean   processed = false;
    private IndexView jandexIndex;

    @Override
    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
        if (processed || roundEnv.processingOver()) {
            return false;
        }
        processed = true;

        processingEnv.getMessager().printMessage(Diagnostic.Kind.NOTE,
                                                 "GraphQL generator: options received: " + processingEnv.getOptions());

        IndexView index = loadCombinedIndex();
        this.jandexIndex = index;

        Set<String> graphqlSkipMethods = scanHandWrittenGraphQLMethods(index, roundEnv);
        Set<String> restSkipMethods    = scanHandWrittenRestMethods(index, roundEnv);

        Map<String, DomainOperations> jandexDomains =
                index != null ? scanAnnotatedDomains(index) : new HashMap<>();
        Map<String, DomainOperations> roundEnvDomains = scanRoundEnvironment(roundEnv);

        Map<String, DomainOperations> allDomains = new HashMap<>(roundEnvDomains);
        allDomains.putAll(jandexDomains);

        if (allDomains.isEmpty()) {
            return false;
        }

        for (var entry : allDomains.entrySet()) {
            processingEnv.getMessager().printMessage(Diagnostic.Kind.NOTE,
                                                     "GraphQL generator: found domain '" + entry.getKey()
                                                     + "' (" + entry.getValue().operations.size() + " operations)"
                                                     + " [source: " + entry.getValue().source + "]");
        }

        boolean generateGraphQL = !"false".equals(processingEnv.getOptions().get("generateGraphQL"));
        boolean generateRest    = !"false".equals(processingEnv.getOptions().get("generateRest"));
        String  domainFilter    = processingEnv.getOptions().get("domainFilter");
        Set<String> allowedDomains = domainFilter != null
                                     ? java.util.Arrays.stream(domainFilter.split(",")).map(String::trim)
                                                       .collect(java.util.stream.Collectors.toSet())
                                     : null;

        if (!generateGraphQL) {
            processingEnv.getMessager().printMessage(Diagnostic.Kind.NOTE,
                                                     "GraphQL generator: GraphQL resolver generation disabled (generateGraphQL=false)");
        }
        if (!generateRest) {
            processingEnv.getMessager().printMessage(Diagnostic.Kind.NOTE,
                                                     "GraphQL generator: REST resource generation disabled (generateRest=false)");
        }

        for (var entry : allDomains.entrySet()) {
            if (allowedDomains != null && !allowedDomains.contains(entry.getKey())) {
                processingEnv.getMessager().printMessage(Diagnostic.Kind.NOTE,
                                                         "GraphQL generator: skipping domain '" + entry.getKey()
                                                         + "' — not in domainFilter");
                continue;
            }
            if (generateGraphQL) {
                generateResolverSource(entry.getKey(), entry.getValue(), graphqlSkipMethods);
            }
            if (generateRest) {
                generateRestResourceSource(entry.getKey(), entry.getValue(), restSkipMethods);
            }
        }

        if (allowedDomains != null) {
            long generatedCount = allDomains.keySet().stream()
                                            .filter(allowedDomains::contains).count();
            if (generatedCount == 0) {
                processingEnv.getMessager().printMessage(Diagnostic.Kind.WARNING,
                                                         "GraphQL generator: domainFilter=" + domainFilter
                                                         + " matched zero domains. Available domains: " + allDomains.keySet());
            }
        } else if (allDomains.size() > 1) {
            processingEnv.getMessager().printMessage(Diagnostic.Kind.NOTE,
                                                     "GraphQL generator: no domainFilter set — generating for all "
                                                     + allDomains.size() + " domains. Set -AdomainFilter=... to restrict.");
        }

        enforceNoHandWrittenRest(roundEnv);

        return false;
    }

    private void enforceNoHandWrittenRest(RoundEnvironment roundEnv) {
        var pathType = processingEnv.getElementUtils().getTypeElement("jakarta.ws.rs.Path");
        if (pathType == null) return;

        var violations = new ArrayList<String>();
        for (var element : roundEnv.getElementsAnnotatedWith(pathType)) {
            if (element.getKind() != javax.lang.model.element.ElementKind.CLASS) continue;
            String name = ((TypeElement) element).getQualifiedName().toString();

            var mcpDomain = element.getAnnotation(
                    processingEnv.getElementUtils().getTypeElement(
                            "io.casehub.platform.api.mcp.McpDomain") != null
                    ? loadAnnotationClass("io.casehub.platform.api.mcp.McpDomain") : null);

            boolean hasMcpDomain = element.getAnnotationMirrors().stream()
                    .anyMatch(a -> a.getAnnotationType().toString().endsWith("McpDomain"));
            boolean hasHandWritten = element.getAnnotationMirrors().stream()
                    .anyMatch(a -> a.getAnnotationType().toString().endsWith("HandWrittenEndpoint"));
            boolean hasUnlessProfile = element.getAnnotationMirrors().stream()
                    .anyMatch(a -> a.getAnnotationType().toString().endsWith("UnlessBuildProfile"));

            if (!hasMcpDomain && !hasHandWritten && !hasUnlessProfile) {
                violations.add(name);
            }
        }

        for (var v : violations) {
            processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR,
                    "Hand-written @Path on " + v
                    + " — use @McpDomain for tri-channel REST/GraphQL/MCP parity. "
                    + "If this endpoint must stay hand-written (webhook, SSE, auth), "
                    + "annotate with @HandWrittenEndpoint(\"reason\").");
        }
    }

    @SuppressWarnings("unchecked")
    private static <A extends java.lang.annotation.Annotation> Class<A> loadAnnotationClass(String name) {
        try { return (Class<A>) Class.forName(name); }
        catch (ClassNotFoundException e) { return null; }
    }

    private IndexView loadCombinedIndex() {
        List<IndexView> indexes = new ArrayList<>();
        try {
            ClassLoader      cl        = getClass().getClassLoader();
            Enumeration<URL> resources = cl.getResources("META-INF/jandex.idx");
            while (resources.hasMoreElements()) {
                URL url = resources.nextElement();
                try (InputStream is = url.openStream()) {
                    indexes.add(new IndexReader(is).read());
                }
            }
        } catch (IOException e) {
            processingEnv.getMessager().printMessage(Diagnostic.Kind.WARNING,
                                                     "GraphQL generator: failed to read Jandex indexes: " + e.getMessage());
            return null;
        }

        if (indexes.isEmpty()) {
            return null;
        }

        processingEnv.getMessager().printMessage(Diagnostic.Kind.NOTE,
                                                 "GraphQL generator: loaded " + indexes.size() + " Jandex index(es)");
        return CompositeIndex.create(indexes);
    }

    private Set<String> scanHandWrittenGraphQLMethods(IndexView index, RoundEnvironment roundEnv) {
        Set<String> methods = new HashSet<>();
        if (index != null) {
            for (AnnotationInstance ann : index.getAnnotations(GRAPHQL_API)) {
                if (ann.target().kind() != AnnotationTarget.Kind.CLASS) {continue;}
                ClassInfo          classInfo = ann.target().asClass();
                AnnotationInstance mcpDomain = classInfo.annotation(MCP_DOMAIN);
                if (mcpDomain == null) {continue;}
                String domain = mcpDomain.value().asString();
                for (MethodInfo method : classInfo.methods()) {
                    if (method.hasAnnotation(QUERY) || method.hasAnnotation(MUTATION)) {
                        methods.add(domain + ":" + method.name());
                    }
                }
            }
        }
        for (javax.lang.model.element.Element element : roundEnv.getRootElements()) {
            if (element.getKind() != javax.lang.model.element.ElementKind.CLASS) {continue;}
            javax.lang.model.element.AnnotationMirror graphqlApi = findAnnotationMirror(element,
                                                                                        "org.eclipse.microprofile.graphql.GraphQLApi");
            if (graphqlApi == null) {continue;}
            javax.lang.model.element.AnnotationMirror mcpDomain = findAnnotationMirror(element,
                                                                                       "io.casehub.platform.api.mcp.McpDomain");
            if (mcpDomain == null) {continue;}
            String domain = extractAnnotationStringValue(mcpDomain);
            for (javax.lang.model.element.Element enclosed : element.getEnclosedElements()) {
                if (enclosed.getKind() != javax.lang.model.element.ElementKind.METHOD) {continue;}
                if (findAnnotationMirror(enclosed, "org.eclipse.microprofile.graphql.Query") != null
                    || findAnnotationMirror(enclosed, "org.eclipse.microprofile.graphql.Mutation") != null) {
                    methods.add(domain + ":" + enclosed.getSimpleName().toString());
                }
            }
        }
        return methods;
    }

    private Set<String> scanHandWrittenRestMethods(IndexView index, RoundEnvironment roundEnv) {
        Set<String> methods = new HashSet<>();
        if (index != null) {
            for (AnnotationInstance pathAnn : index.getAnnotations(PATH)) {
                if (pathAnn.target().kind() != AnnotationTarget.Kind.CLASS) {continue;}
                ClassInfo          classInfo = pathAnn.target().asClass();
                AnnotationInstance mcpDomain = classInfo.annotation(MCP_DOMAIN);
                if (mcpDomain == null) {continue;}
                String domain = mcpDomain.value().asString();
                for (MethodInfo method : classInfo.methods()) {
                    if (method.hasAnnotation(JAX_GET) || method.hasAnnotation(JAX_POST)
                        || method.hasAnnotation(JAX_PUT) || method.hasAnnotation(JAX_DELETE)
                        || method.hasAnnotation(JAX_PATCH)) {
                        methods.add(domain + ":" + method.name());
                    }
                }
            }
        }
        for (javax.lang.model.element.Element element : roundEnv.getRootElements()) {
            if (element.getKind() != javax.lang.model.element.ElementKind.CLASS) {continue;}
            javax.lang.model.element.AnnotationMirror pathAnn = findAnnotationMirror(element,
                                                                                     "jakarta.ws.rs.Path");
            if (pathAnn == null) {continue;}
            javax.lang.model.element.AnnotationMirror mcpDomain = findAnnotationMirror(element,
                                                                                       "io.casehub.platform.api.mcp.McpDomain");
            if (mcpDomain == null) {continue;}
            String domain = extractAnnotationStringValue(mcpDomain);
            for (javax.lang.model.element.Element enclosed : element.getEnclosedElements()) {
                if (enclosed.getKind() != javax.lang.model.element.ElementKind.METHOD) {continue;}
                if (findAnnotationMirror(enclosed, "jakarta.ws.rs.GET") != null
                    || findAnnotationMirror(enclosed, "jakarta.ws.rs.POST") != null
                    || findAnnotationMirror(enclosed, "jakarta.ws.rs.PUT") != null
                    || findAnnotationMirror(enclosed, "jakarta.ws.rs.DELETE") != null
                    || findAnnotationMirror(enclosed, "jakarta.ws.rs.PATCH") != null) {
                    methods.add(domain + ":" + enclosed.getSimpleName().toString());
                }
            }
        }
        return methods;
    }


    private Map<String, DomainOperations> scanAnnotatedDomains(IndexView index) {
        Map<String, DomainOperations> domains = new HashMap<>();

        for (AnnotationInstance ann : index.getAnnotations(MCP_DOMAIN)) {
            if (ann.target().kind() != AnnotationTarget.Kind.CLASS) {continue;}
            ClassInfo classInfo = ann.target().asClass();

            String domain = ann.value().asString();
            DomainOperations ops = domains.computeIfAbsent(domain,
                                                           d -> new DomainOperations(d, Source.JANDEX));
            if (ann.value("basePath") != null && !ann.value("basePath").asString().isEmpty()) {
                ops.basePath = ann.value("basePath").asString();
            }

            for (MethodInfo method : classInfo.methods()) {
                AnnotationInstance queryAnn   = method.annotation(PLATFORM_QUERY);
                AnnotationInstance mutAnn     = method.annotation(PLATFORM_MUTATION);
                AnnotationInstance streamAnn  = method.annotation(PLATFORM_STREAM);
                AnnotationInstance webhookAnn = method.annotation(PLATFORM_WEBHOOK);

                if (queryAnn != null || mutAnn != null || streamAnn != null || webhookAnn != null) {
                    OperationType opType = webhookAnn != null ? OperationType.WEBHOOK
                                         : streamAnn != null ? OperationType.STREAM
                                         : queryAnn != null ? OperationType.QUERY : OperationType.MUTATION;
                    AnnotationInstance descAnn = webhookAnn != null ? webhookAnn : streamAnn != null ? streamAnn : queryAnn != null ? queryAnn : mutAnn;
                    String desc = descAnn.value() != null ? descAnn.value().asString() : "";
                    String             restMethodOverride = null;
                    AnnotationInstance restMethodAnn      = method.annotation(REST_METHOD_ANN);
                    if (restMethodAnn != null && restMethodAnn.value() != null) {
                        restMethodOverride = restMethodAnn.value().asEnum();
                    }
                    String             restPathOverride = null;
                    AnnotationInstance restPathAnn      = method.annotation(REST_PATH_ANN);
                    if (restPathAnn != null && restPathAnn.value() != null) {
                        restPathOverride = restPathAnn.value().asString();
                    }
                    int restStatusOverride = -1;
                    AnnotationInstance restStatusAnn = method.annotation(REST_STATUS_ANN);
                    if (restStatusAnn != null && restStatusAnn.value() != null) {
                        restStatusOverride = restStatusAnn.value().asInt();
                    }
                    ops.operations.add(resolveFromJandex(method, classInfo, opType, desc, restMethodOverride, restPathOverride, restStatusOverride));
                }
            }
        }

        processingEnv.getMessager().printMessage(Diagnostic.Kind.NOTE,
                                                 "GraphQL generator: scanned " + domains.size() + " domain(s)");
        return domains;
    }

    private ResolvedOperation resolveFromJandex(MethodInfo method, ClassInfo declaringClass,
                                                OperationType opType, String description,
                                                String restMethodOverride, String restPathOverride,
                                                int restStatusOverride) {
        String      returnTypeStr = typeToJava(method.returnType());
        Set<String> imports       = new HashSet<>();
        addTypeImport(imports, method.returnType());

        List<ResolvedParam> params = new ArrayList<>();
        for (int i = 0; i < method.parameterTypes().size(); i++) {
            Type   paramType = method.parameterTypes().get(i);
            String paramName = method.parameterName(i) != null ? method.parameterName(i) : "arg" + i;
            String typeStr   = typeToJava(paramType);
            String typeFqcn  = paramType.name().toString();
            addTypeImport(imports, paramType);

            AnnotationInstance ppAnn         = findParameterAnnotation(method, i, PATH_PARAM_ANN);
            boolean            isPathParam   = ppAnn != null;
            String             pathParamName = null;
            if (ppAnn != null && ppAnn.value() != null && !ppAnn.value().asString().isEmpty()) {
                pathParamName = ppAnn.value().asString();
            }

            AnnotationInstance rnAnn    = findParameterAnnotation(method, i, REST_NAME_ANN);
            String             restName = (rnAnn != null && rnAnn.value() != null) ? rnAnn.value().asString() : null;

            AnnotationInstance cpAnn           = findParameterAnnotation(method, i, CONTEXT_PARAM_ANN);
            boolean            isContextParam  = cpAnn != null;
            String             contextParamKey = (cpAnn != null && cpAnn.value() != null) ? cpAnn.value().asString() : null;

            boolean hasValid = findParameterAnnotation(method, i, VALID_ANN) != null;

            AnnotationInstance dvAnn        = findParameterAnnotation(method, i, DEFAULT_VALUE_ANN);
            String             defaultValue = (dvAnn != null && dvAnn.value() != null) ? dvAnn.value().asString() : null;

            AnnotationInstance hpAnn           = findParameterAnnotation(method, i, HEADER_PARAM_ANN);
            boolean            isHeaderParam   = hpAnn != null;
            String             headerParamName = (hpAnn != null && hpAnn.value() != null) ? hpAnn.value().asString() : null;

            AnnotationInstance qpAnn          = findParameterAnnotation(method, i, QUERY_PARAM_ANN);
            boolean            isQueryParam   = qpAnn != null;
            String             queryParamName = (qpAnn != null && qpAnn.value() != null) ? qpAnn.value().asString() : null;

            boolean simple = isSimpleTypeJandex(paramType, jandexIndex);
            params.add(new ResolvedParam(paramName, typeStr, typeFqcn, isPathParam, pathParamName, simple, restName, isContextParam, contextParamKey, hasValid, defaultValue, isHeaderParam, headerParamName, isQueryParam, queryParamName));
        }

        imports.add(declaringClass.name().toString());

        List<String>       rolesAllowed = List.of();
        AnnotationInstance raAnn        = method.annotation(ROLES_ALLOWED_ANN);
        if (raAnn != null && raAnn.value() != null) {
            rolesAllowed = List.of(raAnn.value().asStringArray());
        }

        boolean permitAll = method.hasAnnotation(PERMIT_ALL_ANN);

        List<String> passedAnnotations = new ArrayList<>();
        for (AnnotationInstance ann : method.annotations()) {
            if (ann.target() != null && ann.target().kind() == AnnotationTarget.Kind.METHOD_PARAMETER) {continue;}
            String annName = ann.name().toString();
            if (annName.startsWith("jakarta.annotation.security.") && !annName.endsWith("RolesAllowed") && !annName.endsWith("PermitAll")) {
                passedAnnotations.add("@" + annName);
            }
            if (jandexIndex != null) {
                ClassInfo annClass = jandexIndex.getClassByName(ann.name());
                if (annClass != null && annClass.hasAnnotation(NAME_BINDING_ANN)) {
                    passedAnnotations.add("@" + annName);
                }
            }
        }

        boolean paginated        = method.hasAnnotation(PAGINATED_ANN);
        String  totalCountMethod = "totalCount";
        if (paginated) {
            AnnotationInstance pgAnn = method.annotation(PAGINATED_ANN);
            if (pgAnn != null && pgAnn.value("totalCountMethod") != null) {
                totalCountMethod = pgAnn.value("totalCountMethod").asString();
            }
        }

        String[] webhookConsumes = null;
        if (opType == OperationType.WEBHOOK) {
            AnnotationInstance whAnn = method.annotation(PLATFORM_WEBHOOK);
            if (whAnn != null && whAnn.value("consumes") != null) {
                webhookConsumes = whAnn.value("consumes").asStringArray();
            }
            if (webhookConsumes == null) {
                webhookConsumes = new String[]{"application/json"};
            }
        }

        return new ResolvedOperation(
                method.name(), returnTypeStr, params, imports,
                declaringClass.name().toString(), declaringClass.simpleName(),
                opType, description, restMethodOverride, restPathOverride,
                restStatusOverride, rolesAllowed, paginated, totalCountMethod,
                webhookConsumes, permitAll, passedAnnotations
        );
    }

    private javax.lang.model.element.AnnotationMirror findAnnotationMirror(
            javax.lang.model.element.Element element, String annotationFqcn) {
        for (javax.lang.model.element.AnnotationMirror am : element.getAnnotationMirrors()) {
            if (am.getAnnotationType().toString().equals(annotationFqcn)) {
                return am;
            }
        }
        return null;
    }

    private String extractAnnotationStringValue(javax.lang.model.element.AnnotationMirror am) {
        return extractAnnotationAttribute(am, "value");
    }

    private String extractAnnotationAttribute(javax.lang.model.element.AnnotationMirror am, String attributeName) {
        for (var entry : am.getElementValues().entrySet()) {
            if (entry.getKey().getSimpleName().contentEquals(attributeName)) {
                return entry.getValue().getValue().toString();
            }
        }
        return "";
    }

    private String typeMirrorToJava(javax.lang.model.type.TypeMirror type) {
        return switch (type.getKind()) {
            case VOID -> "void";
            case BOOLEAN -> "boolean";
            case BYTE -> "byte";
            case SHORT -> "short";
            case INT -> "int";
            case LONG -> "long";
            case FLOAT -> "float";
            case DOUBLE -> "double";
            case CHAR -> "char";
            case DECLARED -> {
                javax.lang.model.type.DeclaredType dt     = (javax.lang.model.type.DeclaredType) type;
                String                             simple = ((javax.lang.model.element.TypeElement) dt.asElement()).getSimpleName().toString();
                if (dt.getTypeArguments().isEmpty()) {
                    yield simple;
                }
                StringBuilder sb = new StringBuilder(simple).append("<");
                for (int i = 0; i < dt.getTypeArguments().size(); i++) {
                    if (i > 0) {sb.append(", ");}
                    sb.append(typeMirrorToJava(dt.getTypeArguments().get(i)));
                }
                sb.append(">");
                yield sb.toString();
            }
            case ARRAY -> typeMirrorToJava(((javax.lang.model.type.ArrayType) type).getComponentType()) + "[]";
            default -> type.toString();
        };
    }

    private void collectTypeMirrorImports(Set<String> imports, javax.lang.model.type.TypeMirror type) {
        if (type.getKind() == javax.lang.model.type.TypeKind.DECLARED) {
            javax.lang.model.type.DeclaredType dt   = (javax.lang.model.type.DeclaredType) type;
            String                             fqcn = ((javax.lang.model.element.TypeElement) dt.asElement()).getQualifiedName().toString();
            imports.add(fqcn);
            for (javax.lang.model.type.TypeMirror arg : dt.getTypeArguments()) {
                collectTypeMirrorImports(imports, arg);
            }
        } else if (type.getKind() == javax.lang.model.type.TypeKind.ARRAY) {
            collectTypeMirrorImports(imports, ((javax.lang.model.type.ArrayType) type).getComponentType());
        }
    }

    private boolean hasVisibleCdiScope(javax.lang.model.element.Element element) {
        for (String scopeAnnotation : List.of(
                "jakarta.enterprise.context.ApplicationScoped",
                "jakarta.enterprise.context.RequestScoped",
                "jakarta.enterprise.context.SessionScoped",
                "jakarta.enterprise.context.Dependent",
                "jakarta.inject.Singleton")) {
            if (findAnnotationMirror(element, scopeAnnotation) != null) {
                return true;
            }
        }
        return false;
    }

    private boolean isSimpleTypeMirror(javax.lang.model.type.TypeMirror type) {
        if (type.getKind().isPrimitive()) {return true;}
        if (type.getKind() != javax.lang.model.type.TypeKind.DECLARED) {return false;}
        javax.lang.model.type.DeclaredType declared = (javax.lang.model.type.DeclaredType) type;
        String fqcn = ((javax.lang.model.element.TypeElement) declared.asElement()).getQualifiedName().toString();
        if (COLLECTION_TYPES.contains(fqcn)) {
            if (declared.getTypeArguments().isEmpty()) {return true;}
            return declared.getTypeArguments().stream().allMatch(this::isSimpleTypeMirror);
        }
        if (isSimpleType(fqcn, jandexIndex)) {return true;}
        javax.lang.model.element.Element element = declared.asElement();
        if (element.getKind() == javax.lang.model.element.ElementKind.ENUM) {return true;}
        for (javax.lang.model.element.Element enclosed : element.getEnclosedElements()) {
            if (enclosed.getKind() == javax.lang.model.element.ElementKind.METHOD) {
                javax.lang.model.element.ExecutableElement method =
                        (javax.lang.model.element.ExecutableElement) enclosed;
                if (method.getModifiers().contains(javax.lang.model.element.Modifier.STATIC)
                    && method.getParameters().size() == 1
                    && method.getParameters().get(0).asType().toString().equals("java.lang.String")
                    && (method.getSimpleName().contentEquals("fromString")
                        || method.getSimpleName().contentEquals("valueOf"))) {
                    return true;
                }
            }
        }
        return false;
    }

    private Map<String, DomainOperations> scanRoundEnvironment(RoundEnvironment roundEnv) {
        Map<String, DomainOperations> domains = new HashMap<>();

        Set<? extends javax.lang.model.element.Element> annotated;
        try {
            annotated = roundEnv.getElementsAnnotatedWith(
                    io.casehub.platform.api.mcp.McpDomain.class);
        } catch (IllegalArgumentException | TypeNotPresentException e) {
            processingEnv.getMessager().printMessage(Diagnostic.Kind.NOTE,
                                                     "GraphQL generator: RoundEnv scan skipped — McpDomain annotation not loadable: " + e.getMessage());
            return domains;
        }

        for (javax.lang.model.element.Element element : annotated) {
            if (element.getKind() != javax.lang.model.element.ElementKind.INTERFACE
                && element.getKind() != javax.lang.model.element.ElementKind.CLASS) {continue;}
            javax.lang.model.element.TypeElement typeElement =
                    (javax.lang.model.element.TypeElement) element;

            if (element.getKind() == javax.lang.model.element.ElementKind.CLASS) {
                if (!hasVisibleCdiScope(element)) {
                    processingEnv.getMessager().printMessage(Diagnostic.Kind.WARNING,
                            "@McpDomain on class " + typeElement.getQualifiedName()
                            + " without a visible CDI scope annotation. "
                            + "The class must be a CDI bean at runtime. "
                            + "Note: this check cannot see inherited scopes, stereotypes, "
                            + "or scopes added by CDI extensions.",
                            element);
                }
            }

            javax.lang.model.element.AnnotationMirror mcpAnn = findAnnotationMirror(element,
                                                                                    "io.casehub.platform.api.mcp.McpDomain");
            if (mcpAnn == null) {continue;}
            String domain = extractAnnotationStringValue(mcpAnn);
            if (domain.isEmpty()) {continue;}

            DomainOperations ops = domains.computeIfAbsent(domain,
                                                           d -> new DomainOperations(d, Source.ROUND_ENV));
            String roundBasePath = extractAnnotationAttribute(mcpAnn, "basePath");
            if (roundBasePath != null && !roundBasePath.isEmpty()) {
                ops.basePath = roundBasePath;
            }

            for (javax.lang.model.element.Element enclosed : typeElement.getEnclosedElements()) {
                if (enclosed.getKind() != javax.lang.model.element.ElementKind.METHOD) {continue;}
                javax.lang.model.element.ExecutableElement method =
                        (javax.lang.model.element.ExecutableElement) enclosed;

                javax.lang.model.element.AnnotationMirror queryAnn = findAnnotationMirror(method,
                                                                                          "io.casehub.platform.api.mcp.PlatformQuery");
                javax.lang.model.element.AnnotationMirror mutAnn = findAnnotationMirror(method,
                                                                                        "io.casehub.platform.api.mcp.PlatformMutation");
                javax.lang.model.element.AnnotationMirror streamAnn = findAnnotationMirror(method,
                                                                                            "io.casehub.platform.api.mcp.PlatformStream");
                javax.lang.model.element.AnnotationMirror webhookAnn = findAnnotationMirror(method,
                                                                                             "io.casehub.platform.api.mcp.PlatformWebhook");
                if (queryAnn == null && mutAnn == null && streamAnn == null && webhookAnn == null) {continue;}

                OperationType opType = webhookAnn != null ? OperationType.WEBHOOK
                                     : streamAnn != null ? OperationType.STREAM
                                     : queryAnn != null ? OperationType.QUERY : OperationType.MUTATION;
                javax.lang.model.element.AnnotationMirror descAnn = webhookAnn != null ? webhookAnn : streamAnn != null ? streamAnn : queryAnn != null ? queryAnn : mutAnn;
                String desc = extractAnnotationStringValue(descAnn);

                String restMethodOverride = null;
                javax.lang.model.element.AnnotationMirror restMethodAnn = findAnnotationMirror(method,
                                                                                               "io.casehub.platform.api.mcp.RestMethod");
                if (restMethodAnn != null) {
                    restMethodOverride = extractAnnotationStringValue(restMethodAnn);
                }

                String restPathOverride = null;
                javax.lang.model.element.AnnotationMirror restPathAnn = findAnnotationMirror(method,
                                                                                             "io.casehub.platform.api.mcp.RestPath");
                if (restPathAnn != null) {
                    restPathOverride = extractAnnotationStringValue(restPathAnn);
                }

                int restStatusOverride = -1;
                javax.lang.model.element.AnnotationMirror restStatusAnn = findAnnotationMirror(method,
                                                                                                "io.casehub.platform.api.mcp.RestStatus");
                if (restStatusAnn != null) {
                    for (var e : restStatusAnn.getElementValues().entrySet()) {
                        if (e.getKey().getSimpleName().contentEquals("value")) {
                            restStatusOverride = (int) e.getValue().getValue();
                        }
                    }
                }

                String      returnTypeStr = typeMirrorToJava(method.getReturnType());
                Set<String> imports       = new HashSet<>();
                collectTypeMirrorImports(imports, method.getReturnType());

                List<ResolvedParam> params = new ArrayList<>();
                for (var param : method.getParameters()) {
                    String paramName = param.getSimpleName().toString();
                    String typeStr   = typeMirrorToJava(param.asType());
                    String typeFqcn = param.asType().getKind() == javax.lang.model.type.TypeKind.DECLARED
                                      ? ((javax.lang.model.element.TypeElement)
                                                 ((javax.lang.model.type.DeclaredType) param.asType()).asElement())
                                        .getQualifiedName().toString()
                                      : param.asType().toString();
                    collectTypeMirrorImports(imports, param.asType());

                    javax.lang.model.element.AnnotationMirror ppAnn = findAnnotationMirror(param,
                                                                                           "io.casehub.platform.api.mcp.PathParam");
                    boolean isPathParam   = ppAnn != null;
                    String  pathParamName = isPathParam ? extractAnnotationStringValue(ppAnn) : null;
                    if (pathParamName != null && pathParamName.isEmpty()) {pathParamName = null;}

                    javax.lang.model.element.AnnotationMirror rnAnn = findAnnotationMirror(param,
                                                                                            "io.casehub.platform.api.mcp.RestName");
                    String restName = rnAnn != null ? extractAnnotationStringValue(rnAnn) : null;
                    if (restName != null && restName.isEmpty()) {restName = null;}

                    javax.lang.model.element.AnnotationMirror cpAnn = findAnnotationMirror(param,
                                                                                            "io.casehub.platform.api.mcp.ContextParam");
                    boolean isContextParam = cpAnn != null;
                    String contextParamKey = isContextParam ? extractAnnotationStringValue(cpAnn) : null;

                    boolean hasValid = findAnnotationMirror(param, "jakarta.validation.Valid") != null;

                    javax.lang.model.element.AnnotationMirror dvAnn = findAnnotationMirror(param,
                                                                                            "io.casehub.platform.api.mcp.DefaultValue");
                    String defaultValue = dvAnn != null ? extractAnnotationStringValue(dvAnn) : null;

                    javax.lang.model.element.AnnotationMirror hpAnn = findAnnotationMirror(param,
                                                                                            "io.casehub.platform.api.mcp.HeaderParam");
                    boolean isHeaderParam = hpAnn != null;
                    String headerParamName = isHeaderParam ? extractAnnotationStringValue(hpAnn) : null;

                    javax.lang.model.element.AnnotationMirror qpAnn = findAnnotationMirror(param,
                                                                                            "io.casehub.platform.api.mcp.QueryParam");
                    boolean isQueryParam = qpAnn != null;
                    String queryParamName = isQueryParam ? extractAnnotationStringValue(qpAnn) : null;

                    boolean simple = isSimpleTypeMirror(param.asType());
                    params.add(new ResolvedParam(paramName, typeStr, typeFqcn, isPathParam, pathParamName, simple, restName, isContextParam, contextParamKey, hasValid, defaultValue, isHeaderParam, headerParamName, isQueryParam, queryParamName));
                }

                String classFqcn   = typeElement.getQualifiedName().toString();
                String classSimple = typeElement.getSimpleName().toString();
                imports.add(classFqcn);

                List<String> rolesAllowed = List.of();
                javax.lang.model.element.AnnotationMirror raAnn = findAnnotationMirror(method,
                                                                                        "jakarta.annotation.security.RolesAllowed");
                if (raAnn != null) {
                    for (var e : raAnn.getElementValues().entrySet()) {
                        if (e.getKey().getSimpleName().contentEquals("value")) {
                            @SuppressWarnings("unchecked")
                            var values = (java.util.List<? extends javax.lang.model.element.AnnotationValue>) e.getValue().getValue();
                            rolesAllowed = values.stream().map(v -> v.getValue().toString()).toList();
                        }
                    }
                }

                boolean paginated = findAnnotationMirror(method, "io.casehub.platform.api.mcp.PaginatedResponse") != null;
                String totalCountMethod = "totalCount";
                if (paginated) {
                    javax.lang.model.element.AnnotationMirror pgAnn = findAnnotationMirror(method, "io.casehub.platform.api.mcp.PaginatedResponse");
                    for (var e : pgAnn.getElementValues().entrySet()) {
                        if (e.getKey().getSimpleName().contentEquals("totalCountMethod")) {
                            totalCountMethod = e.getValue().getValue().toString();
                        }
                    }
                }

                boolean permitAll = findAnnotationMirror(method, "jakarta.annotation.security.PermitAll") != null;

                List<String> passedAnnotations = new ArrayList<>();
                for (var methodAnn : method.getAnnotationMirrors()) {
                    String annFqcn = ((javax.lang.model.element.TypeElement) methodAnn.getAnnotationType().asElement()).getQualifiedName().toString();
                    if (annFqcn.startsWith("jakarta.annotation.security.") && !annFqcn.endsWith("RolesAllowed") && !annFqcn.endsWith("PermitAll")) {
                        passedAnnotations.add("@" + annFqcn);
                    }
                    if (methodAnn.getAnnotationType().asElement().getAnnotationMirrors().stream()
                            .anyMatch(a -> ((javax.lang.model.element.TypeElement) a.getAnnotationType().asElement()).getQualifiedName().toString().equals("jakarta.ws.rs.NameBinding"))) {
                        passedAnnotations.add("@" + annFqcn);
                    }
                }

                String[] webhookConsumes = null;
                if (opType == OperationType.WEBHOOK) {
                    javax.lang.model.element.AnnotationMirror whAnn = findAnnotationMirror(method, "io.casehub.platform.api.mcp.PlatformWebhook");
                    if (whAnn != null) {
                        for (var e : whAnn.getElementValues().entrySet()) {
                            if (e.getKey().getSimpleName().contentEquals("consumes")) {
                                Object val = e.getValue().getValue();
                                if (val instanceof java.util.List<?> list) {
                                    webhookConsumes = list.stream()
                                            .map(v -> ((javax.lang.model.element.AnnotationValue) v).getValue().toString())
                                            .toArray(String[]::new);
                                } else {
                                    webhookConsumes = new String[]{val.toString()};
                                }
                            }
                        }
                    }
                    if (webhookConsumes == null) {
                        webhookConsumes = new String[]{"application/json"};
                    }
                }

                ops.operations.add(new ResolvedOperation(
                        method.getSimpleName().toString(), returnTypeStr, params, imports,
                        classFqcn, classSimple, opType, desc, restMethodOverride, restPathOverride,
                        restStatusOverride, rolesAllowed, paginated, totalCountMethod,
                        webhookConsumes, permitAll, passedAnnotations));
            }
        }

        processingEnv.getMessager().printMessage(Diagnostic.Kind.NOTE,
                                                 "GraphQL generator: RoundEnv scan found " + domains.size() + " domain(s)");
        return domains;
    }


    private void generateResolverSource(String domain, DomainOperations ops,
                                        Set<String> handWrittenMethods) {
        String className = toPascalCase(domain) + "Resolver";
        if (!SourceVersion.isIdentifier(className)) {
            processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR,
                                                     "GraphQL generator: domain '" + domain
                                                     + "' produces invalid class name '" + className
                                                     + "'. Domain names must contain only alphanumerics, hyphens, and slashes.");
            return;
        }
        String graphqlPkgOverride = processingEnv.getOptions().get("graphqlPackage");
        String packageName = graphqlPkgOverride != null
            ? graphqlPkgOverride
            : deriveOutputPackage(spiPackage(ops), "graphql");
        String fqcn        = packageName + "." + className;

        Set<String>             spiImports = new HashSet<>();
        List<ResolvedOperation> toGenerate = new ArrayList<>();

        for (ResolvedOperation op : ops.operations) {
            if (op.type() == OperationType.WEBHOOK) { continue; }
            if ("Response".equals(op.returnTypeStr()) || "jakarta.ws.rs.core.Response".equals(op.returnTypeStr())) { continue; }
            if (usesHttpContext(op)) { continue; }
            String skipKey = domain + ":" + op.methodName();
            if (handWrittenMethods.contains(skipKey)) {
                processingEnv.getMessager().printMessage(Diagnostic.Kind.NOTE,
                                                         "GraphQL generator: skipping " + skipKey + " — hand-written resolver exists");
                continue;
            }
            toGenerate.add(op);
            spiImports.add(op.declaringClassFqcn());
        }

        if (toGenerate.isEmpty()) {
            return;
        }

        try {
            JavaFileObject sourceFile = processingEnv.getFiler().createSourceFile(fqcn);
            try (PrintWriter out = new PrintWriter(sourceFile.openWriter())) {
                out.println("package " + packageName + ";");
                out.println();
                out.println("import jakarta.enterprise.context.ApplicationScoped;");
                out.println("import jakarta.inject.Inject;");
                out.println("import org.eclipse.microprofile.graphql.GraphQLApi;");
                out.println("import org.eclipse.microprofile.graphql.Query;");
                out.println("import org.eclipse.microprofile.graphql.Mutation;");
                out.println("import org.eclipse.microprofile.graphql.Description;");
                out.println("import io.casehub.platform.api.mcp.McpDomain;");

                boolean needsVirtualThread = false;
                for (ResolvedOperation genOp : toGenerate) {
                    boolean isUni = genOp.returnTypeStr().startsWith("Uni<");
                    if (genOp.type() != OperationType.STREAM && !isUni) {
                        needsVirtualThread = true;
                    }
                }
                if (needsVirtualThread) {
                    out.println("import io.smallrye.common.annotation.RunOnVirtualThread;");
                    out.println("import jakarta.transaction.Transactional;");
                }

                Set<String> typeImports = collectTypeImports(toGenerate);
                for (ResolvedOperation genOp : toGenerate) {
                    for (ResolvedParam genP : genOp.params()) {
                        if (!genP.isPathParam() && !genP.isHeaderParam() && !genP.isQueryParam() && !genP.isContextParam() && !genP.isSimpleType() && (genOp.type() == OperationType.QUERY || genOp.type() == OperationType.STREAM)) {
                            typeImports.addAll(resolveBeanFieldImports(genP.typeFqcn()));
                        }
                    }
                }
                Set<String> baseImports = Set.of("jakarta.enterprise.context.ApplicationScoped", "jakarta.inject.Inject", "jakarta.ws.rs.Path", "jakarta.ws.rs.Produces", "jakarta.ws.rs.core.MediaType", "jakarta.ws.rs.core.Response");
                for (String imp : typeImports) {
                    if (!imp.startsWith("java.lang.") && imp.contains(".") && !baseImports.contains(imp)) {
                        out.println("import " + imp + ";");
                    }
                }
                for (String imp : spiImports) {
                    out.println("import " + imp + ";");
                }

                out.println();
                out.println("// GENERATED by GraphQLResolverProcessor — do not edit");
                out.println("@GraphQLApi");
                out.println("@McpDomain(\"" + domain + "\")");
                out.println("@ApplicationScoped");
                out.println("public class " + className + " {");
                out.println();

                Set<String> injectedFields = new HashSet<>();
                for (ResolvedOperation op : toGenerate) {
                    String fieldName = decapitalize(op.declaringClassSimple());
                    if (injectedFields.add(fieldName)) {
                        out.println("    @Inject");
                        out.println("    " + op.declaringClassSimple() + " " + fieldName + ";");
                        out.println();
                    }
                }

                if (hasPrincipalContextParams(toGenerate)) {
                    out.println("    @Inject");
                    out.println("    io.casehub.platform.api.identity.CurrentPrincipal currentPrincipal;");
                    out.println();
                }

                for (ResolvedOperation op : toGenerate) {
                    generateMethod(out, op);
                }

                out.println("}");
            }

            processingEnv.getMessager().printMessage(Diagnostic.Kind.NOTE,
                                                     "GraphQL generator: generated " + fqcn);

        } catch (IOException e) {
            processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR,
                                                     "GraphQL generator: failed to write " + fqcn + ": " + e.getMessage());
        }
    }

    private void generateRestResourceSource(String domain, DomainOperations ops,
                                            Set<String> handWrittenMethods) {
        String className = toPascalCase(domain) + "Resource";
        if (!SourceVersion.isIdentifier(className)) {
            processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR,
                                                     "REST generator: domain '" + domain
                                                     + "' produces invalid class name '" + className
                                                     + "'. Domain names must contain only alphanumerics, hyphens, and slashes.");
            return;
        }
        String restPkgOverride = processingEnv.getOptions().get("restPackage");
        String packageName = restPkgOverride != null
            ? restPkgOverride
            : deriveOutputPackage(spiPackage(ops), "rest");
        String fqcn        = packageName + "." + className;

        Set<String>             spiImports = new HashSet<>();
        List<ResolvedOperation> toGenerate = new ArrayList<>();

        for (ResolvedOperation op : ops.operations) {
            String skipKey = domain + ":" + op.methodName();
            if (handWrittenMethods.contains(skipKey)) {
                processingEnv.getMessager().printMessage(Diagnostic.Kind.NOTE,
                                                         "REST generator: skipping " + skipKey + " — hand-written REST resource exists");
                continue;
            }
            toGenerate.add(op);
            spiImports.add(op.declaringClassFqcn());
        }

        if (toGenerate.isEmpty()) {
            return;
        }

        for (ResolvedOperation op : toGenerate) {
            if (op.description() == null || op.description().isBlank()) {
                processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR,
                        "GraphQL generator: @" + op.type().name() + " on method '"
                        + op.methodName() + "' in " + op.declaringClassFqcn()
                        + " has an empty description. Provide a meaningful summary for LLM discovery.");
            }
        }

        try {
            JavaFileObject sourceFile = processingEnv.getFiler().createSourceFile(fqcn);
            try (PrintWriter out = new PrintWriter(sourceFile.openWriter())) {
                out.println("package " + packageName + ";");
                out.println();
                out.println("import jakarta.enterprise.context.ApplicationScoped;");
                out.println("import jakarta.inject.Inject;");
                out.println("import jakarta.ws.rs.Path;");
                out.println("import jakarta.ws.rs.Produces;");
                out.println("import jakarta.ws.rs.core.MediaType;");
                out.println("import jakarta.ws.rs.core.Response;");

                Set<String> usedVerbs = new HashSet<>();
                boolean needsConsumes = false;
                boolean needsQueryParam = false;
                boolean needsVirtualThread = false;
                for (ResolvedOperation genOp : toGenerate) {
                    String verb = resolveHttpVerb(genOp.type(), genOp.restMethodOverride());
                    usedVerbs.add(verb);
                    boolean isUni = genOp.returnTypeStr().startsWith("Uni<");
                    if (genOp.type() != OperationType.STREAM && genOp.type() != OperationType.WEBHOOK && !isUni) {
                        needsVirtualThread = true;
                    }
                    if (genOp.type() == OperationType.WEBHOOK) {
                        needsConsumes = true;
                    }
                    for (ResolvedParam p : genOp.params()) {
                        if (!p.isPathParam() && !p.isHeaderParam() && !p.isQueryParam() && !p.isContextParam() && !p.isSimpleType()) {
                            if (genOp.type() == OperationType.QUERY || genOp.type() == OperationType.STREAM) {
                                needsQueryParam = true;
                            } else {
                                needsConsumes = true;
                            }
                        }
                        if (!p.isPathParam() && !p.isHeaderParam() && !p.isContextParam() && p.isSimpleType() && !p.isQueryParam()) {
                            needsQueryParam = true;
                        }
                        if (p.isQueryParam()) {
                            needsQueryParam = true;
                        }
                    }
                }
                for (String verb : usedVerbs) {
                    out.println("import jakarta.ws.rs." + verb + ";");
                }
                if (needsConsumes) {
                    out.println("import jakarta.ws.rs.Consumes;");
                }
                if (needsQueryParam) {
                    out.println("import jakarta.ws.rs.QueryParam;");
                }
                if (needsVirtualThread) {
                    out.println("import io.smallrye.common.annotation.RunOnVirtualThread;");
                    out.println("import jakarta.transaction.Transactional;");
                }

                Set<String> typeImports = collectTypeImports(toGenerate);
                typeImports.addAll(spiImports);
                for (ResolvedOperation genOp : toGenerate) {
                    for (ResolvedParam genP : genOp.params()) {
                        if (!genP.isPathParam() && !genP.isHeaderParam() && !genP.isQueryParam() && !genP.isContextParam() && !genP.isSimpleType() && (genOp.type() == OperationType.QUERY || genOp.type() == OperationType.STREAM)) {
                            typeImports.addAll(resolveBeanFieldImports(genP.typeFqcn()));
                        }
                    }
                }
                Set<String> baseImports = Set.of("jakarta.enterprise.context.ApplicationScoped", "jakarta.inject.Inject", "jakarta.ws.rs.Path", "jakarta.ws.rs.Produces", "jakarta.ws.rs.core.MediaType", "jakarta.ws.rs.core.Response");
                for (String imp : typeImports) {
                    if (!imp.startsWith("java.lang.") && imp.contains(".") && !baseImports.contains(imp)) {
                        out.println("import " + imp + ";");
                    }
                }

                out.println();
                out.println("// GENERATED by GraphQLResolverProcessor — do not edit");
                String restBasePath = ops.basePath != null ? ops.basePath : "/api/" + domain;
                out.println("@Path(\"" + restBasePath + "\")");
                out.println("@Produces(MediaType.APPLICATION_JSON)");
                out.println("@ApplicationScoped");
                out.println("public class " + className + " {");
                out.println();

                Set<String> injectedFields = new HashSet<>();
                for (ResolvedOperation op : toGenerate) {
                    String fieldName = decapitalize(op.declaringClassSimple());
                    if (injectedFields.add(fieldName)) {
                        out.println("    @Inject");
                        out.println("    " + op.declaringClassSimple() + " " + fieldName + ";");
                        out.println();
                    }
                }

                if (hasPrincipalContextParams(toGenerate)) {
                    out.println("    @Inject");
                    out.println("    io.casehub.platform.api.identity.CurrentPrincipal currentPrincipal;");
                    out.println();
                }

                if (hasHttpContextParams(toGenerate)) {
                    out.println("    @jakarta.ws.rs.core.Context");
                    out.println("    jakarta.ws.rs.core.HttpHeaders httpHeaders;");
                    out.println();
                }

                if (needsUriInfo(toGenerate)) {
                    out.println("    @jakarta.ws.rs.core.Context");
                    out.println("    jakarta.ws.rs.core.UriInfo uriInfo;");
                    out.println();
                }

                for (ResolvedOperation op : toGenerate) {
                    generateRestMethod(out, op);
                }

                if (needsUriInfo(toGenerate)) {
                    out.println("    private static java.util.Map<String, String> flatQueryParams(jakarta.ws.rs.core.UriInfo uriInfo) {");
                    out.println("        return uriInfo.getQueryParameters().entrySet().stream()");
                    out.println("                .collect(java.util.stream.Collectors.toMap(java.util.Map.Entry::getKey,");
                    out.println("                        e -> e.getValue().isEmpty() ? \"\" : e.getValue().get(0)));");
                    out.println("    }");
                    out.println();
                }

                out.println("}");
            }

            processingEnv.getMessager().printMessage(Diagnostic.Kind.NOTE,
                                                     "REST generator: generated " + fqcn);

        } catch (IOException e) {
            processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR,
                                                     "REST generator: failed to write " + fqcn + ": " + e.getMessage());
        }
    }

    private void generateRestMethod(PrintWriter out, ResolvedOperation op) {
        if (op.type() == OperationType.STREAM) {
            generateRestStreamMethod(out, op);
            return;
        }
        if (op.type() == OperationType.WEBHOOK) {
            generateRestWebhookMethod(out, op);
            return;
        }
        String  httpVerb         = resolveHttpVerb(op.type(), op.restMethodOverride());
        boolean isBodyVerb       = httpVerb.equals("POST") || httpVerb.equals("PUT") || httpVerb.equals("PATCH");
        boolean isUniReturn      = op.returnTypeStr().startsWith("Uni<");
        boolean isResponseReturn = op.returnTypeStr().equals("Response") || op.returnTypeStr().equals("jakarta.ws.rs.core.Response");

        List<String> pathParams           = new ArrayList<>();
        Set<Integer> pathParamPositions   = new HashSet<>();
        Set<Integer> headerParamPositions = new HashSet<>();
        Set<Integer> queryParamPositions  = new HashSet<>();
        int          bodyParamIndex       = -1;
        int          complexCount         = 0;
        boolean      hasBeanParam         = false;
        int          beanParamIndex       = -1;

        for (int i = 0; i < op.params().size(); i++) {
            ResolvedParam p = op.params().get(i);
            if (p.isPathParam()) {
                pathParams.add(p.pathParamName() != null ? p.pathParamName() : p.name());
                pathParamPositions.add(i);
            } else if (p.isHeaderParam()) {
                headerParamPositions.add(i);
            } else if (p.isQueryParam()) {
                queryParamPositions.add(i);
            } else if (p.isContextParam()) {
                // handled separately
            } else if (isBodyVerb && !p.isSimpleType()) {
                complexCount++;
                if (bodyParamIndex < 0) {bodyParamIndex = i;}
            } else if (!isBodyVerb && !p.isSimpleType() && !p.isContextParam()) {
                hasBeanParam   = true;
                beanParamIndex = i;
            }
        }

        if (complexCount > 1) {
            processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR,
                                                     "REST generator: method '" + op.methodName() + "' on domain '"
                                                     + op.declaringClassSimple() + "' has " + complexCount
                                                     + " complex parameters. Wrap them in a single request DTO"
                                                     + " or annotate path parameters with @PathParam.");
            return;
        }

        boolean hasBody = bodyParamIndex >= 0;

        StringBuilder pathSuffix   = new StringBuilder();
        String        resolvedPath = resolveRestPath(op.restPathOverride(), op.methodName());
        if (resolvedPath.startsWith("/")) {
            pathSuffix.append(resolvedPath);
        } else {
            pathSuffix.append("/").append(resolvedPath);
        }
        for (String pp : pathParams) {
            if (!resolvedPath.contains("{" + pp + "}")) {
                pathSuffix.append("/{").append(pp).append("}");
            }
        }

        if (!op.rolesAllowed().isEmpty()) {
            String roles = op.rolesAllowed().stream().map(r -> "\"" + escapeJavaString(r) + "\"").collect(java.util.stream.Collectors.joining(", "));
            out.println("    @jakarta.annotation.security.RolesAllowed({" + roles + "})");
        }
        if (op.permitAll()) {
            out.println("    @jakarta.annotation.security.PermitAll");
        }
        for (String passed : op.passedAnnotations()) {
            out.println("    " + passed);
        }
        if (!op.description().isEmpty() && isOpenApiAvailable()) {
            out.println("    @org.eclipse.microprofile.openapi.annotations.Operation(summary = \"" + escapeJavaString(op.description()) + "\")");
        }
        if (!isUniReturn) {
            out.println("    @Transactional");
            out.println("    @RunOnVirtualThread");
        }
        out.println("    @" + httpVerb);
        out.println("    @Path(\"" + pathSuffix + "\")");
        if (hasBody) {
            out.println("    @Consumes(MediaType.APPLICATION_JSON)");
        }

        StringBuilder params     = new StringBuilder();
        boolean       firstParam = true;

        if (hasBeanParam) {
            ResolvedParam bp         = op.params().get(beanParamIndex);
            List<String>  beanFields = resolveBeanFields(bp.typeFqcn());
            for (String field : beanFields) {
                if (!firstParam) {params.append(", ");}
                firstParam = false;
                String fieldType = resolveBeanFieldType(bp.typeFqcn(), field);
                params.append("@QueryParam(\"").append(field).append("\") ").append(fieldType).append(" ").append(field);
            }
        }

        for (int i = 0; i < op.params().size(); i++) {
            ResolvedParam p = op.params().get(i);
            if (p.isContextParam()) {continue;}
            if (i == beanParamIndex) {continue;}

            if (!firstParam) {params.append(", ");}
            firstParam = false;

            if (p.hasValid()) {
                params.append("@jakarta.validation.Valid ");
            }

            if (pathParamPositions.contains(i)) {
                String pathName = p.pathParamName() != null ? p.pathParamName() : p.name();
                params.append("@jakarta.ws.rs.PathParam(\"").append(pathName).append("\") ");
            } else if (headerParamPositions.contains(i)) {
                params.append("@jakarta.ws.rs.HeaderParam(\"").append(p.headerParamName()).append("\") ");
            } else if (queryParamPositions.contains(i)) {
                params.append("@jakarta.ws.rs.QueryParam(\"").append(p.queryParamName()).append("\") ");
            } else if (i == bodyParamIndex) {
                // body param — no additional annotation needed beyond @Valid above
            } else {
                String qpName = p.restName() != null ? p.restName() : p.name();
                if (p.defaultValue() != null) {
                    params.append("@jakarta.ws.rs.DefaultValue(\"").append(escapeJavaString(p.defaultValue())).append("\") ");
                }
                params.append("@QueryParam(\"").append(qpName).append("\") ");
            }
            params.append(p.typeStr()).append(" ").append(p.name());
        }

        if (isUniReturn || isResponseReturn) {
            out.println("    public " + op.returnTypeStr() + " " + op.methodName() + "(" + params + ") {");
        } else {
            out.println("    public Response " + op.methodName() + "(" + params + ") {");
        }

        String        fieldName = decapitalize(op.declaringClassSimple());
        StringBuilder args      = new StringBuilder();
        for (int i = 0; i < op.params().size(); i++) {
            if (i > 0) {args.append(", ");}
            ResolvedParam p = op.params().get(i);
            if (p.isContextParam()) {
                args.append(contextParamResolution(p.contextParamKey()));
            } else if (i == beanParamIndex) {
                List<String> beanFields = resolveBeanFields(p.typeFqcn());
                args.append("new ").append(p.typeFqcn()).append("(").append(String.join(", ", beanFields)).append(")");
            } else {
                args.append(p.name());
            }
        }

        if (isUniReturn || isResponseReturn) {
            String delegateCall = fieldName + "." + op.methodName() + "(" + args + ")";
            out.println("        return " + delegateCall + ";");
        } else {
            boolean isMutation   = op.type() == OperationType.MUTATION;
            boolean hasPathParam = !pathParams.isEmpty();
            String  delegateCall = fieldName + "." + op.methodName() + "(" + args + ")";
            if (op.paginated()) {
                out.println("        var __pageResult = " + delegateCall + ";");
                out.println("        return Response.ok(__pageResult).header(\"X-Total-Count\", String.valueOf(__pageResult." + op.totalCountMethod() + "())).build();");
            } else {
                String responseCode = generateResponseCode(op.returnTypeStr(), delegateCall, isMutation, op.restStatusOverride(), hasPathParam);
                out.println("        " + responseCode);
            }
        }

        out.println("    }");
        out.println();
    }


    private void generateRestStreamMethod(PrintWriter out, ResolvedOperation op) {
        List<String> pathParams = new ArrayList<>();
        Set<Integer> pathParamPositions = new HashSet<>();
        for (int i = 0; i < op.params().size(); i++) {
            ResolvedParam p = op.params().get(i);
            if (p.isPathParam()) {
                pathParams.add(p.pathParamName() != null ? p.pathParamName() : p.name());
                pathParamPositions.add(i);
            }
        }

        StringBuilder pathSuffix = new StringBuilder();
        String resolvedPath = resolveRestPath(op.restPathOverride(), op.methodName());
        if (resolvedPath.startsWith("/")) {
            pathSuffix.append(resolvedPath);
        } else {
            pathSuffix.append("/").append(resolvedPath);
        }
        for (String pp : pathParams) {
            if (!resolvedPath.contains("{" + pp + "}")) {
                pathSuffix.append("/{").append(pp).append("}");
            }
        }

        if (!op.description().isEmpty() && isOpenApiAvailable()) {
            out.println("    @org.eclipse.microprofile.openapi.annotations.Operation(summary = \"" + escapeJavaString(op.description()) + "\")");
        }
        out.println("    @GET");
        out.println("    @Path(\"" + pathSuffix + "\")");
        out.println("    @Produces(MediaType.SERVER_SENT_EVENTS)");
        out.println("    @org.jboss.resteasy.reactive.RestStreamElementType(MediaType.APPLICATION_JSON)");

        StringBuilder params = new StringBuilder();
        boolean firstParam = true;
        for (int i = 0; i < op.params().size(); i++) {
            ResolvedParam p = op.params().get(i);
            if (p.isContextParam()) { continue; }

            if (!firstParam) {params.append(", ");}
            firstParam = false;

            if (pathParamPositions.contains(i)) {
                String pathName = p.pathParamName() != null ? p.pathParamName() : p.name();
                params.append("@jakarta.ws.rs.PathParam(\"").append(pathName).append("\") ");
            } else {
                String qpName = p.restName() != null ? p.restName() : p.name();
                params.append("@QueryParam(\"").append(qpName).append("\") ");
            }
            params.append(p.typeStr()).append(" ").append(p.name());
        }

        String returnType = op.returnTypeStr();
        String delegateCall;
        String fieldName = decapitalize(op.declaringClassSimple());
        StringBuilder args = new StringBuilder();
        for (int i = 0; i < op.params().size(); i++) {
            if (i > 0) {args.append(", ");}
            ResolvedParam p = op.params().get(i);
            if (p.isContextParam()) {
                args.append(contextParamResolution(p.contextParamKey()));
            } else {
                args.append(p.name());
            }
        }
        if (returnType.startsWith("Flow.Publisher<") || returnType.startsWith("java.util.concurrent.Flow.Publisher<")) {
            String typeArg = extractTypeArgument(returnType);
            returnType = "io.smallrye.mutiny.Multi<" + typeArg + ">";
            delegateCall = "io.smallrye.mutiny.Multi.createFrom().publisher(" + fieldName + "." + op.methodName() + "(" + args + "))";
        } else {
            delegateCall = fieldName + "." + op.methodName() + "(" + args + ")";
        }
        out.println("    public " + returnType + " " + op.methodName() + "(" + params + ") {");
        out.println("        return " + delegateCall + ";");
        out.println("    }");
        out.println();
    }

    private static String extractTypeArgument(String parameterizedType) {
        int start = parameterizedType.indexOf('<');
        int end = parameterizedType.lastIndexOf('>');
        if (start >= 0 && end > start) {
            return parameterizedType.substring(start + 1, end);
        }
        return "Object";
    }

    private void generateRestWebhookMethod(PrintWriter out, ResolvedOperation op) {
        List<String> pathParams           = new ArrayList<>();
        Set<Integer> pathParamPositions   = new HashSet<>();
        Set<Integer> headerParamPositions = new HashSet<>();
        Set<Integer> queryParamPositions  = new HashSet<>();
        int          bodyParamIndex       = -1;

        for (int i = 0; i < op.params().size(); i++) {
            ResolvedParam p = op.params().get(i);
            if (p.isPathParam()) {
                pathParams.add(p.pathParamName() != null ? p.pathParamName() : p.name());
                pathParamPositions.add(i);
            } else if (p.isHeaderParam()) {
                headerParamPositions.add(i);
            } else if (p.isQueryParam()) {
                queryParamPositions.add(i);
            } else if (!p.isContextParam() && bodyParamIndex < 0) {
                bodyParamIndex = i;
            }
        }

        StringBuilder pathSuffix   = new StringBuilder();
        String        resolvedPath = resolveRestPath(op.restPathOverride(), op.methodName());
        if (resolvedPath.startsWith("/")) {
            pathSuffix.append(resolvedPath);
        } else {
            pathSuffix.append("/").append(resolvedPath);
        }
        for (String pp : pathParams) {
            if (!resolvedPath.contains("{" + pp + "}")) {
                pathSuffix.append("/{").append(pp).append("}");
            }
        }

        if (!op.rolesAllowed().isEmpty()) {
            String roles = op.rolesAllowed().stream().map(r -> "\"" + escapeJavaString(r) + "\"").collect(java.util.stream.Collectors.joining(", "));
            out.println("    @jakarta.annotation.security.RolesAllowed({" + roles + "})");
        } else if (!op.permitAll()) {
            out.println("    @jakarta.annotation.security.PermitAll");
        }
        if (op.permitAll()) {
            out.println("    @jakarta.annotation.security.PermitAll");
        }
        for (String passed : op.passedAnnotations()) {
            out.println("    " + passed);
        }
        if (!op.description().isEmpty() && isOpenApiAvailable()) {
            out.println("    @org.eclipse.microprofile.openapi.annotations.Operation(summary = \"" + escapeJavaString(op.description()) + "\")");
        }
        out.println("    @POST");
        out.println("    @Path(\"" + pathSuffix + "\")");

        String[] consumes = op.webhookConsumes();
        if (consumes != null && consumes.length == 1) {
            if ("application/json".equals(consumes[0])) {
                out.println("    @Consumes(MediaType.APPLICATION_JSON)");
            } else {
                out.println("    @Consumes(\"" + escapeJavaString(consumes[0]) + "\")");
            }
        } else if (consumes != null && consumes.length > 1) {
            String consumesList = java.util.Arrays.stream(consumes).map(c -> "\"" + escapeJavaString(c) + "\"").collect(java.util.stream.Collectors.joining(", "));
            out.println("    @Consumes({" + consumesList + "})");
        }

        StringBuilder params     = new StringBuilder();
        boolean       firstParam = true;
        for (int i = 0; i < op.params().size(); i++) {
            ResolvedParam p = op.params().get(i);
            if (p.isContextParam()) {continue;}

            if (!firstParam) {params.append(", ");}
            firstParam = false;

            if (pathParamPositions.contains(i)) {
                String pathName = p.pathParamName() != null ? p.pathParamName() : p.name();
                params.append("@jakarta.ws.rs.PathParam(\"").append(pathName).append("\") ");
            } else if (headerParamPositions.contains(i)) {
                params.append("@jakarta.ws.rs.HeaderParam(\"").append(p.headerParamName()).append("\") ");
            } else if (queryParamPositions.contains(i)) {
                params.append("@jakarta.ws.rs.QueryParam(\"").append(p.queryParamName()).append("\") ");
            }
            params.append(p.typeStr()).append(" ").append(p.name());
        }

        boolean isResponseReturn = op.returnTypeStr().equals("Response") || op.returnTypeStr().equals("jakarta.ws.rs.core.Response");

        out.println("    public Response " + op.methodName() + "(" + params + ") {");
        String        fieldName = decapitalize(op.declaringClassSimple());
        StringBuilder args      = new StringBuilder();
        for (int i = 0; i < op.params().size(); i++) {
            if (i > 0) {args.append(", ");}
            ResolvedParam p = op.params().get(i);
            if (p.isContextParam()) {
                args.append(contextParamResolution(p.contextParamKey()));
            } else {
                args.append(p.name());
            }
        }

        String delegateCall = fieldName + "." + op.methodName() + "(" + args + ")";
        if (isResponseReturn) {
            out.println("        return " + delegateCall + ";");
        } else if ("void".equals(op.returnTypeStr())) {
            out.println("        " + delegateCall + ";");
            out.println("        return Response.ok().build();");
        } else {
            out.println("        return Response.ok(" + delegateCall + ").build();");
        }
        out.println("    }");
        out.println();
    }

    private List<String> resolveBeanFields(String typeFqcn) {
        if (jandexIndex != null) {
            ClassInfo ci = jandexIndex.getClassByName(DotName.createSimple(typeFqcn));
            if (ci != null) {
                List<String> fields = resolveRecordFieldOrder(ci);
                if (fields.isEmpty()) {
                    processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR,
                                                             "REST generator: @BeanParam expansion requires a Java record, but '"
                                                             + typeFqcn + "' is not a record. Use a record for query parameter DTOs.");
                }
                return fields;
            }
        }
        try {
            javax.lang.model.element.TypeElement te = processingEnv.getElementUtils().getTypeElement(typeFqcn);
            if (te != null) {
                List<String> fields = te.getRecordComponents().stream().map(rc -> rc.getSimpleName().toString()).toList();
                if (fields.isEmpty()) {
                    processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR,
                                                             "REST generator: @BeanParam expansion requires a Java record, but '"
                                                             + typeFqcn + "' is not a record. Use a record for query parameter DTOs.");
                }
                return fields;
            }
        } catch (Exception e) {
            // fallback
        }
        return List.of();
    }

    private String resolveBeanFieldType(String typeFqcn, String fieldName) {
        if (jandexIndex != null) {
            ClassInfo ci = jandexIndex.getClassByName(DotName.createSimple(typeFqcn));
            if (ci != null) {
                for (var rc : ci.recordComponents()) {
                    if (rc.name().equals(fieldName)) {
                        return typeToJava(rc.type());
                    }
                }
            }
        }
        try {
            javax.lang.model.element.TypeElement te = processingEnv.getElementUtils().getTypeElement(typeFqcn);
            if (te != null) {
                for (var rc : te.getRecordComponents()) {
                    if (rc.getSimpleName().toString().equals(fieldName)) {
                        return typeMirrorToJava(rc.asType());
                    }
                }
            }
        } catch (Exception e) {
            // fallback
        }
        return "String";
    }

    private Set<String> resolveBeanFieldImports(String typeFqcn) {
        Set<String> imports = new HashSet<>();
        if (jandexIndex != null) {
            ClassInfo ci = jandexIndex.getClassByName(DotName.createSimple(typeFqcn));
            if (ci != null) {
                for (var rc : ci.recordComponents()) {
                    addTypeImport(imports, rc.type());
                }
                return imports;
            }
        }
        try {
            javax.lang.model.element.TypeElement te = processingEnv.getElementUtils().getTypeElement(typeFqcn);
            if (te != null) {
                for (var rc : te.getRecordComponents()) {
                    collectTypeMirrorImports(imports, rc.asType());
                }
            }
        } catch (Exception e) {
            // fallback
        }
        return imports;
    }

    /**
     * Resolve record component names in declaration order via the canonical constructor.
     * Jandex recordComponents() and fields() both return alphabetical order, but
     * the canonical constructor parameters preserve the source declaration order.
     */
    private List<String> resolveRecordFieldOrder(ClassInfo ci) {
        int componentCount = ci.recordComponents().size();
        for (var m : ci.methods()) {
            if (m.name().equals("<init>") && m.parametersCount() == componentCount) {
                List<String> names = new ArrayList<>(componentCount);
                for (int i = 0; i < componentCount; i++) {
                    names.add(m.parameterName(i));
                }
                return names;
            }
        }
        return ci.recordComponents().stream().map(rc -> rc.name()).toList();
    }

    private void generateMethod(PrintWriter out, ResolvedOperation op) {
        if (op.type() == OperationType.STREAM) {
            generateGraphQLStreamMethod(out, op);
            return;
        }
        if (op.type() == OperationType.WEBHOOK) {
            return;
        }
        String annotation = op.type() == OperationType.QUERY ? "@Query" : "@Mutation";

        out.println("    " + annotation);
        if (!op.description().isEmpty()) {
            out.println("    @Description(\"" + escapeJavaString(op.description()) + "\")");
        }
        boolean isUniReturn = op.returnTypeStr().startsWith("Uni<");
        if (!isUniReturn) {
            out.println("    @Transactional");
            out.println("    @RunOnVirtualThread");
        }

        StringBuilder params = new StringBuilder();
        boolean firstParam = true;
        for (int i = 0; i < op.params().size(); i++) {
            ResolvedParam p = op.params().get(i);
            if (p.isContextParam()) { continue; }

            if (!firstParam) {params.append(", ");}
            firstParam = false;
            params.append(p.typeStr()).append(" ").append(p.name());
        }

        out.println("    public " + op.returnTypeStr() + " " + op.methodName() + "(" + params + ") {");

        String        fieldName = decapitalize(op.declaringClassSimple());
        StringBuilder args      = new StringBuilder();
        for (int i = 0; i < op.params().size(); i++) {
            if (i > 0) {args.append(", ");}
            ResolvedParam p = op.params().get(i);
            if (p.isContextParam()) {
                args.append(contextParamResolution(p.contextParamKey()));
            } else {
                args.append(p.name());
            }
        }

        if ("void".equals(op.returnTypeStr())) {
            out.println("        " + fieldName + "." + op.methodName() + "(" + args + ");");
        } else {
            out.println("        return " + fieldName + "." + op.methodName() + "(" + args + ");");
        }

        out.println("    }");
        out.println();
    }

    private void generateGraphQLStreamMethod(PrintWriter out, ResolvedOperation op) {
        out.println("    @io.smallrye.graphql.api.Subscription");
        if (!op.description().isEmpty()) {
            out.println("    @Description(\"" + escapeJavaString(op.description()) + "\")");
        }

        StringBuilder params = new StringBuilder();
        boolean firstParam = true;
        for (int i = 0; i < op.params().size(); i++) {
            ResolvedParam p = op.params().get(i);
            if (p.isContextParam()) { continue; }

            if (!firstParam) {params.append(", ");}
            firstParam = false;
            params.append(p.typeStr()).append(" ").append(p.name());
        }

        String returnType = op.returnTypeStr();
        String fieldName = decapitalize(op.declaringClassSimple());
        StringBuilder args = new StringBuilder();
        for (int i = 0; i < op.params().size(); i++) {
            if (i > 0) {args.append(", ");}
            ResolvedParam p = op.params().get(i);
            if (p.isContextParam()) {
                args.append(contextParamResolution(p.contextParamKey()));
            } else {
                args.append(p.name());
            }
        }
        String delegateCall;
        if (returnType.startsWith("Flow.Publisher<") || returnType.startsWith("java.util.concurrent.Flow.Publisher<")) {
            String typeArg = extractTypeArgument(returnType);
            returnType = "io.smallrye.mutiny.Multi<" + typeArg + ">";
            delegateCall = "io.smallrye.mutiny.Multi.createFrom().publisher(" + fieldName + "." + op.methodName() + "(" + args + "))";
        } else {
            delegateCall = fieldName + "." + op.methodName() + "(" + args + ")";
        }
        out.println("    public " + returnType + " " + op.methodName() + "(" + params + ") {");
        out.println("        return " + delegateCall + ";");
        out.println("    }");
        out.println();
    }


    private static boolean usesHttpContext(ResolvedOperation op) {
        for (ResolvedParam p : op.params()) {
            if (p.isContextParam()) {
                String key = p.contextParamKey();
                if ("httpHeaders".equals(key) || "queryParams".equals(key) || "requestUrl".equals(key)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean hasPrincipalContextParams(List<ResolvedOperation> operations) {
        for (ResolvedOperation op : operations) {
            for (ResolvedParam p : op.params()) {
                if (p.isContextParam()) {
                    String key = p.contextParamKey();
                    if (!"httpHeaders".equals(key) && !"queryParams".equals(key) && !"requestUrl".equals(key)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private static boolean hasHttpContextParams(List<ResolvedOperation> operations) {
        for (ResolvedOperation op : operations) {
            if (usesHttpContext(op)) {return true;}
        }
        return false;
    }

    private static boolean needsUriInfo(List<ResolvedOperation> operations) {
        for (ResolvedOperation op : operations) {
            for (ResolvedParam p : op.params()) {
                if (p.isContextParam()) {
                    String key = p.contextParamKey();
                    if ("queryParams".equals(key) || "requestUrl".equals(key)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }


    private static String contextParamResolution(String key) {
        return switch (key) {
            case "tenancyId" -> "currentPrincipal.tenancyId()";
            case "actorId" -> "currentPrincipal.actorId()";
            case "httpHeaders" -> "httpHeaders.getRequestHeaders()";
            case "queryParams" -> "flatQueryParams(uriInfo)";
            case "requestUrl" -> "uriInfo.getRequestUri().toString()";
            default -> "currentPrincipal." + key + "()";
        };
    }

    private Set<String> collectTypeImports(List<ResolvedOperation> operations) {
        Set<String> imports = new HashSet<>();
        for (ResolvedOperation op : operations) {
            imports.addAll(op.typeImports());
        }
        return imports;
    }

    private void addTypeImport(Set<String> imports, Type type) {
        switch (type.kind()) {
            case CLASS -> {
                String fqcn = type.name().toString();
                imports.add(fqcn.contains("$") ? fqcn.substring(0, fqcn.indexOf('$')) : fqcn);
            }
            case PARAMETERIZED_TYPE -> {
                String fqcn = type.asParameterizedType().name().toString();
                imports.add(fqcn.contains("$") ? fqcn.substring(0, fqcn.indexOf('$')) : fqcn);
                for (Type arg : type.asParameterizedType().arguments()) {
                    addTypeImport(imports, arg);
                }
            }
            case ARRAY -> addTypeImport(imports, type.asArrayType().constituent());
            default -> {}
        }
    }

    private String typeToJava(Type type) {
        return switch (type.kind()) {
            case VOID -> "void";
            case PRIMITIVE -> type.asPrimitiveType().primitive().name().toLowerCase();
            case CLASS -> {
                String fqcn = type.asClassType().name().toString();
                if (fqcn.contains("$")) {
                    String outerSimple = fqcn.substring(fqcn.lastIndexOf('.') + 1).replace('$', '.');
                    yield outerSimple;
                }
                yield type.asClassType().name().local();
            }
            case PARAMETERIZED_TYPE -> {
                String fqcn = type.asParameterizedType().name().toString();
                String baseName;
                if (fqcn.contains("$")) {
                    baseName = fqcn.substring(fqcn.lastIndexOf('.') + 1).replace('$', '.');
                } else {
                    baseName = type.asParameterizedType().name().local();
                }
                StringBuilder sb = new StringBuilder(baseName);
                sb.append("<");
                List<Type> args = type.asParameterizedType().arguments();
                for (int i = 0; i < args.size(); i++) {
                    if (i > 0) {sb.append(", ");}
                    sb.append(typeToJava(args.get(i)));
                }
                sb.append(">");
                yield sb.toString();
            }
            case ARRAY -> typeToJava(type.asArrayType().constituent()) + "[]";
            default -> type.name().toString();
        };
    }

    private static String decapitalize(String s) {
        if (s == null || s.isEmpty()) {return s;}
        return Character.toLowerCase(s.charAt(0)) + s.substring(1);
    }

    private static String escapeJavaString(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
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

    static String toKebabCase(String camelCase) {
        if (camelCase == null || camelCase.isEmpty()) {return camelCase;}
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

    static String toPascalCase(String kebab) {
        if (kebab == null || kebab.isEmpty()) {return kebab;}
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

    static String deriveOutputPackage(String spiPackage, String channel) {
        int apiIdx = spiPackage.indexOf(".api.");
        if (apiIdx >= 0) {
            return spiPackage.substring(0, apiIdx) + "." + channel
                   + spiPackage.substring(apiIdx + 4);
        }
        if (spiPackage.endsWith(".api")) {
            return spiPackage.substring(0, spiPackage.length() - 4) + "." + channel;
        }
        return spiPackage + "." + channel;
    }

    private static String spiPackage(DomainOperations ops) {
        if (ops.operations.isEmpty()) {return "";}
        String fqcn    = ops.operations.get(0).declaringClassFqcn();
        int    lastDot = fqcn.lastIndexOf('.');
        return lastDot > 0 ? fqcn.substring(0, lastDot) : "";
    }


    static String resolveHttpVerb(OperationType type, String restMethodOverride) {
        if (restMethodOverride != null) {
            return restMethodOverride;
        }
        return switch (type) {
            case QUERY, STREAM -> "GET";
            case MUTATION, WEBHOOK -> "POST";
        };
    }

    static String resolveRestPath(String restPathOverride, String methodName) {
        if (restPathOverride != null) {
            return restPathOverride;
        }
        return toKebabCase(methodName);
    }


    private static final Set<String> SIMPLE_TYPES = Set.of(
            "java.lang.String",
            "java.lang.Integer", "java.lang.Long", "java.lang.Short", "java.lang.Byte",
            "java.lang.Float", "java.lang.Double", "java.lang.Boolean", "java.lang.Character",
            "java.util.UUID",
            "int", "long", "short", "byte", "float", "double", "boolean", "char"
                                                          );

    static final Set<String> COLLECTION_TYPES = Set.of(
            "java.util.List", "java.util.Set", "java.util.Collection",
            "java.util.Map", "java.util.Optional"
                                                      );

    static boolean isSimpleTypeJandex(Type type, IndexView index) {
        String fqcn = type.name().toString();
        if (COLLECTION_TYPES.contains(fqcn)) {
            if (type.kind() != Type.Kind.PARAMETERIZED_TYPE) {return true;}
            return type.asParameterizedType().arguments().stream()
                    .allMatch(arg -> isSimpleTypeJandex(arg, index));
        }
        return isSimpleType(fqcn, index);
    }

    static boolean isSimpleType(String fqcn) {
        return isSimpleType(fqcn, null);
    }

    static boolean isSimpleType(String fqcn, IndexView index) {
        if (SIMPLE_TYPES.contains(fqcn)) {return true;}
        if (fqcn.startsWith("java.time.")) {return true;}
        if (index != null) {
            ClassInfo ci = index.getClassByName(fqcn);
            if (ci != null) {
                if (ci.isEnum()) {return true;}
                if (hasStaticStringMethod(ci, "fromString")) {return true;}
                if (!ci.isEnum() && hasStaticStringMethod(ci, "valueOf")) {return true;}
            }
        }
        return false;
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

    static String generateResponseCode(String returnType, String delegateCall) {
        return generateResponseCode(returnType, delegateCall, false, -1, false);
    }

    static String generateResponseCode(String returnType, String delegateCall,
                                         boolean isMutation, int restStatusOverride,
                                         boolean hasPathParam) {
        if ("void".equals(returnType)) {
            int status = restStatusOverride > 0 ? restStatusOverride : 204;
            if (status == 204) {
                return delegateCall + "; return Response.noContent().build();";
            }
            return delegateCall + "; return Response.status(" + status + ").build();";
        }
        if (returnType.startsWith("Optional<")) {
            return "return " + delegateCall + ".map(v -> Response.ok(v).build()).orElse(Response.status(404).build());";
        }
        if ("boolean".equals(returnType) && hasPathParam) {
            int successStatus = restStatusOverride > 0 ? restStatusOverride : 204;
            if (successStatus == 204) {
                return "return " + delegateCall + " ? Response.noContent().build() : Response.status(404).build();";
            }
            return "return " + delegateCall + " ? Response.status(" + successStatus + ").build() : Response.status(404).build();";
        }
        if (hasPathParam && !isCollectionType(returnType) && !isPrimitiveType(returnType)) {
            String statusExpr = restStatusOverride > 0
                    ? "Response.status(" + restStatusOverride + ").entity(result).build()"
                    : "Response.ok(result).build()";
            return "var result = " + delegateCall + "; if (result == null) return Response.status(404).build(); return " + statusExpr + ";";
        }
        if (restStatusOverride > 0) {
            return "return Response.status(" + restStatusOverride + ").entity(" + delegateCall + ").build();";
        }
        if (isMutation) {
            return "return Response.ok(" + delegateCall + ").build();";
        }
        return "return Response.ok(" + delegateCall + ").build();";
    }

    private boolean openApiAvailable;
    private boolean openApiChecked;

    private boolean isOpenApiAvailable() {
        if (!openApiChecked) {
            openApiChecked = true;
            try {
                getClass().getClassLoader().loadClass("org.eclipse.microprofile.openapi.annotations.Operation");
                openApiAvailable = true;
            } catch (ClassNotFoundException e) {
                openApiAvailable = false;
            }
        }
        return openApiAvailable;
    }

    static boolean isCollectionType(String returnType) {
        return returnType.startsWith("List<") || returnType.startsWith("Set<")
               || returnType.startsWith("Collection<") || returnType.startsWith("Map<");
    }

    private static final Set<String> PRIMITIVE_TYPES = Set.of(
            "boolean", "byte", "char", "short", "int", "long", "float", "double");

    static boolean isPrimitiveType(String returnType) {
        return PRIMITIVE_TYPES.contains(returnType);
    }


    enum OperationType {QUERY, MUTATION, STREAM, WEBHOOK}

    enum Source {JANDEX, ROUND_ENV}

    static class DomainOperations {
        final String                  domain;
        final Source                  source;
        final List<ResolvedOperation> operations = new ArrayList<>();
        String basePath;

        DomainOperations(String domain, Source source) {
            this.domain = domain;
            this.source = source;
        }
    }

    record ResolvedOperation(
            String methodName,
            String returnTypeStr,
            List<ResolvedParam> params,
            Set<String> typeImports,
            String declaringClassFqcn,
            String declaringClassSimple,
            OperationType type,
            String description,
            String restMethodOverride,
            String restPathOverride,
            int restStatusOverride,
            List<String> rolesAllowed,
            boolean paginated,
            String totalCountMethod,
            String[] webhookConsumes,
            boolean permitAll,
            List<String> passedAnnotations
    ) {}

    record ResolvedParam(
            String name,
            String typeStr,
            String typeFqcn,
            boolean isPathParam,
            String pathParamName,
            boolean isSimpleType,
            String restName,
            boolean isContextParam,
            String contextParamKey,
            boolean hasValid,
            String defaultValue,
            boolean isHeaderParam,
            String headerParamName,
            boolean isQueryParam,
            String queryParamName
    ) {}


}
