package io.casehub.platform.notification.rest;

import io.casehub.pages.push.EventBroadcaster;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Singleton;

import static org.mockito.Mockito.mock;

@ApplicationScoped
public class MockEventBroadcasterProducer {

    @Produces
    @Singleton
    EventBroadcaster eventBroadcaster() {
        return mock(EventBroadcaster.class);
    }
}
