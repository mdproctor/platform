package io.casehub.platform.generator;

import org.apache.maven.plugin.MojoExecutionException;
import org.jboss.jandex.IndexView;

import java.util.LinkedHashSet;
import java.util.Set;

public abstract class AbstractVerifyMojo extends AbstractGeneratorMojo {

    protected abstract Set<String> collectSourceTypes(IndexView index);

    protected abstract Set<String> collectTargetTypes();

    @Override
    public void execute() throws MojoExecutionException {
        IndexView index = loadJandexIndex();

        Set<String> sourceTypes = collectSourceTypes(index);
        Set<String> targetTypes = collectTargetTypes();

        Set<String> gaps = new LinkedHashSet<>(sourceTypes);
        gaps.removeAll(targetTypes);

        Set<String> extras = new LinkedHashSet<>(targetTypes);
        extras.removeAll(sourceTypes);

        if (!gaps.isEmpty()) {
            throw new MojoExecutionException(
                    "DRIFT DETECTED — Quarkus types with no Spring equivalent: "
                    + gaps + ". Add Spring equivalents or update the generator.");
        }

        if (!extras.isEmpty()) {
            getLog().info("Spring-only types (manual additions): " + extras);
        }

        getLog().info("Drift verification passed: " + sourceTypes.size()
                + " source types, " + targetTypes.size() + " target types.");
    }
}
