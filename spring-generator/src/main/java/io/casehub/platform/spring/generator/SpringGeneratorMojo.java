package io.casehub.platform.spring.generator;

import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;
import org.jboss.jandex.Index;
import org.jboss.jandex.IndexReader;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

@Mojo(name = "generate", defaultPhase = LifecyclePhase.GENERATE_SOURCES)
public class SpringGeneratorMojo extends AbstractMojo {

    @Parameter(required = true)
    private File quarkusModule;

    @Parameter(defaultValue = "${project.build.directory}/generated-sources/spring-generator")
    private File outputDirectory;

    @Parameter(defaultValue = "${project}")
    private MavenProject project;

    @Override
    public void execute() throws MojoExecutionException {
        File jandexIdx = new File(quarkusModule, "target/classes/META-INF/jandex.idx");
        if (!jandexIdx.exists()) {
            throw new MojoExecutionException(
                    "Jandex index not found at " + jandexIdx.getAbsolutePath()
                    + ". Build the Quarkus module first.");
        }

        try {
            Index index;
            try (var fis = new FileInputStream(jandexIdx)) {
                index = new IndexReader(fis).read();
            }

            var scanner = new JandexProducerScanner();
            List<ProducerDescriptor> descriptors = scanner.scan(index);

            if (descriptors.isEmpty()) {
                getLog().info("No @Produces methods found — skipping generation.");
                return;
            }

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

            project.addCompileSourceRoot(outputDirectory.getAbsolutePath());

            getLog().info("Generated " + configClassName + " with " + descriptors.size()
                    + " @Bean method(s) from " + jandexIdx.getAbsolutePath());

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
