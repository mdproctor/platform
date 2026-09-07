package io.casehub.platform.spring.generator;

import java.util.List;

public record ProducerDescriptor(
        String producerClassName,
        String methodName,
        String returnType,
        List<ParameterDescriptor> parameters,
        boolean defaultBean,
        boolean alternative,
        int priority) {

    public record ParameterDescriptor(String type, String name, String configProperty) {

        public boolean isConfigProperty() {
            return configProperty != null;
        }
    }

    public boolean hasConfigProperties() {
        return parameters.stream().anyMatch(ParameterDescriptor::isConfigProperty);
    }

    public String returnTypeSimpleName() {
        int dot = returnType.lastIndexOf('.');
        return dot >= 0 ? returnType.substring(dot + 1) : returnType;
    }
}
