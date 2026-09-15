package io.casehub.platform.spring.generator;

import io.casehub.platform.generator.AbstractGeneratorMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.jboss.jandex.IndexView;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

@Mojo(name = "generate", defaultPhase = LifecyclePhase.GENERATE_SOURCES)
public class SpringGeneratorMojo extends AbstractGeneratorMojo {

    @Parameter(defaultValue = "${project.build.directory}/generated-sources/spring-generator")
    private File outputDirectory;

    @Override
    protected File getOutputDirectory() { return outputDirectory; }

    @Override
    protected String getGeneratorName() { return "spring-generator"; }

    @Override
    public void execute() throws MojoExecutionException {
        IndexView index = loadJandexIndex();

        var scanner = new JandexProducerScanner();
        List<ProducerDescriptor> descriptors = scanner.scan(index);

        if (descriptors.isEmpty()) {
            getLog().info("No @Produces methods found — skipping generation.");
            return;
        }

        try {
            String sourcePackage = deriveSpringPackage(descriptors.get(0).producerClassName());
            String configClassName = deriveConfigClassName(quarkusModule.getName());

            var writer = new AutoConfigurationWriter();
            String source = writer.generate(sourcePackage, configClassName, descriptors);

            Path sourceDir = outputDirectory.toPath()
                    .resolve(sourcePackage.replace('.', '/'));
            Files.createDirectories(sourceDir);
            Files.writeString(sourceDir.resolve(configClassName + ".java"), source);

            Path metaInf = outputDirectory.toPath()
                    .resolve("META-INF/spring");
            Files.createDirectories(metaInf);
            Files.writeString(
                    metaInf.resolve("org.springframework.boot.autoconfigure.AutoConfiguration.imports"),
                    writer.generateImportsFile(sourcePackage, configClassName));

            registerSourceRoot();

            getLog().info("Generated " + configClassName + " with " + descriptors.size()
                    + " @Bean method(s)");

        } catch (IOException e) {
            throw new MojoExecutionException("Failed to generate Spring auto-configuration", e);
        }
    }

    private String deriveSpringPackage(String quarkusClassName) {
        int lastDot = quarkusClassName.lastIndexOf('.');
        String basePackage = lastDot >= 0 ? quarkusClassName.substring(0, lastDot) : quarkusClassName;
        if (basePackage.endsWith(".quarkus")) {
            basePackage = basePackage.substring(0, basePackage.length() - ".quarkus".length());
        }
        return basePackage + ".spring";
    }

    String deriveConfigClassName(String moduleName) {
        String[] parts = moduleName.replace("platform-", "").split("-");
        var sb = new StringBuilder();
        for (String part : parts) {
            if (!part.isEmpty()) {
                sb.append(Character.toUpperCase(part.charAt(0)));
                sb.append(part.substring(1));
            }
        }
        sb.append("AutoConfiguration");
        return sb.toString();
    }
}
