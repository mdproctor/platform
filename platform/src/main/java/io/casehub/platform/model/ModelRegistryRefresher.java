package io.casehub.platform.model;

import io.casehub.platform.api.model.ModelCatalogChangedEvent;
import io.casehub.platform.api.model.ModelDescriptor;
import io.casehub.platform.api.model.ModelSource;
import io.casehub.platform.api.model.RefreshResult;

import java.util.List;
import io.quarkus.runtime.Startup;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Event;
import jakarta.enterprise.inject.Any;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

@ApplicationScoped
public class ModelRegistryRefresher {

    private static final Logger LOG = Logger.getLogger(ModelRegistryRefresher.class);

    @Inject @Any Instance<ModelSource> sources;
    @Inject InMemoryModelRegistry registry;
    @Inject Event<ModelCatalogChangedEvent> catalogChanged;

    @Startup
    void initialRefresh() {
        refreshAll();
    }

    @Scheduled(every = "${casehub.model.registry.refresh-interval:1h}")
    void scheduledRefresh() {
        refreshAll();
    }

    void refreshAll() {refreshAllWithResult();}

    RefreshResult refreshAllWithResult() {
        var sortedSources = new java.util.ArrayList<ModelSource>();
        sources.forEach(sortedSources::add);
        sortedSources.sort(java.util.Comparator.comparingInt(ModelSource::priority));

        int sourcesRefreshed = 0, added = 0, removed = 0, updated = 0;

        for (ModelSource source : sortedSources) {
            try {
                List<ModelDescriptor> models = source.refresh();
                var                   delta  = registry.replaceSource(source.sourceId(), source.priority(), models);
                sourcesRefreshed++;
                added += delta.addedIds().size();
                removed += delta.removedIds().size();
                updated += delta.updatedIds().size();
                if (delta.hasChanges()) {
                    catalogChanged.fire(new ModelCatalogChangedEvent(
                            source.sourceId(), delta.addedIds(), delta.removedIds(), delta.updatedIds()));
                }
            } catch (Exception e) {
                LOG.warnf("Model source '%s' refresh failed: %s", source.sourceId(), e.getMessage());
            }
        }

        return new RefreshResult(sourcesRefreshed, registry.all().size(), added, removed, updated);
    }

}
