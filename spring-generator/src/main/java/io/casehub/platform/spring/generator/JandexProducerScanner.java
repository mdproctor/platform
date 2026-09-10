package io.casehub.platform.spring.generator;

import org.jboss.jandex.AnnotationInstance;
import org.jboss.jandex.ClassInfo;
import org.jboss.jandex.DotName;
import org.jboss.jandex.Index;
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

    public List<ProducerDescriptor> scan(Index index) {
        List<ProducerDescriptor> result = new ArrayList<>();

        for (AnnotationInstance produces : index.getAnnotations(PRODUCES)) {
            if (produces.target().kind() != org.jboss.jandex.AnnotationTarget.Kind.METHOD) {
                continue;
            }

            MethodInfo method = produces.target().asMethod();
            ClassInfo declaringClass = method.declaringClass();

            boolean isDefaultBean = method.hasAnnotation(DEFAULT_BEAN);
            boolean isAlternative = method.hasAnnotation(ALTERNATIVE);
            int priority = 0;
            AnnotationInstance priorityAnn = method.annotation(PRIORITY);
            if (priorityAnn != null) {
                priority = priorityAnn.value().asInt();
            }

            List<ProducerDescriptor.ParameterDescriptor> params = new ArrayList<>();
            for (MethodParameterInfo param : method.parameters()) {
                String paramType = param.type().name().toString();
                String paramName = param.name() != null ? param.name() : inferParamName(paramType);

                String configProp = null;
                AnnotationInstance configAnn = param.annotation(CONFIG_PROPERTY);
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
                    priority));
        }

        return result;
    }

    private String inferParamName(String typeName) {
        String simple = typeName.substring(typeName.lastIndexOf('.') + 1);
        return Character.toLowerCase(simple.charAt(0)) + simple.substring(1);
    }
}
