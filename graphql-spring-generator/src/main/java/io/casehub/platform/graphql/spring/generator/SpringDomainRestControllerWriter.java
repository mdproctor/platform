package io.casehub.platform.graphql.spring.generator;

import com.palantir.javapoet.AnnotationSpec;
import com.palantir.javapoet.ClassName;
import com.palantir.javapoet.CodeBlock;
import com.palantir.javapoet.FieldSpec;
import com.palantir.javapoet.JavaFile;
import com.palantir.javapoet.MethodSpec;
import com.palantir.javapoet.ParameterSpec;
import com.palantir.javapoet.ParameterizedTypeName;
import com.palantir.javapoet.TypeName;
import com.palantir.javapoet.TypeSpec;
import io.casehub.platform.generator.DomainScanResult;
import io.casehub.platform.generator.GeneratorUtils;
import io.casehub.platform.generator.OperationType;
import io.casehub.platform.generator.ResolvedOperation;
import io.casehub.platform.generator.ResolvedParam;

import javax.lang.model.element.Modifier;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class SpringDomainRestControllerWriter {

    private static final ClassName REST_CONTROLLER = ClassName.get("org.springframework.web.bind.annotation", "RestController");
    private static final ClassName REQUEST_MAPPING = ClassName.get("org.springframework.web.bind.annotation", "RequestMapping");
    private static final ClassName GET_MAPPING = ClassName.get("org.springframework.web.bind.annotation", "GetMapping");
    private static final ClassName POST_MAPPING = ClassName.get("org.springframework.web.bind.annotation", "PostMapping");
    private static final ClassName PUT_MAPPING = ClassName.get("org.springframework.web.bind.annotation", "PutMapping");
    private static final ClassName DELETE_MAPPING = ClassName.get("org.springframework.web.bind.annotation", "DeleteMapping");
    private static final ClassName PATCH_MAPPING = ClassName.get("org.springframework.web.bind.annotation", "PatchMapping");
    private static final ClassName REQUEST_PARAM = ClassName.get("org.springframework.web.bind.annotation", "RequestParam");
    private static final ClassName PATH_VARIABLE = ClassName.get("org.springframework.web.bind.annotation", "PathVariable");
    private static final ClassName REQUEST_BODY = ClassName.get("org.springframework.web.bind.annotation", "RequestBody");
    private static final ClassName RESPONSE_ENTITY = ClassName.get("org.springframework.http", "ResponseEntity");
    private static final ClassName HTTP_STATUS = ClassName.get("org.springframework.http", "HttpStatus");
    private static final ClassName MEDIA_TYPE = ClassName.get("org.springframework.http", "MediaType");
    private static final ClassName ROLES_ALLOWED = ClassName.get("jakarta.annotation.security", "RolesAllowed");
    private static final ClassName SSE_EMITTER          = ClassName.get("org.springframework.web.servlet.mvc.method.annotation", "SseEmitter");
    private static final ClassName FLOW_SUBSCRIBER      = ClassName.get("java.util.concurrent", "Flow", "Subscriber");
    private static final ClassName FLOW_SUBSCRIPTION    = ClassName.get("java.util.concurrent", "Flow", "Subscription");
    private static final ClassName VALID = ClassName.get("jakarta.validation", "Valid");
    private static final ClassName REQUEST_HEADER = ClassName.get("org.springframework.web.bind.annotation", "RequestHeader");

    public JavaFile generate(DomainScanResult domain, String targetPackage) {
        String className = GeneratorUtils.toPascalCase(domain.domainName()) + "RestController";
        ClassName spiType = ClassName.bestGuess(domain.sourceFqcn());
        String fieldName = GeneratorUtils.decapitalize(domain.sourceSimple());

        TypeSpec.Builder classBuilder = TypeSpec.classBuilder(className)
                .addModifiers(Modifier.PUBLIC)
                .addAnnotation(REST_CONTROLLER)
                .addAnnotation(AnnotationSpec.builder(REQUEST_MAPPING)
                        .addMember("value", "$S", domain.resolvedBasePath())
                        .addMember("produces", "$T.APPLICATION_JSON_VALUE", MEDIA_TYPE)
                        .build());

        classBuilder.addField(FieldSpec.builder(spiType, fieldName, Modifier.PRIVATE, Modifier.FINAL).build());

        boolean needsCurrentPrincipal = domain.operations().stream()
                .anyMatch(op -> op.params().stream().anyMatch(ResolvedParam::isContextParam));
        ClassName currentPrincipalType = ClassName.get("io.casehub.platform.api.identity", "CurrentPrincipal");
        if (needsCurrentPrincipal) {
            classBuilder.addField(FieldSpec.builder(currentPrincipalType, "currentPrincipal", Modifier.PRIVATE, Modifier.FINAL).build());
        }

        MethodSpec.Builder ctorBuilder = MethodSpec.constructorBuilder()
                .addModifiers(Modifier.PUBLIC)
                .addParameter(spiType, fieldName)
                .addStatement("this.$L = $L", fieldName, fieldName);
        if (needsCurrentPrincipal) {
            ctorBuilder.addParameter(currentPrincipalType, "currentPrincipal")
                    .addStatement("this.currentPrincipal = currentPrincipal");
        }
        classBuilder.addMethod(ctorBuilder.build());

        for (ResolvedOperation op : domain.operations()) {
            if (op.type() == OperationType.STREAM) {
                classBuilder.addMethod(buildStreamMethod(op, fieldName));
            } else if (op.type() == OperationType.WEBHOOK) {
                classBuilder.addMethod(buildWebhookMethod(op, fieldName));
            } else {
                classBuilder.addMethod(buildRestMethod(op, fieldName));
            }
        }

        return JavaFile.builder(targetPackage, classBuilder.build()).build();
    }

    private MethodSpec buildRestMethod(ResolvedOperation op, String fieldName) {
        String httpVerb = GeneratorUtils.resolveHttpVerb(op.type(), op.restMethodOverride());
        boolean isBodyVerb = httpVerb.equals("POST") || httpVerb.equals("PUT") || httpVerb.equals("PATCH");

        List<String> pathParams = new ArrayList<>();
        Set<Integer> pathParamPositions = new HashSet<>();
        int bodyParamIndex = -1;
        int complexCount = 0;

        for (int i = 0; i < op.params().size(); i++) {
            ResolvedParam p = op.params().get(i);
            if (p.isPathParam()) {
                pathParams.add(p.pathParamName() != null ? p.pathParamName() : p.name());
                pathParamPositions.add(i);
            } else if (isBodyVerb && !p.isSimpleType()) {
                complexCount++;
                if (bodyParamIndex < 0) { bodyParamIndex = i; }
            }
        }

        boolean hasBody = bodyParamIndex >= 0;
        boolean hasPathParam = !pathParams.isEmpty();

        StringBuilder pathSuffix = new StringBuilder();
        pathSuffix.append("/").append(GeneratorUtils.resolveRestPath(op.restPathOverride(), op.methodName()));
        String resolvedPath = GeneratorUtils.resolveRestPath(op.restPathOverride(), op.methodName());
        for (String pp : pathParams) {
            if (!resolvedPath.contains("{" + pp + "}")) {
                pathSuffix.append("/{").append(pp).append("}");
            }
        }

        ClassName mappingAnnotation = switch (httpVerb) {
            case "GET" -> GET_MAPPING;
            case "POST" -> POST_MAPPING;
            case "PUT" -> PUT_MAPPING;
            case "DELETE" -> DELETE_MAPPING;
            case "PATCH" -> PATCH_MAPPING;
            default -> GET_MAPPING;
        };

        TypeName returnType = ParameterizedTypeName.get(RESPONSE_ENTITY, ClassName.OBJECT);

        MethodSpec.Builder builder = MethodSpec.methodBuilder(op.methodName())
                .addModifiers(Modifier.PUBLIC)
                .returns(returnType);

        if (!op.rolesAllowed().isEmpty()) {
            AnnotationSpec.Builder ra = AnnotationSpec.builder(ROLES_ALLOWED);
            for (String role : op.rolesAllowed()) {
                ra.addMember("value", "$S", role);
            }
            builder.addAnnotation(ra.build());
        }

        builder.addAnnotation(AnnotationSpec.builder(mappingAnnotation)
                .addMember("value", "$S", pathSuffix.toString())
                .build());

        for (int i = 0; i < op.params().size(); i++) {
            ResolvedParam p = op.params().get(i);
            if (p.isContextParam()) { continue; }
            ParameterSpec.Builder paramBuilder = ParameterSpec.builder(p.typeName(), p.name());

            if (p.hasValid()) {
                paramBuilder.addAnnotation(VALID);
            }

            if (pathParamPositions.contains(i)) {
                String pathName = p.pathParamName() != null ? p.pathParamName() : p.name();
                paramBuilder.addAnnotation(AnnotationSpec.builder(PATH_VARIABLE)
                        .addMember("value", "$S", pathName).build());
            } else if (p.isHeaderParam()) {
                String headerName = p.headerParamName() != null ? p.headerParamName() : p.name();
                paramBuilder.addAnnotation(AnnotationSpec.builder(REQUEST_HEADER)
                        .addMember("value", "$S", headerName).build());
            } else if (i == bodyParamIndex) {
                paramBuilder.addAnnotation(REQUEST_BODY);
            } else {
                String qpName = p.restName() != null ? p.restName() : p.name();
                AnnotationSpec.Builder rp = AnnotationSpec.builder(REQUEST_PARAM)
                        .addMember("value", "$S", qpName);
                if (p.defaultValue() != null) {
                    rp.addMember("defaultValue", "$S", p.defaultValue());
                }
                paramBuilder.addAnnotation(rp.build());
            }

            builder.addParameter(paramBuilder.build());
        }

        String args = op.params().stream()
                .map(p -> p.isContextParam() ? contextParamResolution(p.contextParamKey()) : p.name())
                .reduce((a, b) -> a + ", " + b).orElse("");
        String delegateCall = fieldName + "." + op.methodName() + "(" + args + ")";
        boolean isMutation = op.type() == OperationType.MUTATION;

        if (op.paginated()) {
            builder.addStatement("var __pageResult = $L", delegateCall);
            builder.addStatement("return $T.ok().header(\"X-Total-Count\", String.valueOf(__pageResult.$L())).body(__pageResult)",
                    RESPONSE_ENTITY, op.totalCountMethod());
        } else {
            builder.addCode(generateResponseCode(op.returnTypeStr(), delegateCall, isMutation,
                    op.restStatusOverride(), hasPathParam));
        }

        return builder.build();
    }

    private MethodSpec buildStreamMethod(ResolvedOperation op, String fieldName) {
        List<String> pathParams         = new ArrayList<>();
        Set<Integer> pathParamPositions = new HashSet<>();
        for (int i = 0; i < op.params().size(); i++) {
            ResolvedParam p = op.params().get(i);
            if (p.isPathParam()) {
                pathParams.add(p.pathParamName() != null ? p.pathParamName() : p.name());
                pathParamPositions.add(i);
            }
        }

        StringBuilder pathSuffix = new StringBuilder();
        pathSuffix.append("/").append(GeneratorUtils.resolveRestPath(op.restPathOverride(), op.methodName()));
        String resolvedPath = GeneratorUtils.resolveRestPath(op.restPathOverride(), op.methodName());
        for (String pp : pathParams) {
            if (!resolvedPath.contains("{" + pp + "}")) {
                pathSuffix.append("/{").append(pp).append("}");
            }
        }

        MethodSpec.Builder builder = MethodSpec.methodBuilder(op.methodName())
                                               .addModifiers(Modifier.PUBLIC)
                                               .returns(SSE_EMITTER)
                                               .addAnnotation(AnnotationSpec.builder(GET_MAPPING)
                                                                            .addMember("value", "$S", pathSuffix.toString())
                                                                            .addMember("produces", "$T.TEXT_EVENT_STREAM_VALUE", MEDIA_TYPE)
                                                                            .build());

        for (int i = 0; i < op.params().size(); i++) {
            ResolvedParam p = op.params().get(i);
            if (p.isContextParam()) {continue;}
            ParameterSpec.Builder paramBuilder = ParameterSpec.builder(p.typeName(), p.name());
            if (pathParamPositions.contains(i)) {
                String pathName = p.pathParamName() != null ? p.pathParamName() : p.name();
                paramBuilder.addAnnotation(AnnotationSpec.builder(PATH_VARIABLE)
                                                         .addMember("value", "$S", pathName).build());
            } else {
                String qpName = p.restName() != null ? p.restName() : p.name();
                paramBuilder.addAnnotation(AnnotationSpec.builder(REQUEST_PARAM)
                                                         .addMember("value", "$S", qpName).build());
            }
            builder.addParameter(paramBuilder.build());
        }

        String args = op.params().stream()
                        .map(p -> p.isContextParam() ? contextParamResolution(p.contextParamKey()) : p.name())
                        .reduce((a, b) -> a + ", " + b).orElse("");

        com.palantir.javapoet.TypeName eventType = ClassName.get(Object.class);
        if (op.returnTypeName() instanceof ParameterizedTypeName pt && !pt.typeArguments().isEmpty()) {
            eventType = pt.typeArguments().get(0);
        }

        builder.addCode(CodeBlock.builder()
                                 .addStatement("$T emitter = new $T(0L)", SSE_EMITTER, SSE_EMITTER)
                                 .beginControlFlow("$T.ofVirtual().start(() ->", Thread.class)
                                 .beginControlFlow("$L.$L($L).subscribe(new $T<$T>()", fieldName, op.methodName(), args, FLOW_SUBSCRIBER, eventType)
                                 .add("@Override\n")
                                 .beginControlFlow("public void onSubscribe($T subscription)", FLOW_SUBSCRIPTION)
                                 .addStatement("subscription.request($T.MAX_VALUE)", Long.class)
                                 .addStatement("emitter.onTimeout(subscription::cancel)")
                                 .addStatement("emitter.onCompletion(subscription::cancel)")
                                 .endControlFlow()
                                 .add("@Override\n")
                                 .beginControlFlow("public void onNext($T item)", eventType)
                                 .beginControlFlow("try")
                                 .addStatement("emitter.send(item)")
                                 .nextControlFlow("catch ($T e)", Exception.class)
                                 .addStatement("emitter.completeWithError(e)")
                                 .endControlFlow()
                                 .endControlFlow()
                                 .add("@Override\n")
                                 .beginControlFlow("public void onError($T t)", Throwable.class)
                                 .addStatement("emitter.completeWithError(t)")
                                 .endControlFlow()
                                 .add("@Override\n")
                                 .beginControlFlow("public void onComplete()")
                                 .addStatement("emitter.complete()")
                                 .endControlFlow()
                                 .endControlFlow(")") // end anonymous class + subscribe call
                                 .endControlFlow(")") // end lambda + Thread.start
                                 .addStatement("return emitter")
                                 .build());

        return builder.build();
    }

    private MethodSpec buildWebhookMethod(ResolvedOperation op, String fieldName) {
        List<String> pathParams = new ArrayList<>();
        Set<Integer> pathParamPositions = new HashSet<>();
        int bodyParamIndex = -1;

        for (int i = 0; i < op.params().size(); i++) {
            ResolvedParam p = op.params().get(i);
            if (p.isPathParam()) {
                pathParams.add(p.pathParamName() != null ? p.pathParamName() : p.name());
                pathParamPositions.add(i);
            } else if (!p.isContextParam() && !p.isHeaderParam() && !p.isSimpleType() && bodyParamIndex < 0) {
                bodyParamIndex = i;
            }
        }

        StringBuilder pathSuffix = new StringBuilder();
        pathSuffix.append("/").append(GeneratorUtils.resolveRestPath(op.restPathOverride(), op.methodName()));
        String resolvedPath = GeneratorUtils.resolveRestPath(op.restPathOverride(), op.methodName());
        for (String pp : pathParams) {
            if (!resolvedPath.contains("{" + pp + "}")) {
                pathSuffix.append("/{").append(pp).append("}");
            }
        }

        AnnotationSpec.Builder mappingBuilder = AnnotationSpec.builder(POST_MAPPING)
                .addMember("value", "$S", pathSuffix.toString());
        if (op.webhookConsumes() != null && op.webhookConsumes().length > 0) {
            for (String ct : op.webhookConsumes()) {
                mappingBuilder.addMember("consumes", "$S", ct);
            }
        }

        TypeName returnType = ParameterizedTypeName.get(RESPONSE_ENTITY, ClassName.OBJECT);

        MethodSpec.Builder builder = MethodSpec.methodBuilder(op.methodName())
                .addModifiers(Modifier.PUBLIC)
                .returns(returnType)
                .addAnnotation(mappingBuilder.build());

        if (!op.rolesAllowed().isEmpty()) {
            AnnotationSpec.Builder ra = AnnotationSpec.builder(ROLES_ALLOWED);
            for (String role : op.rolesAllowed()) {
                ra.addMember("value", "$S", role);
            }
            builder.addAnnotation(ra.build());
        }

        for (int i = 0; i < op.params().size(); i++) {
            ResolvedParam p = op.params().get(i);
            if (p.isContextParam()) { continue; }
            ParameterSpec.Builder paramBuilder = ParameterSpec.builder(p.typeName(), p.name());

            if (p.hasValid()) {
                paramBuilder.addAnnotation(VALID);
            }

            if (pathParamPositions.contains(i)) {
                String pathName = p.pathParamName() != null ? p.pathParamName() : p.name();
                paramBuilder.addAnnotation(AnnotationSpec.builder(PATH_VARIABLE)
                        .addMember("value", "$S", pathName).build());
            } else if (p.isHeaderParam()) {
                String headerName = p.headerParamName() != null ? p.headerParamName() : p.name();
                paramBuilder.addAnnotation(AnnotationSpec.builder(REQUEST_HEADER)
                        .addMember("value", "$S", headerName).build());
            } else if (i == bodyParamIndex) {
                paramBuilder.addAnnotation(REQUEST_BODY);
            } else {
                String qpName = p.restName() != null ? p.restName() : p.name();
                paramBuilder.addAnnotation(AnnotationSpec.builder(REQUEST_PARAM)
                        .addMember("value", "$S", qpName).build());
            }

            builder.addParameter(paramBuilder.build());
        }

        String args = op.params().stream()
                .map(p -> p.isContextParam() ? contextParamResolution(p.contextParamKey()) : p.name())
                .reduce((a, b) -> a + ", " + b).orElse("");
        String delegateCall = fieldName + "." + op.methodName() + "(" + args + ")";

        builder.addCode(generateResponseCode(op.returnTypeStr(), delegateCall, false,
                op.restStatusOverride(), !pathParams.isEmpty()));

        return builder.build();
    }

    private CodeBlock generateResponseCode(String returnType, String delegateCall,
                                            boolean isMutation, int restStatusOverride, boolean hasPathParam) {
        CodeBlock.Builder code = CodeBlock.builder();

        if ("void".equals(returnType)) {
            int status = restStatusOverride > 0 ? restStatusOverride : 204;
            code.addStatement("$L", delegateCall);
            if (status == 204) {
                code.addStatement("return $T.noContent().build()", RESPONSE_ENTITY);
            } else {
                code.addStatement("return $T.status($L).build()", RESPONSE_ENTITY, status);
            }
            return code.build();
        }

        if (returnType.startsWith("Optional<")) {
            code.addStatement("return $L.map(v -> $T.ok((Object) v)).orElse($T.notFound().build())",
                    delegateCall, RESPONSE_ENTITY, RESPONSE_ENTITY);
            return code.build();
        }

        if (hasPathParam && !GeneratorUtils.isCollectionType(returnType) && !GeneratorUtils.isPrimitiveType(returnType)) {
            code.addStatement("var result = $L", delegateCall);
            code.beginControlFlow("if (result == null)");
            code.addStatement("return $T.notFound().build()", RESPONSE_ENTITY);
            code.endControlFlow();
            if (restStatusOverride > 0) {
                code.addStatement("return $T.status($L).body(result)", RESPONSE_ENTITY, restStatusOverride);
            } else {
                code.addStatement("return $T.ok(result)", RESPONSE_ENTITY);
            }
            return code.build();
        }

        if (restStatusOverride > 0) {
            code.addStatement("return $T.status($L).body($L)", RESPONSE_ENTITY, restStatusOverride, delegateCall);
            return code.build();
        }

        if (isMutation) {
            code.addStatement("return $T.status(201).body($L)", RESPONSE_ENTITY, delegateCall);
            return code.build();
        }

        code.addStatement("return $T.ok($L)", RESPONSE_ENTITY, delegateCall);
        return code.build();
    }

    private static String contextParamResolution(String key) {
        return switch (key) {
            case "tenancyId" -> "currentPrincipal.tenancyId()";
            case "actorId" -> "currentPrincipal.actorId()";
            default -> throw new IllegalArgumentException("Unknown @ContextParam key: " + key);
        };
    }
}
