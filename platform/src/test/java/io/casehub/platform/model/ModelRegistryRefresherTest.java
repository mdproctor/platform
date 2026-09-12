package io.casehub.platform.model;

import io.casehub.platform.api.model.ModelCatalogChangedEvent;
import io.casehub.platform.api.model.ModelDescriptor;
import io.casehub.platform.api.model.ModelSource;
import jakarta.enterprise.event.Event;
import jakarta.enterprise.inject.Instance;
import java.lang.annotation.Annotation;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ModelRegistryRefresherTest {

    @Test
    void refreshAll_sortsSourcesByPriorityAscending() {
        var refreshOrder = new ArrayList<String>();
        var high = stubSource("high", 10, refreshOrder);
        var low = stubSource("low", 0, refreshOrder);
        var mid = stubSource("mid", 5, refreshOrder);

        var refresher = new ModelRegistryRefresher();
        refresher.sources = listInstance(List.of(high, low, mid));
        refresher.registry = new InMemoryModelRegistry();
        refresher.catalogChanged = noOpEvent();

        refresher.refreshAll();

        assertThat(refreshOrder).containsExactly("low", "mid", "high");
    }

    @Test
    void refreshAll_continuesAfterSourceFailure() {
        var refreshOrder = new ArrayList<String>();
        var first = stubSource("first", 0, refreshOrder);
        var failing = new ModelSource() {
            @Override public String sourceId() { return "failing"; }
            @Override public int priority() { return 5; }
            @Override public List<ModelDescriptor> refresh() {
                refreshOrder.add("failing");
                throw new RuntimeException("boom");
            }
        };
        var last = stubSource("last", 10, refreshOrder);

        var refresher = new ModelRegistryRefresher();
        refresher.sources = listInstance(List.of(first, failing, last));
        refresher.registry = new InMemoryModelRegistry();
        refresher.catalogChanged = noOpEvent();

        refresher.refreshAll();

        assertThat(refreshOrder).containsExactly("first", "failing", "last");
    }

    private ModelSource stubSource(String id, int priority, List<String> order) {
        return new ModelSource() {
            @Override public String sourceId() { return id; }
            @Override public int priority() { return priority; }
            @Override public List<ModelDescriptor> refresh() {
                order.add(id);
                return List.of();
            }
        };
    }

    @SuppressWarnings("unchecked")
    private static Instance<ModelSource> listInstance(List<ModelSource> sources) {
        return new Instance<>() {
            @Override public Iterator<ModelSource> iterator() { return sources.iterator(); }
            @Override public Instance<ModelSource> select(Annotation... qualifiers) { return this; }
            @Override public <U extends ModelSource> Instance<U> select(Class<U> subtype, Annotation... qualifiers) { throw new UnsupportedOperationException(); }
            @Override public <U extends ModelSource> Instance<U> select(jakarta.enterprise.util.TypeLiteral<U> subtype, Annotation... qualifiers) { throw new UnsupportedOperationException(); }
            @Override public boolean isUnsatisfied() { return sources.isEmpty(); }
            @Override public boolean isAmbiguous() { return false; }
            @Override public boolean isResolvable() { return !sources.isEmpty(); }
            @Override public ModelSource get() { return sources.get(0); }
            @Override public void destroy(ModelSource instance) {}
            @Override public Handle<ModelSource> getHandle() { throw new UnsupportedOperationException(); }
            @Override public Iterable<? extends Handle<ModelSource>> handles() { throw new UnsupportedOperationException(); }
        };
    }

    @SuppressWarnings("unchecked")
    private static Event<ModelCatalogChangedEvent> noOpEvent() {
        return new Event<>() {
            @Override public void fire(ModelCatalogChangedEvent event) {}
            @Override public <U extends ModelCatalogChangedEvent> jakarta.enterprise.event.Event<U> select(Class<U> subtype, Annotation... qualifiers) { throw new UnsupportedOperationException(); }
            @Override public <U extends ModelCatalogChangedEvent> jakarta.enterprise.event.Event<U> select(jakarta.enterprise.util.TypeLiteral<U> subtype, Annotation... qualifiers) { throw new UnsupportedOperationException(); }
            @Override public jakarta.enterprise.event.Event<ModelCatalogChangedEvent> select(Annotation... qualifiers) { throw new UnsupportedOperationException(); }
            @Override public java.util.concurrent.CompletionStage<ModelCatalogChangedEvent> fireAsync(ModelCatalogChangedEvent event) { throw new UnsupportedOperationException(); }
            @Override public java.util.concurrent.CompletionStage<ModelCatalogChangedEvent> fireAsync(ModelCatalogChangedEvent event, jakarta.enterprise.event.NotificationOptions options) { throw new UnsupportedOperationException(); }
        };
    }
}
