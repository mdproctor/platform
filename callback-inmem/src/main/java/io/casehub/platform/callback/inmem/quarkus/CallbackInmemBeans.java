package io.casehub.platform.callback.inmem.quarkus;

import io.casehub.platform.callback.inmem.InMemoryCallbackRegistry;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Alternative;
import jakarta.enterprise.inject.Produces;

@ApplicationScoped
public class CallbackInmemBeans {

    @Produces
    @Alternative
    @Priority(100)
    @ApplicationScoped
    public InMemoryCallbackRegistry inMemoryCallbackRegistry() {
        return new InMemoryCallbackRegistry();
    }
}
