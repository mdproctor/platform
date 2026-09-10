package io.casehub.platform.view.quarkus;

import io.casehub.platform.api.preferences.PreferenceProvider;
import io.casehub.platform.api.view.SubjectViewStore;
import io.casehub.platform.api.view.ViewMembershipTracker;
import io.casehub.platform.view.SubjectViewEvaluator;
import io.casehub.platform.view.SubjectViewOrchestrator;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;

@ApplicationScoped
public class ViewBeans {

    @Produces
    @ApplicationScoped
    public SubjectViewEvaluator subjectViewEvaluator() {
        return new SubjectViewEvaluator();
    }

    @Produces
    @ApplicationScoped
    public SubjectViewOrchestrator subjectViewOrchestrator(
            SubjectViewEvaluator evaluator,
            SubjectViewStore viewStore,
            ViewMembershipTracker tracker,
            PreferenceProvider preferenceProvider) {
        return new SubjectViewOrchestrator(evaluator, viewStore, tracker, preferenceProvider);
    }
}
