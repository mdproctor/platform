package io.casehub.platform.graphql.spring.generator;

import io.casehub.platform.generator.AbstractVerifyMojo;
import io.casehub.platform.generator.DomainScanResult;
import io.casehub.platform.generator.McpDomainJandexScanner;
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
public class GraphqlSpringVerifyMojo extends AbstractVerifyMojo {

    @Parameter(defaultValue = "${project.build.directory}/generated-sources/graphql-spring-generator")
    private File outputDirectory;

    @Parameter(defaultValue = "${project.basedir}/src/main/java")
    private File sourceDir;

    @Override
    protected File getOutputDirectory() { return outputDirectory; }

    @Override
    protected String getGeneratorName() { return "graphql-spring-generator"; }

    @Override
    protected Set<String> collectSourceTypes(IndexView index) {
        var scanner = new McpDomainJandexScanner();
        return scanner.scan(index).stream()
                .map(DomainScanResult::domainName)
                .map(name -> name.replace("-", "").replace("/", "").toLowerCase())
                .collect(Collectors.toSet());
    }

    @Override
    protected Set<String> collectTargetTypes() {
        Set<String> types = new HashSet<>();
        collectDomainTypes(sourceDir.toPath(), types);
        collectDomainTypes(outputDirectory.toPath(), types);
        return types;
    }

    private void collectDomainTypes(Path dir, Set<String> types) {
        if (!Files.exists(dir)) { return; }
        try {
            Files.walkFileTree(dir, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    if (file.toString().endsWith(".java")) {
                        String fileName = file.getFileName().toString().replace(".java", "");
                        if (fileName.endsWith("GraphqlController")) {
                            types.add(fileName.replace("GraphqlController", "").toLowerCase());
                        }
                    }
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            getLog().warn("Failed to scan Spring sources: " + e.getMessage());
        }
    }
}
