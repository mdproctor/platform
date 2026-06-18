package io.casehub.platform.streams.camel;

import io.casehub.platform.api.endpoints.EndpointCapability;
import io.casehub.platform.api.endpoints.EndpointDescriptor;
import io.casehub.platform.api.endpoints.EndpointPropertyKeys;
import io.casehub.platform.api.endpoints.EndpointProtocol;
import io.casehub.platform.api.endpoints.EndpointQuery;
import io.casehub.platform.api.endpoints.EndpointRegistered;
import io.casehub.platform.api.endpoints.EndpointRegistry;
import io.casehub.platform.api.identity.TenancyConstants;
import io.cloudevents.CloudEvent;
import io.cloudevents.core.builder.CloudEventBuilder;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Event;
import jakarta.enterprise.event.ObservesAsync;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.apache.camel.CamelContext;
import org.apache.camel.builder.RouteBuilder;
import org.jboss.logging.Logger;

import java.net.URI;
import java.time.OffsetDateTime;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Dynamic Camel route builder for runtime-registered CAMEL endpoints.
 *
 * <p>Startup: {@code @Observes StartupEvent} discovers all pre-startup CAMEL endpoints
 * from the registry and adds routes. Sets {@code camelStarted = true} after processing.
 *
 * <p>Runtime: {@code @ObservesAsync EndpointRegistered} for CAMEL protocol — if
 * {@code camelStarted} is false, pre-startup events delivered late by the CDI async executor
 * are discarded (the startup handler already covered them via discover). If
 * {@code camelStarted} is true, adds a route idempotently via the {@code routedUris} set.
 *
 * <p><b>P0 constraint:</b> Changing a Camel endpoint URI requires restart — the old route
 * is not stopped; a second route is added for the new URI.
 *
 * <p><b>Known startup-window gap:</b> An endpoint registered after {@code onStartup}'s
 * {@code discover()} but before {@code camelStarted.set(true)} would be discarded.
 * In practice this window is zero (desiredstate reconciliation does not start before the
 * app is ready).
 *
 * <p>CAMEL and KAFKA are mutually exclusive for the same Kafka topic — running both
 * from the same consumer group causes silent message loss.
 */
@ApplicationScoped
public class CamelStreamProcessor {

    private static final Logger LOG = Logger.getLogger(CamelStreamProcessor.class);

    @Inject
    EndpointRegistry endpointRegistry;

    @Inject
    Event<CloudEvent> cloudEventBus;

    @Inject
    CamelContext camelContext;

    private final AtomicBoolean camelStarted = new AtomicBoolean(false);
    private final Set<String> routedUris = ConcurrentHashMap.newKeySet();

    void onStartup(@Observes StartupEvent ev) {
        // @Startup @ApplicationScoped beans complete @PostConstruct before StartupEvent fires,
        // so discover() sees the complete pre-startup registry state.
        endpointRegistry.discover(
            new EndpointQuery(TenancyConstants.DEFAULT_TENANT_ID, null,
                EndpointProtocol.CAMEL, Set.of(EndpointCapability.RECEIVE))
        ).forEach(d -> {
            String uri = d.properties().get(EndpointPropertyKeys.URL);
            if (routedUris.add(uri)) {
                addRoute(d);  // RuntimeException propagates out of forEach, aborts startup.
                              // Remaining descriptors not processed — fail-fast is correct.
            }
        });
        camelStarted.set(true);
    }

    void onEndpointRegistered(@ObservesAsync EndpointRegistered event) {
        EndpointDescriptor d = event.descriptor();
        if (d.protocol() != EndpointProtocol.CAMEL) return;
        if (!camelStarted.get()) return;  // Pre-startup events delivered late: covered by onStartup
        String uri = d.properties().get(EndpointPropertyKeys.URL);
        if (routedUris.add(uri)) {
            addRoute(d);  // idempotent: skip if URI already routed
        }
    }

    /**
     * Package-private for direct unit testing.
     */
    CloudEvent buildCloudEvent(byte[] body, EndpointDescriptor descriptor) {
        String type = descriptor.properties().getOrDefault(
            EndpointPropertyKeys.STREAM_EVENT_TYPE,
            "io.casehub.platform.streams.camel.unregistered");

        return CloudEventBuilder.v1()
            .withId(UUID.randomUUID().toString())
            .withType(type)
            .withSource(URI.create("/platform/streams/camel"))
            .withTime(OffsetDateTime.now())
            .withData(body)
            .withExtension("tenancyid", descriptor.tenancyId())
            .build();
    }

    private void addRoute(EndpointDescriptor d) {
        String uri = d.properties().get(EndpointPropertyKeys.URL);
        try {
            camelContext.addRoutes(new RouteBuilder() {
                @Override
                public void configure() {
                    from(uri).process(exchange -> {
                        byte[] body = exchange.getIn().getBody(byte[].class);
                        CloudEvent ce = buildCloudEvent(body, d);
                        cloudEventBus.fireAsync(ce)
                            .whenComplete((e, t) -> {
                                if (t != null) {
                                    LOG.warnf(t, "CloudEvent observer failed for Camel route %s", uri);
                                }
                            });
                    });
                }
            });
        } catch (Exception e) {
            // In onStartup: propagates out, aborts startup (remaining descriptors not processed).
            // In onEndpointRegistered: CDI async executor catches RuntimeException, wraps in
            // CompletionException, which fireAsync().whenComplete in register() WARN-logs.
            throw new RuntimeException("Failed to add Camel route for URI: " + uri, e);
        }
    }
}
