package io.casehub.platform.datasource;

import io.casehub.platform.api.datasource.DataProcessor;
import io.casehub.platform.api.datasource.DataSource;
import io.casehub.platform.api.datasource.DataSourceDescriptor;
import io.casehub.platform.api.datasource.DataSourceQuery;
import io.casehub.platform.api.datasource.DataSourceRegistry;
import io.casehub.platform.api.datasource.ObjectType;
import io.casehub.platform.api.datasource.SubscriptionHandle;
import io.casehub.platform.api.path.Path;

import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;

public class NoOpDataSourceRegistry implements DataSourceRegistry {

    @Override public DataSource<?> register(final DataSourceDescriptor descriptor) { return NoOpDataSource.INSTANCE; }
    @Override public Optional<DataSourceDescriptor> resolve(final Path path, final String tenancyId) { return Optional.empty(); }
    @Override public Optional<DataSource<?>> resolveSource(final Path path, final String tenancyId) { return Optional.empty(); }
    @Override public List<DataSourceDescriptor> discover(final DataSourceQuery query) { return List.of(); }
    @Override public void deregister(final Path path, final String tenancyId) {}
    @Override public void update(final DataSourceDescriptor descriptor) {}

    private enum NoOpDataSource implements DataSource<Object> {
        INSTANCE;
        @Override public void add(Object value) {}
        @Override public SubscriptionHandle subscribe(DataProcessor<? super Object> p) { return NoOpHandle.INSTANCE; }
        @Override public <U> SubscriptionHandle subscribe(ObjectType<U> t, DataProcessor<? super U> p) { return NoOpHandle.INSTANCE; }
        @Override public <U> SubscriptionHandle subscribe(ObjectType<U> t, Predicate<U> f, DataProcessor<? super U> p) { return NoOpHandle.INSTANCE; }
        @Override public <U> SubscriptionHandle subscribe(Class<U> t, Predicate<U> f, DataProcessor<? super U> p) { return NoOpHandle.INSTANCE; }
    }

    private enum NoOpHandle implements SubscriptionHandle {
        INSTANCE;
        @Override public boolean isActive() { return false; }
        @Override public void unsubscribe() {}
    }
}
