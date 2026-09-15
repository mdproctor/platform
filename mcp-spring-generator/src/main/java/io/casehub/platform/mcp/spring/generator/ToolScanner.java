package io.casehub.platform.mcp.spring.generator;

import io.casehub.platform.generator.JandexTypeConverter;
import org.jboss.jandex.AnnotationInstance;
import org.jboss.jandex.AnnotationTarget;
import org.jboss.jandex.DotName;
import org.jboss.jandex.IndexView;
import org.jboss.jandex.MethodInfo;
import org.jboss.jandex.MethodParameterInfo;

import java.util.ArrayList;
import java.util.List;

public class ToolScanner {

    private static final DotName TOOL = DotName.createSimple("io.quarkiverse.mcp.server.Tool");
    private static final DotName TOOL_ARG = DotName.createSimple("io.quarkiverse.mcp.server.ToolArg");

    public List<ToolDescriptor> scan(IndexView index) {
        List<ToolDescriptor> result = new ArrayList<>();

        for (AnnotationInstance ann : index.getAnnotations(TOOL)) {
            if (ann.target().kind() != AnnotationTarget.Kind.METHOD) {
                continue;
            }

            MethodInfo method = ann.target().asMethod();
            String description = ann.value() != null ? ann.value().asString() : "";

            List<ToolDescriptor.ToolParameterDescriptor> params = new ArrayList<>();
            for (MethodParameterInfo param : method.parameters()) {
                String paramName = param.name() != null ? param.name() : "arg" + params.size();
                String paramDesc = "";

                AnnotationInstance toolArg = findAnnotation(param, TOOL_ARG);
                if (toolArg != null) {
                    if (toolArg.value("description") != null) {
                        paramDesc = toolArg.value("description").asString();
                    }
                    if (toolArg.value() != null && !toolArg.value().asString().isEmpty()) {
                        paramName = toolArg.value().asString();
                    }
                }

                params.add(new ToolDescriptor.ToolParameterDescriptor(
                        paramName,
                        JandexTypeConverter.toTypeName(param.type()),
                        paramDesc));
            }

            result.add(new ToolDescriptor(
                    method.declaringClass().name().toString(),
                    method.name(),
                    description,
                    JandexTypeConverter.toTypeName(method.returnType()),
                    params));
        }

        return result;
    }

    private AnnotationInstance findAnnotation(MethodParameterInfo param, DotName annotationName) {
        for (AnnotationInstance ann : param.annotations()) {
            if (ann.name().equals(annotationName)) {
                return ann;
            }
        }
        return null;
    }
}
