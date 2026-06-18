package io.casehub.platform.streams.poll;

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
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Event;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.Set;
import java.util.UUID;

/**
 * Scheduled HTTP GET poller.
 *
 * <p>Discovers {@link EndpointProtocol#HTTP} + {@link EndpointCapability#QUERY} endpoints
 * registered under {@link TenancyConstants#DEFAULT_TENANT_ID} (P0 — single-tenant).
 *
 * <p>{@code java.net.http.HttpClient.send()} is declared {@code throws IOException, InterruptedException}.
 * HTTP 4xx/5xx responses are NOT exceptions — they return {@code HttpResponse<byte[]>} with
 * {@code statusCode()} set. The status code must be checked explicitly.
 * {@code InterruptedException} must be caught and re-thrown as {@code IOException} after calling
 * {@code Thread.currentThread().interrupt()} to preserve the Quarkus scheduler shutdown signal.
 */
@Startup
@ApplicationScoped
public class PollStreamProcessor {

    private static final Logger LOG = Logger.getLogger(PollStreamProcessor.class);

    @Inject
    EndpointRegistry endpointRegistry;

    @Inject
    Event<CloudEvent> cloudEventBus;

    // Class field — connection pool reused across poll cycles; per-call creation discards pool
    private final HttpClient httpClient = HttpClient.newHttpClient();

    @Scheduled(every = "${casehub.streams.poll.interval:60s}")
    void poll() {
        endpointRegistry.discover(
            new EndpointQuery(TenancyConstants.DEFAULT_TENANT_ID, null,
                EndpointProtocol.HTTP, Set.of(EndpointCapability.QUERY))
        ).forEach(descriptor -> {
            try {
                pollAndFire(descriptor);
            } catch (Exception e) {
                LOG.warnf(e, "Poll failed for endpoint %s — continuing to next endpoint",
                    descriptor.properties().get(EndpointPropertyKeys.URL));
            }
        });
    }

    /**
     * Package-private for direct unit testing of the HTTP fetch + status check logic.
     *
     * @throws IOException on connection failure, non-2xx status, or thread interruption
     */
    byte[] fetchBytes(String url) throws IOException {
        HttpRequest request = HttpRequest.newBuilder().GET().uri(URI.create(url)).build();
        HttpResponse<byte[]> response;
        try {
            response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Poll interrupted for " + url, e);
        }
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IOException("Poll returned HTTP " + response.statusCode() + " for " + url);
        }
        return response.body();
    }

    /**
     * Package-private for direct unit testing.
     *
     * <p>HttpClient.send() throws IOException for connection errors and
     * InterruptedException if the thread is interrupted. Neither is thrown for
     * HTTP 4xx/5xx — those must be detected via explicit status code check.
     */
    void pollAndFire(EndpointDescriptor descriptor) throws IOException {
        String url = descriptor.properties().get(EndpointPropertyKeys.URL);
        byte[] body = fetchBytes(url);
        CloudEvent ce = buildCloudEvent(body, descriptor);
        cloudEventBus.fireAsync(ce)
            .whenComplete((e, t) -> {
                if (t != null) LOG.warnf(t, "CloudEvent observer failed for poll endpoint %s", url);
            });
    }

    /**
     * Package-private for direct unit testing.
     */
    CloudEvent buildCloudEvent(byte[] body, EndpointDescriptor descriptor) {
        String type = descriptor.properties().getOrDefault(
            EndpointPropertyKeys.STREAM_EVENT_TYPE,
            "io.casehub.platform.streams.poll.unregistered");

        String urlEncoded = URLEncoder.encode(
            descriptor.properties().getOrDefault(EndpointPropertyKeys.URL, "unknown"),
            StandardCharsets.UTF_8);

        return CloudEventBuilder.v1()
            .withId(UUID.randomUUID().toString())
            .withType(type)
            .withSource(URI.create("/platform/streams/poll/" + urlEncoded))
            .withTime(OffsetDateTime.now())
            .withData(body)
            .withExtension("tenancyid", descriptor.tenancyId())
            .build();
    }
}
