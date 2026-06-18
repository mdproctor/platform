package io.casehub.platform.streams.kafka;

import io.casehub.platform.api.endpoints.EndpointCapability;
import io.casehub.platform.api.endpoints.EndpointDescriptor;
import io.casehub.platform.api.endpoints.EndpointPropertyKeys;
import io.casehub.platform.api.endpoints.EndpointProtocol;
import io.casehub.platform.api.endpoints.EndpointQuery;
import io.casehub.platform.api.endpoints.EndpointRegistry;
import io.casehub.platform.api.identity.TenancyConstants;
import io.cloudevents.CloudEvent;
import io.cloudevents.core.builder.CloudEventBuilder;
import io.quarkus.runtime.Startup;
import io.quarkus.runtime.StartupEvent;
import io.smallrye.reactive.messaging.kafka.api.IncomingKafkaRecordMetadata;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Event;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.apache.kafka.common.header.Header;
import org.eclipse.microprofile.config.ConfigProvider;
import org.eclipse.microprofile.reactive.messaging.Incoming;
import org.eclipse.microprofile.reactive.messaging.Message;
import org.jboss.logging.Logger;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletionStage;

/**
 * Kafka stream ingestion processor.
 *
 * <p>Receives messages on a single static {@code @Incoming("casehub-kafka-stream")} channel.
 * Always receives as {@code Message<byte[]>} (P0 — no native CloudEvents deserialization).
 * Builds a CloudEvent from scratch and fires {@code Event<CloudEvent>.fireAsync()}.
 *
 * <p>Does NOT observe {@link io.casehub.platform.api.endpoints.EndpointRegistered} —
 * KAFKA stream descriptors must be registered before application startup via
 * {@code endpoints-config} YAML. For runtime-dynamic topics, use {@code streams-camel}.
 *
 * <p>CAMEL and KAFKA are mutually exclusive for the same topic — running both from the
 * same consumer group causes silent message loss (Kafka partition-splits between groups).
 */
@Startup
@ApplicationScoped
public class KafkaStreamProcessor {

    private static final Logger LOG = Logger.getLogger(KafkaStreamProcessor.class);
    private static final String CHANNEL_NAME = "casehub-kafka-stream";
    private static final String UNREGISTERED_TYPE = "io.casehub.platform.streams.kafka.unregistered";

    @Inject
    EndpointRegistry endpointRegistry;

    @Inject
    Event<CloudEvent> cloudEventBus;

    /** topic → EndpointDescriptor, populated at startup. */
    private final Map<String, EndpointDescriptor> topicToDescriptor = new HashMap<>();

    void onStartup(@Observes StartupEvent ev) {
        String topicConfig = ConfigProvider.getConfig()
            .getOptionalValue("mp.messaging.incoming." + CHANNEL_NAME + ".topic", String.class)
            .or(() -> ConfigProvider.getConfig()
                .getOptionalValue("mp.messaging.incoming." + CHANNEL_NAME + ".topics", String.class))
            .orElse("");

        if (topicConfig.isBlank()) {
            LOG.warnf("No topic configured for channel '%s' — no KAFKA streams will be processed",
                CHANNEL_NAME);
            return;
        }

        var descriptors = endpointRegistry.discover(
            new EndpointQuery(TenancyConstants.DEFAULT_TENANT_ID, null,
                EndpointProtocol.KAFKA, Set.of(EndpointCapability.RECEIVE)));

        for (String raw : topicConfig.split(",")) {
            String topic = raw.strip();
            if (topic.isBlank()) continue;
            descriptors.stream()
                .filter(d -> topic.equals(d.properties().get(EndpointPropertyKeys.TOPIC)))
                .findFirst()
                .ifPresentOrElse(
                    d -> topicToDescriptor.put(topic, d),
                    () -> LOG.warnf("No EndpointDescriptor found for Kafka topic '%s'", topic));
        }
    }

    @SuppressWarnings("unchecked")
    @Incoming(CHANNEL_NAME)
    public CompletionStage<Void> process(Message<byte[]> message) {
        @SuppressWarnings("rawtypes")
        Optional<IncomingKafkaRecordMetadata> rawMeta =
            message.getMetadata(IncomingKafkaRecordMetadata.class);
        Optional<IncomingKafkaRecordMetadata<?, byte[]>> meta =
            rawMeta.map(m -> (IncomingKafkaRecordMetadata<?, byte[]>) m);

        String topic = meta.map(IncomingKafkaRecordMetadata::getTopic).orElse("unknown");

        String tenancyId = meta.map(m -> {
            Header header = m.getHeaders().lastHeader("X-Tenancy-ID");
            return header != null ? new String(header.value(), StandardCharsets.UTF_8) : null;
        }).orElse(null);

        EndpointDescriptor descriptor = topicToDescriptor.get(topic);
        CloudEvent ce = buildCloudEvent(message.getPayload(), topic, descriptor, tenancyId);

        return cloudEventBus.fireAsync(ce)
            .whenComplete((e, t) -> {
                if (t != null) LOG.warnf(t, "CloudEvent observer failed for Kafka topic %s", topic);
            })
            .thenCompose(ignored -> message.ack());
    }

    /**
     * Package-private: exposed for direct unit testing.
     *
     * @param body       raw message bytes
     * @param topic      Kafka topic name
     * @param descriptor matched EndpointDescriptor, or {@code null} if unregistered
     * @param tenancyId  from Kafka header X-Tenancy-ID, or {@code null} to fall back to descriptor
     */
    CloudEvent buildCloudEvent(byte[] body, String topic, EndpointDescriptor descriptor,
                               String tenancyId) {
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
            .withSource(URI.create("/platform/streams/kafka/" + topic))
            .withTime(OffsetDateTime.now())
            .withData(body)
            .withExtension("tenancyid", effectiveTenancyId)
            .build();
    }
}
