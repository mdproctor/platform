package io.casehub.platform.rest.spring.generator;

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

import javax.lang.model.element.Modifier;
import java.util.List;

public class RestControllerWriter {

    private static final ClassName REST_CONTROLLER = ClassName.get("org.springframework.web.bind.annotation", "RestController");
    private static final ClassName REQUEST_MAPPING = ClassName.get("org.springframework.web.bind.annotation", "RequestMapping");
    private static final ClassName GET_MAPPING = ClassName.get("org.springframework.web.bind.annotation", "GetMapping");
    private static final ClassName POST_MAPPING = ClassName.get("org.springframework.web.bind.annotation", "PostMapping");
    private static final ClassName PUT_MAPPING = ClassName.get("org.springframework.web.bind.annotation", "PutMapping");
    private static final ClassName DELETE_MAPPING = ClassName.get("org.springframework.web.bind.annotation", "DeleteMapping");
    private static final ClassName PATCH_MAPPING = ClassName.get("org.springframework.web.bind.annotation", "PatchMapping");
    private static final ClassName PATH_VARIABLE = ClassName.get("org.springframework.web.bind.annotation", "PathVariable");
    private static final ClassName REQUEST_PARAM = ClassName.get("org.springframework.web.bind.annotation", "RequestParam");
    private static final ClassName REQUEST_HEADER = ClassName.get("org.springframework.web.bind.annotation", "RequestHeader");
    private static final ClassName REQUEST_BODY = ClassName.get("org.springframework.web.bind.annotation", "RequestBody");
    private static final ClassName RESPONSE_ENTITY = ClassName.get("org.springframework.http", "ResponseEntity");
    private static final ClassName MEDIA_TYPE = ClassName.get("org.springframework.http", "MediaType");
    private static final ClassName OPTIONAL = ClassName.get("java.util", "Optional");
    private static final ClassName HTTP_SERVLET_REQUEST = ClassName.get("jakarta.servlet.http", "HttpServletRequest");

    public JavaFile generate(RestResourceDescriptor descriptor, String targetPackage) {
        String simpleClassName = simpleClassName(descriptor.className());
        String controllerName  = simpleClassName.replace("Resource", "Controller");
        if (controllerName.equals(simpleClassName)) {
            controllerName = simpleClassName + "Controller";
        }

        ClassName delegateType = ClassName.bestGuess(descriptor.delegateTypeName());

        TypeSpec.Builder classBuilder = TypeSpec.classBuilder(controllerName)
                                                .addModifiers(Modifier.PUBLIC)
                                                .addAnnotation(REST_CONTROLLER)
                                                .addAnnotation(buildRequestMappingAnnotation(descriptor));

        classBuilder.addField(FieldSpec.builder(delegateType, descriptor.delegateFieldName(), Modifier.PRIVATE, Modifier.FINAL).build());

        classBuilder.addMethod(MethodSpec.constructorBuilder()
                                         .addModifiers(Modifier.PUBLIC)
                                         .addParameter(delegateType, descriptor.delegateFieldName())
                                         .addStatement("this.$L = $L", descriptor.delegateFieldName(), descriptor.delegateFieldName())
                                         .build());

        for (RestMethodDescriptor method : descriptor.methods()) {
            classBuilder.addMethod(buildMethod(method, descriptor.delegateFieldName(), descriptor.hasContextHeaders()));
        }

        if (descriptor.hasContextHeaders()) {
            classBuilder.addMethod(MethodSpec.methodBuilder("extractHeaders")
                                             .addModifiers(Modifier.PRIVATE, Modifier.STATIC)
                                             .returns(ParameterizedTypeName.get(
                                                     ClassName.get("java.util", "Map"),
                                                     ClassName.get("java.lang", "String"),
                                                     ClassName.get("java.lang", "String")))
                                             .addParameter(HTTP_SERVLET_REQUEST, "request")
                                             .addStatement("$T<$T, $T> headers = new $T<>()",
                                                           java.util.Map.class, String.class, String.class, java.util.HashMap.class)
                                             .addStatement("for (String name : $T.list(request.getHeaderNames())) headers.put(name, request.getHeader(name))",
                                                           ClassName.get("java.util", "Collections"))
                                             .addStatement("return headers")
                                             .build());
        }

        TypeSpec typeSpec = classBuilder.build();
        return JavaFile.builder(targetPackage, typeSpec).build();
    }

    private AnnotationSpec buildRequestMappingAnnotation(RestResourceDescriptor descriptor) {
        AnnotationSpec.Builder builder = AnnotationSpec.builder(REQUEST_MAPPING)
                .addMember("value", "$S", descriptor.path());

        if (descriptor.classProduces().length > 0) {
            builder.addMember("produces", mediaTypeConstant(descriptor.classProduces()[0]));
        }
        if (descriptor.classConsumes().length > 0) {
            builder.addMember("consumes", mediaTypeConstant(descriptor.classConsumes()[0]));
        }

        return builder.build();
    }

    private MethodSpec buildMethod(RestMethodDescriptor method, String delegateFieldName, boolean hasContextHeaders) {
        TypeName returnType = wrapReturnType(method.returnType());

        MethodSpec.Builder builder = MethodSpec.methodBuilder(method.methodName())
                                               .addModifiers(Modifier.PUBLIC)
                                               .returns(returnType)
                                               .addAnnotation(buildHttpMethodAnnotation(method));

        for (RestMethodDescriptor.ParameterDescriptor param : method.parameters()) {
            builder.addParameter(buildParameter(param));
        }

        if (method.needsHeaderInjection()) {
            builder.addParameter(HTTP_SERVLET_REQUEST, "httpRequest");
        }

        builder.addCode(buildMethodBody(method, delegateFieldName, method.needsHeaderInjection()));

        return builder.build();
    }

    private AnnotationSpec buildHttpMethodAnnotation(RestMethodDescriptor method) {
        ClassName mappingAnnotation = switch (method.httpMethod()) {
            case "GET" -> GET_MAPPING;
            case "POST" -> POST_MAPPING;
            case "PUT" -> PUT_MAPPING;
            case "DELETE" -> DELETE_MAPPING;
            case "PATCH" -> PATCH_MAPPING;
            default -> throw new IllegalArgumentException("Unsupported HTTP method: " + method.httpMethod());
        };

        if (method.subPath().isEmpty()) {
            return AnnotationSpec.builder(mappingAnnotation).build();
        }
        return AnnotationSpec.builder(mappingAnnotation)
                .addMember("value", "$S", method.subPath())
                .build();
    }

    private ParameterSpec buildParameter(RestMethodDescriptor.ParameterDescriptor param) {
        ParameterSpec.Builder builder = ParameterSpec.builder(param.type(), param.name());

        switch (param.source()) {
            case PATH -> builder.addAnnotation(AnnotationSpec.builder(PATH_VARIABLE)
                    .addMember("value", "$S", param.annotationValue())
                    .build());
            case QUERY -> builder.addAnnotation(AnnotationSpec.builder(REQUEST_PARAM)
                    .addMember("value", "$S", param.annotationValue())
                    .build());
            case HEADER -> builder.addAnnotation(AnnotationSpec.builder(REQUEST_HEADER)
                    .addMember("value", "$S", param.annotationValue())
                    .build());
            case BODY -> builder.addAnnotation(REQUEST_BODY);
        }

        return builder.build();
    }

    private TypeName wrapReturnType(TypeName originalType) {
        if (originalType.equals(TypeName.VOID)) {
            return ParameterizedTypeName.get(RESPONSE_ENTITY, ClassName.get("java.lang", "Void"));
        }
        if (originalType instanceof ParameterizedTypeName pt && pt.rawType().equals(OPTIONAL)) {
            return ParameterizedTypeName.get(RESPONSE_ENTITY, pt.typeArguments().get(0));
        }
        if (originalType.toString().equals("jakarta.ws.rs.core.Response")) {
            return ParameterizedTypeName.get(RESPONSE_ENTITY, ClassName.OBJECT);
        }
        return ParameterizedTypeName.get(RESPONSE_ENTITY, originalType.box());
    }

    private CodeBlock buildMethodBody(RestMethodDescriptor method, String delegateFieldName, boolean hasContextHeaders) {
        String args = method.parameters().stream()
                            .map(RestMethodDescriptor.ParameterDescriptor::name)
                            .reduce((a, b) -> a + ", " + b)
                            .orElse("");

        if (hasContextHeaders) {
            args = args.isEmpty() ? "extractHeaders(httpRequest)" : args + ", extractHeaders(httpRequest)";
        }

        boolean isResponseReturn = method.returnType().toString().equals("jakarta.ws.rs.core.Response");

        if (method.returnType().equals(TypeName.VOID) || isResponseReturn) {
            return CodeBlock.builder()
                            .addStatement("$L.$L($L)", delegateFieldName, method.methodName(), args)
                            .addStatement("return $T.ok().build()", RESPONSE_ENTITY)
                            .build();
        }

        if (method.returnType() instanceof ParameterizedTypeName pt && pt.rawType().equals(OPTIONAL)) {
            return CodeBlock.builder()
                            .addStatement("return $L.$L($L).map($T::ok).orElse($T.notFound().build())",
                                          delegateFieldName, method.methodName(), args, RESPONSE_ENTITY, RESPONSE_ENTITY)
                            .build();
        }

        return CodeBlock.builder()
                        .addStatement("return $T.ok($L.$L($L))", RESPONSE_ENTITY, delegateFieldName, method.methodName(), args)
                        .build();
    }

    private CodeBlock mediaTypeConstant(String mediaType) {
        return switch (mediaType) {
            case "application/json" -> CodeBlock.of("$T.APPLICATION_JSON_VALUE", MEDIA_TYPE);
            case "application/xml" -> CodeBlock.of("$T.APPLICATION_XML_VALUE", MEDIA_TYPE);
            case "text/plain" -> CodeBlock.of("$T.TEXT_PLAIN_VALUE", MEDIA_TYPE);
            case "application/octet-stream" -> CodeBlock.of("$T.APPLICATION_OCTET_STREAM_VALUE", MEDIA_TYPE);
            case "multipart/form-data" -> CodeBlock.of("$T.MULTIPART_FORM_DATA_VALUE", MEDIA_TYPE);
            default -> CodeBlock.of("$S", mediaType);
        };
    }

    private String simpleClassName(String fqn) {
        int dot = fqn.lastIndexOf('.');
        return dot >= 0 ? fqn.substring(dot + 1) : fqn;
    }
}
