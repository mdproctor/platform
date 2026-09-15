package io.casehub.platform.rest.spring.generator;

import io.casehub.platform.generator.AbstractVerifyMojo;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.jboss.jandex.IndexView;

import java.io.File;
import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.HashSet;
import java.util.Set;
import java.util.stream.Collectors;

@Mojo(name = "verify", defaultPhase = LifecyclePhase.VERIFY)
public class RestVerifyMojo extends AbstractVerifyMojo {

    @Parameter(defaultValue = "${project.build.directory}/generated-sources/rest-spring-generator")
    private File outputDirectory;

    @Parameter(defaultValue = "${project.basedir}/src/main/java")
    private File sourceDir;

    @Override
    protected File getOutputDirectory() { return outputDirectory; }

    @Override
    protected String getGeneratorName() { return "rest-spring-generator"; }

    @Override
    protected Set<String> collectSourceTypes(IndexView index) {
        var scanner = new RestResourceScanner();
        return scanner.scan(index).stream()
                .map(RestResourceDescriptor::className)
                .map(this::simpleClassName)
                .collect(Collectors.toSet());
    }

    @Override
    protected Set<String> collectTargetTypes() {
        Set<String> types = new HashSet<>();
        collectControllerTypes(sourceDir.toPath(), types);
        collectControllerTypes(outputDirectory.toPath(), types);
        return types;
    }

    private void collectControllerTypes(Path dir, Set<String> types) {
        if (!Files.exists(dir)) { return; }
        try {
            Files.walkFileTree(dir, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    if (file.toString().endsWith(".java")) {
                        String content = Files.readString(file);
                        if (content.contains("@RestController")) {
                            String fileName = file.getFileName().toString().replace(".java", "");
                            String normalized = fileName
                                    .replace("Controller", "Resource")
                                    .replace("Spring", "");
                            types.add(normalized);
                        }
                    }
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            getLog().warn("Failed to scan Spring sources: " + e.getMessage());
        }
    }

    private String simpleClassName(String fqn) {
        int dot = fqn.lastIndexOf('.');
        return dot >= 0 ? fqn.substring(dot + 1) : fqn;
    }
}
