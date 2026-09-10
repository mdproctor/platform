package io.casehub.platform.spring.generator;

import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.jboss.jandex.Index;
import org.jboss.jandex.IndexReader;

import java.io.File;
import java.io.FileInputStream;
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
public class SpringVerifyMojo extends AbstractMojo {

    private static final Pattern BEAN_RETURN_TYPE = Pattern.compile(
            "public\\s+(\\w+)\\s+\\w+\\s*\\(");

    @Parameter(required = true)
    private File quarkusModule;

    @Parameter(defaultValue = "${project.build.outputDirectory}")
    private File classesDir;

    @Parameter(defaultValue = "${project.basedir}/src/main/java")
    private File sourceDir;

    @Parameter(defaultValue = "${project.build.directory}/generated-sources/spring-generator")
    private File generatedSourceDir;

    @Override
    public void execute() throws MojoExecutionException {
        File jandexIdx = new File(quarkusModule, "target/classes/META-INF/jandex.idx");
        if (!jandexIdx.exists()) {
            getLog().warn("Jandex index not found — skipping drift verification.");
            return;
        }

        try {
            Index index;
            try (var fis = new FileInputStream(jandexIdx)) {
                index = new IndexReader(fis).read();
            }

            var scanner = new JandexProducerScanner();
            List<ProducerDescriptor> quarkusProducers = scanner.scan(index);

            Set<String> quarkusReturnTypes = new HashSet<>();
            for (ProducerDescriptor d : quarkusProducers) {
                quarkusReturnTypes.add(d.returnTypeSimpleName());
            }

            Set<String> springBeanTypes = new HashSet<>();
            collectBeanReturnTypes(sourceDir.toPath(), springBeanTypes);
            collectBeanReturnTypes(generatedSourceDir.toPath(), springBeanTypes);

            Set<String> gaps = new HashSet<>(quarkusReturnTypes);
            gaps.removeAll(springBeanTypes);

            Set<String> extras = new HashSet<>(springBeanTypes);
            extras.removeAll(quarkusReturnTypes);

            if (!gaps.isEmpty()) {
                throw new MojoExecutionException(
                        "DRIFT DETECTED — Quarkus @Produces beans with no Spring @Bean equivalent: "
                        + gaps + ". Add @Bean methods or update the generator.");
            }

            if (!extras.isEmpty()) {
                getLog().info("Spring-only beans (manual additions): " + extras);
            }

            getLog().info("Drift verification passed: " + quarkusReturnTypes.size()
                    + " Quarkus beans, " + springBeanTypes.size() + " Spring beans.");

        } catch (IOException e) {
            throw new MojoExecutionException("Drift verification failed", e);
        }
    }

    private void collectBeanReturnTypes(Path dir, Set<String> types) throws IOException {
        if (!Files.exists(dir)) return;

        Files.walkFileTree(dir, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                if (file.toString().endsWith(".java")) {
                    String content = Files.readString(file);
                    if (content.contains("@Bean")) {
                        Matcher m = BEAN_RETURN_TYPE.matcher(content);
                        while (m.find()) {
                            types.add(m.group(1));
                        }
                    }
                }
                return FileVisitResult.CONTINUE;
            }
        });
    }
}
