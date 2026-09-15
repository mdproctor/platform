package io.casehub.platform.mcp.spring.generator;

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
public class McpSpringVerifyMojo extends AbstractVerifyMojo {

    @Parameter(defaultValue = "${project.build.directory}/generated-sources/mcp-spring-generator")
    private File outputDirectory;

    @Parameter(defaultValue = "${project.basedir}/src/main/java")
    private File sourceDir;

    @Override
    protected File getOutputDirectory() { return outputDirectory; }

    @Override
    protected String getGeneratorName() { return "mcp-spring-generator"; }

    @Override
    protected Set<String> collectSourceTypes(IndexView index) {
        var scanner = new ToolScanner();
        return scanner.scan(index).stream()
                .map(ToolDescriptor::className)
                .map(this::simpleClassName)
                .collect(Collectors.toSet());
    }

    @Override
    protected Set<String> collectTargetTypes() {
        Set<String> types = new HashSet<>();
        collectConfigTypes(sourceDir.toPath(), types);
        collectConfigTypes(outputDirectory.toPath(), types);
        return types;
    }

    private void collectConfigTypes(Path dir, Set<String> types) {
        if (!Files.exists(dir)) { return; }
        try {
            Files.walkFileTree(dir, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    if (file.toString().endsWith(".java")) {
                        String fileName = file.getFileName().toString().replace(".java", "");
                        if (fileName.startsWith("Spring")) {
                            types.add(fileName.substring("Spring".length()));
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
