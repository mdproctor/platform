package io.casehub.platform.spring.generator;

import org.jboss.jandex.AnnotationInstance;
import org.jboss.jandex.ClassInfo;
import org.jboss.jandex.DotName;
import org.jboss.jandex.IndexView;
import org.jboss.jandex.MethodInfo;
import org.jboss.jandex.MethodParameterInfo;
import org.jboss.jandex.Type;

import java.util.ArrayList;
import java.util.List;

public class JandexProducerScanner {

    private static final DotName PRODUCES = DotName.createSimple("jakarta.enterprise.inject.Produces");
    private static final DotName DEFAULT_BEAN = DotName.createSimple("io.quarkus.arc.DefaultBean");
    private static final DotName ALTERNATIVE = DotName.createSimple("jakarta.enterprise.inject.Alternative");
    private static final DotName PRIORITY = DotName.createSimple("jakarta.annotation.Priority");
    private static final DotName CONFIG_PROPERTY = DotName.createSimple("org.eclipse.microprofile.config.inject.ConfigProperty");
    private static final DotName CDI_INSTANCE = DotName.createSimple("jakarta.enterprise.inject.Instance");
    private static final DotName CDI_EVENT = DotName.createSimple("jakarta.enterprise.event.Event");
    private static final DotName INJECT = DotName.createSimple("jakarta.inject.Inject");
    private static final DotName CONFIG_MAPPING = DotName.createSimple("io.smallrye.config.ConfigMapping");

    private static final java.util.Set<DotName> KNOWN_METHOD_ANNOTATIONS = java.util.Set.of(
            PRODUCES, DEFAULT_BEAN, ALTERNATIVE, PRIORITY,
            DotName.createSimple("jakarta.enterprise.context.ApplicationScoped"),
            DotName.createSimple("jakarta.inject.Singleton"),
            DotName.createSimple("jakarta.enterprise.context.Dependent"),
            DotName.createSimple("jakarta.enterprise.context.RequestScoped"));

    public List<ProducerDescriptor> scan(IndexView index) {
        List<ProducerDescriptor> result = new ArrayList<>();

        for (AnnotationInstance produces : index.getAnnotations(PRODUCES)) {
            if (produces.target().kind() != org.jboss.jandex.AnnotationTarget.Kind.METHOD) {
                continue;
            }

            MethodInfo method         = produces.target().asMethod();
            ClassInfo  declaringClass = method.declaringClass();

            boolean            isDefaultBean = method.hasAnnotation(DEFAULT_BEAN);
            boolean            isAlternative = method.hasAnnotation(ALTERNATIVE);
            int                priority      = 0;
            AnnotationInstance priorityAnn   = method.annotation(PRIORITY);
            if (priorityAnn != null) {
                priority = priorityAnn.value().asInt();
            }

            boolean hasCdiDeps = false;

            // Skip factory-produced JDK types — abstract/interface, can't be new'd
            DotName returnTypeName = method.returnType().name();
            if (returnTypeName.toString().startsWith("java.")) {
                hasCdiDeps = true;
            }

            // Skip methods from classes with @Inject fields (field-injected CDI beans)
            if (declaringClass.fields().stream()
                              .anyMatch(f -> f.hasAnnotation(INJECT) || f.hasAnnotation(CONFIG_PROPERTY))) {
                hasCdiDeps = true;
            }

            // Skip methods with CDI qualifier annotations beyond the known set
            if (hasUnknownCdiQualifiers(method)) {
                hasCdiDeps = true;
            }

            List<ProducerDescriptor.ParameterDescriptor> params = new ArrayList<>();
            for (MethodParameterInfo param : method.parameters()) {
                DotName typeName = param.type().kind() == Type.Kind.PARAMETERIZED_TYPE
                                   ? param.type().asParameterizedType().name()
                                   : param.type().name();

                if (CDI_INSTANCE.equals(typeName) || CDI_EVENT.equals(typeName)) {
                    hasCdiDeps = true;
                }

                ClassInfo paramTypeInfo = index.getClassByName(typeName);
                if (paramTypeInfo != null && paramTypeInfo.hasAnnotation(CONFIG_MAPPING)) {
                    hasCdiDeps = true;
                }

                boolean hasQuarkusQualifier = param.annotations().stream()
                                                   .anyMatch(a -> a.name().toString().startsWith("io.quarkus."));

                if (hasQuarkusQualifier) {
                    hasCdiDeps = true;
                }

                String paramType = param.type().name().toString();
                String paramName = param.name() != null ? param.name() : inferParamName(paramType);

                String             configProp = null;
                AnnotationInstance configAnn  = param.annotation(CONFIG_PROPERTY);
                if (configAnn != null && configAnn.value("name") != null) {
                    configProp = configAnn.value("name").asString();
                }

                params.add(new ProducerDescriptor.ParameterDescriptor(paramType, paramName, configProp));
            }

            result.add(new ProducerDescriptor(
                    declaringClass.name().toString(),
                    method.name(),
                    method.returnType().name().toString(),
                    params,
                    isDefaultBean,
                    isAlternative,
                    priority,
                    hasCdiDeps));
        }

        return result;
    }

    private String inferParamName(String typeName) {
        String simple = typeName.substring(typeName.lastIndexOf('.') + 1);
        return Character.toLowerCase(simple.charAt(0)) + simple.substring(1);
    }

    private boolean hasUnknownCdiQualifiers(MethodInfo method) {
        for (AnnotationInstance ann : method.annotations()) {
            if (ann.target().kind() != org.jboss.jandex.AnnotationTarget.Kind.METHOD) {
                continue;
            }
            DotName name = ann.name();
            if (!KNOWN_METHOD_ANNOTATIONS.contains(name)
                && !name.toString().startsWith("java.")
                && !name.toString().startsWith("javax.")) {
                return true;
            }
        }
        return false;
    }

}
