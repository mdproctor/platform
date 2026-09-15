package io.casehub.platform.rest.spring.generator;

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

@Mojo(name = "generate", defaultPhase = LifecyclePhase.GENERATE_SOURCES)
public class RestGeneratorMojo extends AbstractGeneratorMojo {

    @Parameter(defaultValue = "${project.build.directory}/generated-sources/rest-spring-generator")
    private File outputDirectory;

    @Override
    protected File getOutputDirectory() { return outputDirectory; }

    @Override
    protected String getGeneratorName() { return "rest-spring-generator"; }

    @Override
    public void execute() throws MojoExecutionException {
        IndexView index = loadJandexIndex();

        var scanner = new RestResourceScanner();
        List<RestResourceDescriptor> descriptors = scanner.scan(index);

        if (descriptors.isEmpty()) {
            getLog().info("No @Path resources found — skipping generation.");
            return;
        }

        var providerScanner = new ProviderScanner();
        List<ProviderDescriptor> providers = providerScanner.scan(index);

        try {
            var writer = new RestControllerWriter();
            int count = 0;
            for (RestResourceDescriptor desc : descriptors) {
                String targetPackage = deriveSpringPackage(desc.className());
                JavaFile javaFile = writer.generate(desc, targetPackage);
                javaFile.writeTo(outputDirectory);
                count++;
            }

            var providerWriter = new ProviderWriter();
            for (ProviderDescriptor provider : providers) {
                String targetPackage = deriveSpringPackage(provider.className());
                JavaFile javaFile = providerWriter.generate(provider, targetPackage);
                javaFile.writeTo(outputDirectory);
                count++;
            }

            registerSourceRoot();
            getLog().info("Generated " + count + " Spring class(es) ("
                    + descriptors.size() + " controllers, " + providers.size() + " providers)");

        } catch (IOException e) {
            throw new MojoExecutionException("Failed to generate REST controllers", e);
        }
    }

    static String deriveSpringPackage(String quarkusClassName) {
        int lastDot = quarkusClassName.lastIndexOf('.');
        String basePackage = lastDot >= 0 ? quarkusClassName.substring(0, lastDot) : quarkusClassName;
        return basePackage + ".spring";
    }
}
