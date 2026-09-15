package io.casehub.platform.callback.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;

@ApplicationScoped
public class CallbackClientBeans {

    @Produces
    @ApplicationScoped
    public CallbackDispatcher callbackDispatcher(ObjectMapper objectMapper) {
        return new CallbackDispatcher(objectMapper);
    }
}
