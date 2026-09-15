package io.casehub.platform.generator;

import org.apache.maven.plugin.MojoExecutionException;
import org.jboss.jandex.Index;
import org.jboss.jandex.IndexView;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AbstractVerifyMojoTest {

    @Test
    void passesWhenAllSourceTypesHaveTargets() throws Exception {
        var mojo = new TestVerifyMojo(Set.of("FooService", "BarService"), Set.of("FooService", "BarService"));
        mojo.execute();
    }

    @Test
    void failsWhenSourceTypeHasNoTarget() {
        var mojo = new TestVerifyMojo(Set.of("FooService", "BarService"), Set.of("FooService"));
        assertThatThrownBy(mojo::execute)
                .isInstanceOf(MojoExecutionException.class)
                .hasMessageContaining("DRIFT DETECTED")
                .hasMessageContaining("BarService");
    }

    @Test
    void allowsExtraTargetTypes() throws Exception {
        var mojo = new TestVerifyMojo(Set.of("FooService"), Set.of("FooService", "ManualBean"));
        mojo.execute();
    }

    static class TestVerifyMojo extends AbstractVerifyMojo {
        private final Set<String> sourceTypes;
        private final Set<String> targetTypes;

        TestVerifyMojo(Set<String> sourceTypes, Set<String> targetTypes) {
            this.sourceTypes = sourceTypes;
            this.targetTypes = targetTypes;
        }

        @Override protected Set<String> collectSourceTypes(IndexView index) { return sourceTypes; }
        @Override protected Set<String> collectTargetTypes() { return targetTypes; }
        @Override protected File getOutputDirectory() { return new File("target"); }
        @Override protected String getGeneratorName() { return "test"; }
        @Override protected Index loadJandexIndex() {
            try { return Index.of(new Class<?>[0]); } catch (java.io.IOException e) { throw new RuntimeException(e); }
        }
    }
}
