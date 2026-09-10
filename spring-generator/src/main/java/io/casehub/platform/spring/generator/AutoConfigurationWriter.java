package io.casehub.platform.spring.generator;

import java.io.IOException;
import java.io.Writer;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

public class AutoConfigurationWriter {

    public String generate(String packageName, String className, List<ProducerDescriptor> descriptors) {
        var sb = new StringBuilder();

        sb.append("package ").append(packageName).append(";\n\n");

        Set<String> imports = collectImports(descriptors);
        imports.add("org.springframework.boot.autoconfigure.AutoConfiguration");
        imports.add("org.springframework.context.annotation.Bean");
        for (ProducerDescriptor d : descriptors) {
            if (d.defaultBean()) {
                imports.add("org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean");
            }
            if (d.alternative()) {
                imports.add("org.springframework.context.annotation.Primary");
            }
        }
        imports.add("org.springframework.boot.autoconfigure.condition.ConditionalOnClass");

        for (String imp : imports.stream().sorted().collect(Collectors.toList())) {
            sb.append("import ").append(imp).append(";\n");
        }
        sb.append("\n");

        String anchorType = descriptors.isEmpty() ? "Object" :
                descriptors.get(0).returnTypeSimpleName();
        sb.append("@AutoConfiguration\n");
        sb.append("@ConditionalOnClass(").append(anchorType).append(".class)\n");
        sb.append("public class ").append(className).append(" {\n");

        for (ProducerDescriptor d : descriptors) {
            if (d.hasConfigProperties()) continue;
            sb.append("\n");
            sb.append("    @Bean\n");
            if (d.defaultBean()) {
                sb.append("    @ConditionalOnMissingBean\n");
            }
            if (d.alternative()) {
                sb.append("    @Primary\n");
            }

            sb.append("    public ").append(d.returnTypeSimpleName()).append(" ");
            sb.append(d.methodName()).append("(");

            List<ProducerDescriptor.ParameterDescriptor> params = d.parameters();
            for (int i = 0; i < params.size(); i++) {
                var p = params.get(i);
                if (i > 0) sb.append(", ");
                if (p.isConfigProperty()) {
                    sb.append("@org.springframework.beans.factory.annotation.Value(\"${")
                      .append(p.configProperty()).append("}\") ");
                }
                sb.append(p.type().substring(p.type().lastIndexOf('.') + 1))
                  .append(" ").append(p.name());
            }

            sb.append(") {\n");
            sb.append("        return new ").append(d.returnTypeSimpleName()).append("(");

            for (int i = 0; i < params.size(); i++) {
                if (i > 0) sb.append(", ");
                sb.append(params.get(i).name());
            }
            sb.append(");\n");
            sb.append("    }\n");
        }

        sb.append("}\n");
        return sb.toString();
    }

    public String generateImportsFile(String packageName, String className) {
        return packageName + "." + className + "\n";
    }

    private Set<String> collectImports(List<ProducerDescriptor> descriptors) {
        Set<String> imports = new LinkedHashSet<>();
        for (ProducerDescriptor d : descriptors) {
            imports.add(d.returnType());
            for (ProducerDescriptor.ParameterDescriptor p : d.parameters()) {
                if (!p.isConfigProperty()) {
                    imports.add(p.type());
                }
            }
        }
        imports.removeIf(t -> t.startsWith("java.lang."));
        return imports;
    }
}
