package io.casehub.platform.generator;

import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;
import org.jboss.jandex.CompositeIndex;
import org.jboss.jandex.Index;
import org.jboss.jandex.IndexReader;
import org.jboss.jandex.IndexView;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public abstract class AbstractGeneratorMojo extends AbstractMojo {

    @Parameter
    protected File quarkusModule;

    @Parameter
    protected List<File> quarkusModules;

    @Parameter(defaultValue = "${project}")
    protected MavenProject project;

    protected abstract File getOutputDirectory();

    protected abstract String getGeneratorName();

    protected IndexView loadJandexIndex() throws MojoExecutionException {
        List<File> modules = resolveModules();
        if (modules.size() == 1) {
            return loadSingleIndex(modules.get(0));
        }
        List<IndexView> indexes = new ArrayList<>();
        for (File module : modules) {
            indexes.add(loadSingleIndex(module));
        }
        return CompositeIndex.create(indexes);
    }

    private List<File> resolveModules() throws MojoExecutionException {
        if (quarkusModules != null && !quarkusModules.isEmpty()) {
            return quarkusModules;
        }
        if (quarkusModule != null) {
            return List.of(quarkusModule);
        }
        throw new MojoExecutionException(
                "Either <quarkusModule> or <quarkusModules> must be configured.");
    }

    private Index loadSingleIndex(File module) throws MojoExecutionException {
        File jandexIdx = new File(module, "target/classes/META-INF/jandex.idx");
        if (!jandexIdx.exists()) {
            throw new MojoExecutionException(
                    "Jandex index not found at " + jandexIdx.getAbsolutePath()
                    + ". Build the Quarkus module first.");
        }
        try (var fis = new FileInputStream(jandexIdx)) {
            return new IndexReader(fis).read();
        } catch (IOException e) {
            throw new MojoExecutionException("Failed to read Jandex index", e);
        }
    }

    protected void registerSourceRoot() {
        project.addCompileSourceRoot(getOutputDirectory().getAbsolutePath());
    }
}
