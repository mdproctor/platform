package io.casehub.platform.streams.webhook;

import io.casehub.platform.api.credentials.CredentialResolver;
import io.casehub.platform.api.endpoints.EndpointRegistry;
import io.cloudevents.CloudEvent;
import io.quarkus.runtime.Startup;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Event;
import jakarta.enterprise.inject.Produces;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

@Startup
@ApplicationScoped
public class WebhookBeans {

    private static final Logger LOG = Logger.getLogger(WebhookBeans.class);

    @Produces
    @ApplicationScoped
    public WebhookReceiver webhookReceiver(EndpointRegistry endpointRegistry,
                                            CredentialResolver credentialResolver,
                                            Event<CloudEvent> cloudEventBus,
                                            @ConfigProperty(name = "casehub.streams.webhook.public-url") String publicUrl,
                                            @ConfigProperty(name = "casehub.streams.webhook.require-auth", defaultValue = "true") boolean requireAuth) {
        var receiver = new WebhookReceiver(endpointRegistry, credentialResolver,
                event -> cloudEventBus.fireAsync(event)
                        .whenComplete((e, t) -> {
                            if (t != null) { LOG.warnf(t, "CloudEvent observer failed"); }
                        }),
                publicUrl, requireAuth);
        receiver.init();
        return receiver;
    }
}
