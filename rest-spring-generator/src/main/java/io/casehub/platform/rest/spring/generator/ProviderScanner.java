package io.casehub.platform.rest.spring.generator;

import org.jboss.jandex.AnnotationInstance;
import org.jboss.jandex.ClassInfo;
import org.jboss.jandex.DotName;
import org.jboss.jandex.IndexView;
import org.jboss.jandex.Type;

import java.util.ArrayList;
import java.util.List;

public class ProviderScanner {

    private static final DotName EXCEPTION_MAPPER = DotName.createSimple("jakarta.ws.rs.ext.ExceptionMapper");
    private static final DotName CONTAINER_REQUEST_FILTER = DotName.createSimple("jakarta.ws.rs.container.ContainerRequestFilter");
    private static final DotName CONTAINER_RESPONSE_FILTER = DotName.createSimple("jakarta.ws.rs.container.ContainerResponseFilter");
    private static final DotName PARAM_CONVERTER_PROVIDER = DotName.createSimple("jakarta.ws.rs.ext.ParamConverterProvider");
    private static final DotName PRIORITY = DotName.createSimple("jakarta.annotation.Priority");

    public List<ProviderDescriptor> scan(IndexView index) {
        List<ProviderDescriptor> result = new ArrayList<>();

        for (ClassInfo classInfo : index.getKnownClasses()) {
            if (classInfo.isInterface()) {
                continue;
            }

            if (implementsInterface(classInfo, index, EXCEPTION_MAPPER)) {
                String targetType = extractTypeArgument(classInfo, EXCEPTION_MAPPER);
                result.add(new ProviderDescriptor(
                        classInfo.name().toString(),
                        ProviderDescriptor.ProviderKind.EXCEPTION_MAPPER,
                        targetType,
                        extractPriority(classInfo)));
            }

            if (implementsInterface(classInfo, index, CONTAINER_REQUEST_FILTER)) {
                result.add(new ProviderDescriptor(
                        classInfo.name().toString(),
                        ProviderDescriptor.ProviderKind.REQUEST_FILTER,
                        null,
                        extractPriority(classInfo)));
            }

            if (implementsInterface(classInfo, index, CONTAINER_RESPONSE_FILTER)) {
                result.add(new ProviderDescriptor(
                        classInfo.name().toString(),
                        ProviderDescriptor.ProviderKind.RESPONSE_FILTER,
                        null,
                        extractPriority(classInfo)));
            }

            if (implementsInterface(classInfo, index, PARAM_CONVERTER_PROVIDER)) {
                result.add(new ProviderDescriptor(
                        classInfo.name().toString(),
                        ProviderDescriptor.ProviderKind.PARAM_CONVERTER,
                        null,
                        extractPriority(classInfo)));
            }
        }

        return result;
    }

    private boolean implementsInterface(ClassInfo classInfo, IndexView index, DotName interfaceName) {
        return classInfo.interfaceNames().contains(interfaceName);
    }

    private String extractTypeArgument(ClassInfo classInfo, DotName interfaceName) {
        for (Type iface : classInfo.interfaceTypes()) {
            if (iface.name().equals(interfaceName) && iface.kind() == Type.Kind.PARAMETERIZED_TYPE) {
                List<Type> args = iface.asParameterizedType().arguments();
                if (!args.isEmpty()) {
                    return args.get(0).name().toString();
                }
            }
        }
        return null;
    }

    private int extractPriority(ClassInfo classInfo) {
        AnnotationInstance priorityAnn = classInfo.annotation(PRIORITY);
        if (priorityAnn != null && priorityAnn.value() != null) {
            return priorityAnn.value().asInt();
        }
        return 0;
    }
}
