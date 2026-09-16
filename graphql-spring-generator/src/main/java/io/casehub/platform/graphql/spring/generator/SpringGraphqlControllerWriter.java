package io.casehub.platform.graphql.spring.generator;

import com.palantir.javapoet.AnnotationSpec;
import com.palantir.javapoet.ClassName;
import com.palantir.javapoet.FieldSpec;
import com.palantir.javapoet.JavaFile;
import com.palantir.javapoet.MethodSpec;
import com.palantir.javapoet.ParameterSpec;
import com.palantir.javapoet.TypeSpec;
import io.casehub.platform.generator.DomainScanResult;
import io.casehub.platform.generator.GeneratorUtils;
import io.casehub.platform.generator.ResolvedOperation;
import io.casehub.platform.generator.ResolvedParam;

import javax.lang.model.element.Modifier;

public class SpringGraphqlControllerWriter {

    private static final ClassName CONTROLLER = ClassName.get("org.springframework.stereotype", "Controller");
    private static final ClassName QUERY_MAPPING = ClassName.get("org.springframework.graphql.data.method.annotation", "QueryMapping");
    private static final ClassName MUTATION_MAPPING = ClassName.get("org.springframework.graphql.data.method.annotation", "MutationMapping");
    private static final ClassName SUBSCRIPTION_MAPPING = ClassName.get("org.springframework.graphql.data.method.annotation", "SubscriptionMapping");
    private static final ClassName ARGUMENT = ClassName.get("org.springframework.graphql.data.method.annotation", "Argument");

    public JavaFile generate(DomainScanResult domain, String targetPackage) {
        String className = GeneratorUtils.toPascalCase(domain.domainName()) + "GraphqlController";
        ClassName spiType = ClassName.bestGuess(domain.declaringTypeFqcn());
        String fieldName = GeneratorUtils.decapitalize(domain.declaringTypeSimple());

        TypeSpec.Builder classBuilder = TypeSpec.classBuilder(className)
                .addModifiers(Modifier.PUBLIC)
                .addAnnotation(CONTROLLER);

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
            classBuilder.addMethod(buildMethod(op, fieldName));
        }

        return JavaFile.builder(targetPackage, classBuilder.build()).build();
    }

    private MethodSpec buildMethod(ResolvedOperation op, String fieldName) {
        ClassName mappingAnnotation = switch (op.type()) {
            case QUERY -> QUERY_MAPPING;
            case MUTATION -> MUTATION_MAPPING;
            case STREAM -> SUBSCRIPTION_MAPPING;
        };

        MethodSpec.Builder builder = MethodSpec.methodBuilder(op.methodName())
                .addModifiers(Modifier.PUBLIC)
                .returns(op.returnTypeName())
                .addAnnotation(mappingAnnotation);

        for (ResolvedParam param : op.params()) {
            if (param.isContextParam()) { continue; }
            builder.addParameter(ParameterSpec.builder(param.typeName(), param.name())
                    .addAnnotation(AnnotationSpec.builder(ARGUMENT)
                            .addMember("name", "$S", param.name())
                            .build())
                    .build());
        }

        String args = op.params().stream()
                .map(p -> p.isContextParam() ? contextParamResolution(p.contextParamKey()) : p.name())
                .reduce((a, b) -> a + ", " + b)
                .orElse("");

        if (op.returnTypeStr().equals("void")) {
            builder.addStatement("$L.$L($L)", fieldName, op.methodName(), args);
        } else {
            builder.addStatement("return $L.$L($L)", fieldName, op.methodName(), args);
        }

        return builder.build();
    }

    private static String contextParamResolution(String key) {
        return switch (key) {
            case "tenancyId" -> "currentPrincipal.tenancyId()";
            case "actorId" -> "currentPrincipal.actorId()";
            default -> throw new IllegalArgumentException("Unknown @ContextParam key: " + key);
        };
    }
}
