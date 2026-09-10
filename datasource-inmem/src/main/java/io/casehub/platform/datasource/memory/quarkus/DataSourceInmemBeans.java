package io.casehub.platform.datasource.memory.quarkus;

import io.casehub.platform.api.datasource.DataSourceDeregistered;
import io.casehub.platform.api.datasource.DataSourceRegistered;
import io.casehub.platform.api.datasource.DataSourceUpdated;
import io.casehub.platform.datasource.memory.InMemoryDataSourceRegistry;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Event;
import jakarta.enterprise.inject.Alternative;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Inject;

@ApplicationScoped
public class DataSourceInmemBeans {

    @Inject Event<DataSourceRegistered> registeredEvent;
    @Inject Event<DataSourceDeregistered> deregisteredEvent;
    @Inject Event<DataSourceUpdated> updatedEvent;

    @Produces
    @Alternative
    @Priority(100)
    @ApplicationScoped
    public InMemoryDataSourceRegistry inMemoryDataSourceRegistry() {
        return new InMemoryDataSourceRegistry(
                e -> registeredEvent.fireAsync(e),
                e -> deregisteredEvent.fireAsync(e),
                e -> updatedEvent.fireAsync(e));
    }
}
