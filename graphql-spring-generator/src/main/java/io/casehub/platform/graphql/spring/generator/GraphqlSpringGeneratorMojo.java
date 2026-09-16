package io.casehub.platform.graphql.spring.generator;

import com.palantir.javapoet.JavaFile;
import io.casehub.platform.generator.AbstractGeneratorMojo;
import io.casehub.platform.generator.DomainScanResult;
import io.casehub.platform.generator.McpDomainJandexScanner;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.jboss.jandex.IndexView;

import java.io.File;
import java.io.IOException;
import java.util.List;

@Mojo(name = "generate", defaultPhase = LifecyclePhase.GENERATE_SOURCES)
public class GraphqlSpringGeneratorMojo extends AbstractGeneratorMojo {

    @Parameter(defaultValue = "${project.build.directory}/generated-sources/graphql-spring-generator")
    private File outputDirectory;

    @Override
    protected File getOutputDirectory() { return outputDirectory; }

    @Override
    protected String getGeneratorName() { return "graphql-spring-generator"; }

    @Override
    public void execute() throws MojoExecutionException {
        IndexView index = loadJandexIndex();

        var scanner = new McpDomainJandexScanner();
        List<DomainScanResult> domains = scanner.scan(index);

        if (domains.isEmpty()) {
            getLog().info("No @McpDomain types found — skipping generation.");
            return;
        }

        try {
            var graphqlWriter = new SpringGraphqlControllerWriter();
            var restWriter = new SpringDomainRestControllerWriter();
            int count = 0;

            for (DomainScanResult domain : domains) {
                String targetPackage = "io.casehub.platform.graphql.spring.generated";

                JavaFile graphqlFile = graphqlWriter.generate(domain, targetPackage);
                graphqlFile.writeTo(outputDirectory);

                JavaFile restFile = restWriter.generate(domain, targetPackage);
                restFile.writeTo(outputDirectory);

                count += 2;
            }

            registerSourceRoot();
            getLog().info("Generated " + count + " Spring classes from "
                    + domains.size() + " @McpDomain type(s)");

        } catch (IOException e) {
            throw new MojoExecutionException("Failed to generate Spring GraphQL controllers", e);
        }
    }
}
