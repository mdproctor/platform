package io.casehub.platform.streams.amqp;

import io.casehub.platform.api.endpoints.EndpointCapability;
import io.casehub.platform.api.endpoints.EndpointDescriptor;
import io.casehub.platform.api.endpoints.EndpointPropertyKeys;
import io.casehub.platform.api.endpoints.EndpointProtocol;
import io.casehub.platform.api.endpoints.EndpointQuery;
import io.casehub.platform.api.endpoints.EndpointRegistry;
import io.casehub.platform.api.identity.TenancyConstants;
import io.cloudevents.CloudEvent;
import io.cloudevents.core.builder.CloudEventBuilder;
import io.quarkus.runtime.StartupEvent;
import io.smallrye.reactive.messaging.amqp.IncomingAmqpMetadata;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Event;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.ConfigProvider;
import org.eclipse.microprofile.reactive.messaging.Incoming;
import org.eclipse.microprofile.reactive.messaging.Message;
import org.jboss.logging.Logger;

import java.net.URI;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletionStage;

/**
 * AMQP stream ingestion processor.
 *
 * <p>Receives messages on a single static {@code @Incoming("casehub-amqp-stream")} channel.
 * Always receives as {@code Message<byte[]>} (P0 — no native CloudEvents deserialization).
 * Builds a CloudEvent from scratch and fires {@code Event<CloudEvent>.fireAsync()}.
 *
 * <p>Does NOT observe {@link io.casehub.platform.api.endpoints.EndpointRegistered} —
 * AMQP descriptors must be registered before application startup via
 * {@code endpoints-config} YAML. For runtime-dynamic queues or multi-queue fan-in,
 * use {@code streams-camel}.
 *
 * <p>SmallRye AMQP does NOT support plural addresses per channel (unlike Kafka's
 * {@code topics=a,b}). Each channel has exactly one address. For multi-queue fan-in
 * use {@code streams-camel}.
 */
@ApplicationScoped
public class AmqpStreamProcessor {

    private static final Logger LOG = Logger.getLogger(AmqpStreamProcessor.class);
    private static final String CHANNEL_NAME = "casehub-amqp-stream";
    private static final String UNREGISTERED_TYPE = "io.casehub.platform.streams.amqp.unregistered";

    @Inject
    EndpointRegistry endpointRegistry;

    @Inject
    Event<CloudEvent> cloudEventBus;

    /** address → EndpointDescriptor, populated at startup. */
    private final Map<String, EndpointDescriptor> addressToDescriptor = new HashMap<>();

    void onStartup(@Observes StartupEvent ev) {
        String address = ConfigProvider.getConfig()
            .getOptionalValue("mp.messaging.incoming." + CHANNEL_NAME + ".address", String.class)
            .orElse("");

        if (address.isBlank()) {
            LOG.warnf("No address configured for channel '%s' — no AMQP streams will be processed",
                CHANNEL_NAME);
            return;
        }

        var descriptors = endpointRegistry.discover(
            new EndpointQuery(TenancyConstants.DEFAULT_TENANT_ID, null,
                EndpointProtocol.AMQP, Set.of(EndpointCapability.RECEIVE)));

        descriptors.stream()
            .filter(d -> address.equals(d.properties().get(EndpointPropertyKeys.TOPIC)))
            .findFirst()
            .ifPresentOrElse(
                d -> addressToDescriptor.put(address, d),
                () -> LOG.warnf("No EndpointDescriptor found for AMQP address '%s'", address));
    }

    @Incoming(CHANNEL_NAME)
    public CompletionStage<Void> process(Message<byte[]> message) {
        Optional<IncomingAmqpMetadata> meta = message.getMetadata(IncomingAmqpMetadata.class);

        String address = meta.map(IncomingAmqpMetadata::getAddress).orElse("unknown");

        String tenancyId = meta.map(m -> {
            var props = m.getProperties();
            return props != null ? props.getString("X-Tenancy-ID") : null;
        }).orElse(null);

        EndpointDescriptor descriptor = addressToDescriptor.get(address);
        CloudEvent ce = buildCloudEvent(message.getPayload(), descriptor, tenancyId);

        return cloudEventBus.fireAsync(ce)
            .whenComplete((e, t) -> {
                if (t != null) LOG.warnf(t, "CloudEvent observer failed for AMQP address %s", address);
            })
            .thenCompose(ignored -> message.ack());
    }

    /**
     * Package-private: exposed for direct unit testing.
     *
     * @param body       raw message bytes
     * @param descriptor matched EndpointDescriptor, or {@code null} if unregistered
     * @param tenancyId  from AMQP application property X-Tenancy-ID, or {@code null}
     *                   to fall back to descriptor tenancyId
     */
    CloudEvent buildCloudEvent(byte[] body, EndpointDescriptor descriptor, String tenancyId) {
        String address = descriptor != null
            ? descriptor.properties().getOrDefault(EndpointPropertyKeys.TOPIC, "unknown")
            : "unknown";

        String type = descriptor != null
            ? descriptor.properties().getOrDefault(EndpointPropertyKeys.STREAM_EVENT_TYPE,
                UNREGISTERED_TYPE)
            : UNREGISTERED_TYPE;

        String effectiveTenancyId = tenancyId != null
            ? tenancyId
            : (descriptor != null ? descriptor.tenancyId() : TenancyConstants.DEFAULT_TENANT_ID);

        return CloudEventBuilder.v1()
            .withId(UUID.randomUUID().toString())
            .withType(type)
            .withSource(URI.create("/platform/streams/amqp/" + address))
            .withTime(OffsetDateTime.now())
            .withData(body)
            .withExtension("tenancyid", effectiveTenancyId)
            .build();
    }
}
