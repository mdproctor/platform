package io.casehub.platform.spring.generator;

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
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Mojo(name = "verify", defaultPhase = LifecyclePhase.VERIFY)
public class SpringVerifyMojo extends AbstractVerifyMojo {

    private static final Pattern BEAN_RETURN_TYPE = Pattern.compile(
            "public\\s+([\\w.]+)\\s+\\w+\\s*\\(");

    @Parameter(defaultValue = "${project.build.directory}/generated-sources/spring-generator")
    private File outputDirectory;

    @Parameter(defaultValue = "${project.basedir}/src/main/java")
    private File sourceDir;

    @Override
    protected File getOutputDirectory() { return outputDirectory; }

    @Override
    protected String getGeneratorName() { return "spring-generator"; }

    @Override
    protected Set<String> collectSourceTypes(IndexView index) {
        var scanner = new JandexProducerScanner();
        List<ProducerDescriptor> quarkusProducers = scanner.scan(index);
        Set<String> types = new HashSet<>();
        for (ProducerDescriptor d : quarkusProducers) {
            if (!d.requiresManualConfig()) {
                types.add(d.returnTypeSimpleName());
            }
        }
        return types;
    }

    @Override
    protected Set<String> collectTargetTypes() {
        Set<String> types = new HashSet<>();
        try {
            collectBeanReturnTypes(sourceDir.toPath(), types);
            collectBeanReturnTypes(outputDirectory.toPath(), types);
        } catch (IOException e) {
            getLog().warn("Failed to scan Spring sources: " + e.getMessage());
        }
        return types;
    }

    private void collectBeanReturnTypes(Path dir, Set<String> types) throws IOException {
        if (!Files.exists(dir)) { return; }
        Files.walkFileTree(dir, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                if (file.toString().endsWith(".java")) {
                    String content = Files.readString(file);
                    if (content.contains("@Bean")) {
                        Matcher m = BEAN_RETURN_TYPE.matcher(content);
                        while (m.find()) {
                            String type = m.group(1);
                            int dot = type.lastIndexOf('.');
                            types.add(dot >= 0 ? type.substring(dot + 1) : type);
                        }
                    }
                }
                return FileVisitResult.CONTINUE;
            }
        });
    }
}
