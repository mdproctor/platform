package io.casehub.platform.model;

import io.casehub.platform.api.model.CostTier;
import io.casehub.platform.api.model.ModelCatalogChangedEvent;
import io.casehub.platform.api.model.ModelDescriptor;
import io.casehub.platform.api.model.ModelLocality;
import io.casehub.platform.api.model.ModelSource;
import io.casehub.platform.api.model.ModelTier;
import io.casehub.platform.api.model.RefreshResult;
import jakarta.enterprise.event.Event;
import jakarta.enterprise.inject.Instance;
import org.junit.jupiter.api.Test;

import java.lang.annotation.Annotation;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

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

    @Test
    void refreshAllWithResult_aggregatesDeltas() {
        var sourceA = new ModelSource() {
            @Override
            public String sourceId() {return "a";}

            @Override
            public int priority()    {return 0;}

            @Override
            public List<ModelDescriptor> refresh() {
                return List.of(
                        new ModelDescriptor("a:m1", "m1", "openai", null, "openai", "gpt-4", "GPT-4",
                                            ModelTier.FLAGSHIP, Set.of(), 128000, 4096, ModelLocality.CLOUD, CostTier.HIGH, null, Map.of()),
                        new ModelDescriptor("a:m2", "m2", "openai", null, "openai", "gpt-4", "GPT-4 Mini",
                                            ModelTier.FAST, Set.of(), 128000, 4096, ModelLocality.CLOUD, CostTier.LOW, null, Map.of())
                              );
            }
        };
        var sourceB = new ModelSource() {
            @Override
            public String sourceId() {return "b";}

            @Override
            public int priority()    {return 10;}

            @Override
            public List<ModelDescriptor> refresh() {
                return List.of(
                        new ModelDescriptor("b:m1", "m1", "anthropic", null, "anthropic", "claude", "Claude",
                                            ModelTier.FLAGSHIP, Set.of(), 200000, 8192, ModelLocality.CLOUD, CostTier.HIGH, null, Map.of())
                              );
            }
        };

        var refresher = new ModelRegistryRefresher();
        refresher.sources        = listInstance(List.of(sourceA, sourceB));
        refresher.registry       = new InMemoryModelRegistry();
        refresher.catalogChanged = noOpEvent();

        RefreshResult result = refresher.refreshAllWithResult();

        assertThat(result.sourcesRefreshed()).isEqualTo(2);
        assertThat(result.totalModels()).isEqualTo(3);
        assertThat(result.added()).isEqualTo(3);
        assertThat(result.removed()).isEqualTo(0);
        assertThat(result.updated()).isEqualTo(0);
    }

    @Test
    void refreshAllWithResult_countsFailedSourcesAsZero() {
        var good = new ModelSource() {
            @Override
            public String sourceId() {return "good";}

            @Override
            public int priority()    {return 0;}

            @Override
            public List<ModelDescriptor> refresh() {
                return List.of(
                        new ModelDescriptor("g:m1", "m1", "openai", null, "openai", "gpt-4", "GPT-4",
                                            ModelTier.FLAGSHIP, Set.of(), 128000, 4096, ModelLocality.CLOUD, CostTier.HIGH, null, Map.of())
                              );
            }
        };
        var bad = new ModelSource() {
            @Override
            public String sourceId()               {return "bad";}

            @Override
            public int priority()                  {return 10;}

            @Override
            public List<ModelDescriptor> refresh() {throw new RuntimeException("boom");}
        };

        var refresher = new ModelRegistryRefresher();
        refresher.sources        = listInstance(List.of(good, bad));
        refresher.registry       = new InMemoryModelRegistry();
        refresher.catalogChanged = noOpEvent();

        RefreshResult result = refresher.refreshAllWithResult();

        assertThat(result.sourcesRefreshed()).isEqualTo(1);
        assertThat(result.totalModels()).isEqualTo(1);
        assertThat(result.added()).isEqualTo(1);
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
