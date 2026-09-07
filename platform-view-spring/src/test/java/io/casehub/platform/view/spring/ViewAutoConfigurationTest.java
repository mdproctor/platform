package io.casehub.platform.view.spring;

import io.casehub.platform.api.preferences.MapPreferences;
import io.casehub.platform.api.preferences.PreferenceProvider;
import io.casehub.platform.api.view.SubjectViewStore;
import io.casehub.platform.api.view.ViewMembershipTracker;
import io.casehub.platform.view.SubjectViewEvaluator;
import io.casehub.platform.view.SubjectViewOrchestrator;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class ViewAutoConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(ViewAutoConfiguration.class))
            .withBean(SubjectViewStore.class, () -> new SubjectViewStore() {
                @Override public io.casehub.platform.api.view.SubjectViewSpec save(io.casehub.platform.api.view.SubjectViewSpec spec) { return spec; }
                @Override public Optional<io.casehub.platform.api.view.SubjectViewSpec> findById(UUID id) { return Optional.empty(); }
                @Override public List<io.casehub.platform.api.view.SubjectViewSpec> findByTenancy(String t) { return List.of(); }
                @Override public boolean delete(UUID id) { return false; }
            })
            .withBean(ViewMembershipTracker.class, () -> new ViewMembershipTracker() {
                @Override public Map<UUID, String> getLastKnownMembership(UUID id) { return Map.of(); }
                @Override public void updateMembership(UUID id, Map<UUID, String> m) {}
                @Override public void removeMembership(UUID id) {}
                @Override public Set<UUID> getSubjectsByView(UUID id) { return Set.of(); }
                @Override public void removeMembershipByView(UUID id) {}
            })
            .withBean(PreferenceProvider.class, () -> scope -> new MapPreferences(Map.of()));

    @Test
    void evaluatorBeanCreated() {
        contextRunner.run(context ->
            assertThat(context).hasSingleBean(SubjectViewEvaluator.class));
    }

    @Test
    void orchestratorBeanCreated() {
        contextRunner.run(context ->
            assertThat(context).hasSingleBean(SubjectViewOrchestrator.class));
    }

    @Test
    void beansAreDistinct() {
        contextRunner.run(context -> {
            assertThat(context.getBean(SubjectViewEvaluator.class)).isNotNull();
            assertThat(context.getBean(SubjectViewOrchestrator.class)).isNotNull();
        });
    }
}
