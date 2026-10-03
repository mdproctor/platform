package io.casehub.yaml.statemachine.generator;

import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

@Mojo(name = "generate", defaultPhase = LifecyclePhase.GENERATE_SOURCES)
public class StateMachineGeneratorMojo extends AbstractMojo {

    @Parameter(required = true)
    private File sourceDirectory;

    @Parameter(required = true)
    private String targetPackage;

    @Parameter(defaultValue = "${project}", readonly = true)
    private MavenProject project;

    @Override
    public void execute() throws MojoExecutionException {
        if (!sourceDirectory.exists()) {
            getLog().info("No state machine YAML directory: " + sourceDirectory);
            return;
        }

        var outputDir = new File(project.getBuild().getDirectory(),
            "generated-sources/yaml-statemachine");
        project.addCompileSourceRoot(outputDir.getAbsolutePath());

        var resourceDir = new File(project.getBuild().getDirectory(),
            "generated-resources");
        var resource = new org.apache.maven.model.Resource();
        resource.setDirectory(resourceDir.getAbsolutePath());
        project.addResource(resource);

        var files = sourceDirectory.listFiles(
            (dir, name) -> name.endsWith(".scenario.yaml")
                || name.endsWith(".statemachine.yaml"));
        if (files == null || files.length == 0) {
            getLog().info("No state machine YAML files found");
            return;
        }

        for (File yaml : files) {
            try {
                var baseName = yaml.getName()
                    .replace(".scenario.yaml", "")
                    .replace(".statemachine.yaml", "");
                var name = EventEmitter.toPascalCase(baseName);

                generateStateMachine(yaml, name, targetPackage, outputDir);
                writeDescriptor(name, targetPackage, resourceDir);

                getLog().info("Generated state machine: " + name
                    + " → " + targetPackage);
            } catch (Exception e) {
                throw new MojoExecutionException(
                    "Failed to generate from " + yaml.getName(), e);
            }
        }
    }

    static void generateStateMachine(File yamlFile, String name,
            String pkg, File outputDir) throws IOException {
        var model = StateMachineParser.parse(yamlFile, name, pkg);
        var pkgDir = outputDir.toPath().resolve(pkg.replace('.', '/'));
        Files.createDirectories(pkgDir);

        for (var file : new GeneratedFile[]{
                StateEnumEmitter.emit(model),
                EventEmitter.emit(model),
                DispatchEmitter.emit(model)}) {
            Files.writeString(pkgDir.resolve(file.fileName()), file.content());
        }
    }

    static void writeDescriptor(String name, String pkg,
            File resourceDir) throws IOException {
        var dir = resourceDir.toPath().resolve("META-INF/yaml-dispatch");
        Files.createDirectories(dir);

        var content = "dispatch-class=" + pkg + "." + name + "Dispatch\n"
            + "state-enum=" + pkg + "." + name + "State\n"
            + "event-type=" + pkg + "." + name + "Event\n";

        Files.writeString(dir.resolve(name.toLowerCase() + ".properties"), content);
    }
}
