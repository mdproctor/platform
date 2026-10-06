package io.casehub.yaml.plugin.processor;

import com.google.testing.compile.Compilation;
import com.google.testing.compile.JavaFileObjects;
import org.junit.jupiter.api.Test;

import javax.tools.JavaFileObject;
import javax.tools.StandardLocation;
import java.io.IOException;

import static com.google.testing.compile.CompilationSubject.assertThat;
import static com.google.testing.compile.Compiler.javac;
import static org.assertj.core.api.Assertions.assertThat;

class StepPluginProcessorTest {

    @Test
    void validPluginCompiles() {
        Compilation compilation = javac()
            .withProcessors(new StepPluginProcessor())
            .compile(JavaFileObjects.forResource("test-plugins/ValidPlugin.java"));
        assertThat(compilation).succeededWithoutWarnings();
    }

    @Test
    void missingExecuteMethodFails() {
        Compilation compilation = javac()
            .withProcessors(new StepPluginProcessor())
            .compile(JavaFileObjects.forResource("test-plugins/MissingExecutePlugin.java"));
        assertThat(compilation).failed();
        assertThat(compilation).hadErrorContaining("must have exactly one @Execute method");
    }

    @Test
    void wrongReturnTypeFails() {
        Compilation compilation = javac()
            .withProcessors(new StepPluginProcessor())
            .compile(JavaFileObjects.forResource("test-plugins/WrongReturnTypePlugin.java"));
        assertThat(compilation).failed();
        assertThat(compilation).hadErrorContaining("must return Result");
    }

    @Test
    void generatesStepActionImplementation() {
        Compilation compilation = javac()
            .withProcessors(new StepPluginProcessor())
            .compile(JavaFileObjects.forResource("test-plugins/ValidPlugin.java"));
        assertThat(compilation).succeededWithoutWarnings();
        assertThat(compilation).generatedSourceFile("test.plugins.ValidPluginAction");
    }

    @Test
    void generatedActionHasCorrectStructure() throws IOException {
        Compilation compilation = javac()
            .withProcessors(new StepPluginProcessor())
            .compile(JavaFileObjects.forResource("test-plugins/ValidPlugin.java"));
        assertThat(compilation).succeededWithoutWarnings();

        JavaFileObject source = compilation.generatedSourceFile(
            "test.plugins.ValidPluginAction").orElseThrow();
        String content = source.getCharContent(false).toString();

        assertThat(content).contains("implements Action");
        assertThat(content).contains("public Result execute(Map<String, Object> parameters, ServiceRegistry services)");
    }

    @Test
    void generatedActionValidatesRequiredFields() throws IOException {
        Compilation compilation = javac()
            .withProcessors(new StepPluginProcessor())
            .compile(JavaFileObjects.forResource("test-plugins/ValidPlugin.java"));

        JavaFileObject source = compilation.generatedSourceFile(
            "test.plugins.ValidPluginAction").orElseThrow();
        String content = source.getCharContent(false).toString();

        assertThat(content).contains("test-action: 'name' is required");
    }

    @Test
    void generatesSchemaFile() {
        Compilation compilation = javac()
            .withProcessors(new StepPluginProcessor())
            .compile(JavaFileObjects.forResource("test-plugins/ValidPlugin.java"));
        assertThat(compilation).succeededWithoutWarnings();
        assertThat(compilation).generatedFile(
            StandardLocation.CLASS_OUTPUT,
            "META-INF/yaml-plugins/test-action.schema.json");
    }

    @Test
    void schemaMarksRequiredFields() throws IOException {
        Compilation compilation = javac()
            .withProcessors(new StepPluginProcessor())
            .compile(JavaFileObjects.forResource("test-plugins/ValidPlugin.java"));

        JavaFileObject schema = compilation.generatedFile(
            StandardLocation.CLASS_OUTPUT,
            "META-INF/yaml-plugins/test-action.schema.json").orElseThrow();
        String content = schema.getCharContent(false).toString();

        assertThat(content).doesNotContain("\"condition\"");
        assertThat(content).contains("\"name\"");
        assertThat(content).contains("\"required\"");
        assertThat(content).contains("\"type\": \"string\"");
    }

    @Test
    void generatesRegistryManifest() throws IOException {
        Compilation compilation = javac()
            .withProcessors(new StepPluginProcessor())
            .compile(JavaFileObjects.forResource("test-plugins/ValidPlugin.java"));
        assertThat(compilation).succeededWithoutWarnings();

        JavaFileObject registry = compilation.generatedFile(
            StandardLocation.CLASS_OUTPUT,
            "META-INF/yaml-plugins/test-action.json").orElseThrow();
        String content = registry.getCharContent(false).toString();

        assertThat(content).contains("\"name\": \"test-action\"");
        assertThat(content).contains("\"actionClass\": \"test.plugins.ValidPluginAction\"");
    }

    @Test
    void manifestIncludesDefaultCapability() throws IOException {
        Compilation compilation = javac()
                                          .withProcessors(new StepPluginProcessor())
                                          .compile(JavaFileObjects.forResource("test-plugins/ValidPlugin.java"));

        JavaFileObject registry = compilation.generatedFile(
                StandardLocation.CLASS_OUTPUT,
                "META-INF/yaml-plugins/test-action.json").orElseThrow();
        String content = registry.getCharContent(false).toString();

        assertThat(content).contains("\"capability\": \"steps\"");
    }

    @Test
    void manifestIncludesExplicitCapability() throws IOException {
        Compilation compilation = javac()
                                          .withProcessors(new StepPluginProcessor())
                                          .compile(JavaFileObjects.forResource("test-plugins/CapabilityPlugin.java"));
        assertThat(compilation).succeededWithoutWarnings();

        JavaFileObject registry = compilation.generatedFile(
                StandardLocation.CLASS_OUTPUT,
                "META-INF/yaml-plugins/cap-action.json").orElseThrow();
        String content = registry.getCharContent(false).toString();

        assertThat(content).contains("\"capability\": \"orchestration\"");
    }
}
