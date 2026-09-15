package io.casehub.platform.mcp.spring.generator;

import com.palantir.javapoet.JavaFile;
import io.casehub.platform.generator.AbstractGeneratorMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.jboss.jandex.IndexView;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.Map;

@Mojo(name = "generate", defaultPhase = LifecyclePhase.GENERATE_SOURCES)
public class McpSpringGeneratorMojo extends AbstractGeneratorMojo {

    @Parameter(defaultValue = "${project.build.directory}/generated-sources/mcp-spring-generator")
    private File outputDirectory;

    @Override
    protected File getOutputDirectory() { return outputDirectory; }

    @Override
    protected String getGeneratorName() { return "mcp-spring-generator"; }

    @Override
    public void execute() throws MojoExecutionException {
        IndexView index = loadJandexIndex();

        var scanner = new ToolScanner();
        List<ToolDescriptor> tools = scanner.scan(index);

        if (tools.isEmpty()) {
            getLog().info("No @Tool methods found — skipping generation.");
            return;
        }

        try {
            var writer = new ToolConfigWriter();
            Map<String, List<ToolDescriptor>> grouped = writer.groupByClass(tools);
            int count = 0;

            for (var entry : grouped.entrySet()) {
                String sourceClassName = entry.getKey();
                String targetPackage = deriveSpringPackage(sourceClassName);
                JavaFile javaFile = writer.generate(sourceClassName, entry.getValue(), targetPackage);
                javaFile.writeTo(outputDirectory);
                count++;
            }

            registerSourceRoot();
            getLog().info("Generated " + count + " Spring @Configuration class(es) from "
                    + tools.size() + " @Tool method(s)");

        } catch (IOException e) {
            throw new MojoExecutionException("Failed to generate MCP Spring configuration", e);
        }
    }

    static String deriveSpringPackage(String quarkusClassName) {
        int lastDot = quarkusClassName.lastIndexOf('.');
        String basePackage = lastDot >= 0 ? quarkusClassName.substring(0, lastDot) : quarkusClassName;
        return basePackage + ".spring";
    }
}
