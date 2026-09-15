package io.casehub.platform.callback.client;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.casehub.platform.api.callback.CallbackRegistration;
import io.casehub.platform.api.callback.CallbackRegistrationRequest;
import io.casehub.platform.api.mcp.CallbackEligible;
import io.quarkus.runtime.Startup;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Any;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.health.HealthCheck;
import org.eclipse.microprofile.health.HealthCheckResponse;
import org.eclipse.microprofile.health.Readiness;
import org.jboss.logging.Logger;

/**
 * Discovers local {@link CallbackEligible @CallbackEligible} SPI implementations at startup
 * and registers them with a CaseHub server. Maintains lease renewal via heartbeat and
 * reports readiness status.
 */
@Startup
@Readiness
@ApplicationScoped
public class CallbackAutoRegistrar implements HealthCheck {

    private static final Logger LOG = Logger.getLogger(CallbackAutoRegistrar.class);

    private final Map<String, CallbackRegistration> activeRegistrations = new ConcurrentHashMap<>();
    private final Set<String> pendingSpiNames = ConcurrentHashMap.newKeySet();
    private final ObjectMapper mapper;
    private final HttpClient httpClient;
    private final ExecutorService registrationExecutor = Executors.newSingleThreadExecutor();
    private volatile boolean registrationComplete = false;

    @Inject
    @ConfigProperty(name = "casehub.callback.server-url")
    Optional<String> serverUrl;

    @Inject
    @ConfigProperty(name = "casehub.callback.public-url")
    Optional<String> publicUrl;

    @Inject
    @ConfigProperty(name = "casehub.callback.tenancy-id")
    Optional<String> tenancyId;

    @Inject
    @ConfigProperty(name = "casehub.callback.ttl-seconds", defaultValue = "300")
    int ttlSeconds;

    @Inject
    @ConfigProperty(name = "casehub.callback.timeout-ms", defaultValue = "30000")
    int timeoutMs;

    @Inject
    CallbackDispatcher dispatcher;

    @Inject
    @Any
    Instance<Object> allBeans;

    public CallbackAutoRegistrar() {
        this.mapper = new ObjectMapper();
        this.mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        this.mapper.registerModule(new JavaTimeModule());
        this.httpClient = HttpClient.newHttpClient();
    }

    @jakarta.annotation.PostConstruct
    void init() {
        if (serverUrl.isEmpty() || publicUrl.isEmpty()) {
            LOG.info("Callback auto-registration disabled — casehub.callback.server-url or public-url not configured");
            registrationComplete = true;
            return;
        }
        if (tenancyId.isEmpty()) {
            LOG.warn("Callback auto-registration disabled — casehub.callback.tenancy-id not configured");
            registrationComplete = true;
            return;
        }

        discoverSpis();
        registrationExecutor.submit(this::registerAllWithRetry);
    }

    void discoverSpis() {
        for (final var handle : allBeans.handles()) {
            final Class<?> beanClass = handle.getBean().getBeanClass();
            if (beanClass.isAnnotationPresent(io.quarkus.arc.DefaultBean.class)) {
                continue;
            }
            if (beanClass.isAnnotationPresent(jakarta.decorator.Decorator.class)) {
                continue;
            }
            for (final Class<?> iface : beanClass.getInterfaces()) {
                final CallbackEligible annotation = iface.getAnnotation(CallbackEligible.class);
                if (annotation != null) {
                    final String spiName = annotation.name().isEmpty()
                            ? toKebabCase(iface.getSimpleName())
                            : annotation.name();
                    final Object bean = handle.get();
                    dispatcher.registerSpi(spiName, bean);
                    pendingSpiNames.add(spiName);
                }
            }
        }
    }

    private void registerAllWithRetry() {
        int attempt = 0;
        while (!pendingSpiNames.isEmpty() && attempt < 5) {
            if (attempt > 0) {
                try {
                    long delay = Math.min(1000L * (1L << attempt), 30000L);
                    Thread.sleep(delay);
                } catch (final InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
            attempt++;
            for (final String spiName : Set.copyOf(pendingSpiNames)) {
                if (registerWithServer(spiName)) {
                    pendingSpiNames.remove(spiName);
                }
            }
        }
        if (!pendingSpiNames.isEmpty()) {
            LOG.warnf("Failed to register %d SPI(s) after %d attempts: %s",
                    pendingSpiNames.size(), attempt, pendingSpiNames);
        }
        registrationComplete = true;
    }

    private boolean registerWithServer(final String spiName) {
        final String callbackUrl = publicUrl.orElseThrow() + "/casehub/callbacks/" + spiName;
        final var request = new CallbackRegistrationRequest(
                spiName, callbackUrl, null, tenancyId.orElseThrow(),
                timeoutMs, ttlSeconds, Map.of());

        try {
            final String body = mapper.writeValueAsString(request);
            final HttpRequest httpRequest = HttpRequest.newBuilder()
                    .uri(URI.create(serverUrl.orElseThrow() + "/api/callbacks/register"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();

            final HttpResponse<String> response = httpClient.send(
                    httpRequest, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 200) {
                final CallbackRegistration reg = mapper.readValue(
                        response.body(), CallbackRegistration.class);
                activeRegistrations.put(spiName, reg);
                LOG.infof("Registered callback for SPI '%s' — id=%s", spiName, reg.id());
                return true;
            } else {
                LOG.warnf("Failed to register callback for SPI '%s' — HTTP %d: %s",
                        spiName, response.statusCode(), response.body());
                return false;
            }
        } catch (final Exception e) {
            LOG.warnf(e, "Failed to register callback for SPI '%s' — will retry", spiName);
            return false;
        }
    }

    @Scheduled(every = "${casehub.callback.heartbeat-interval:100s}")
    void heartbeat() {
        for (final var entry : activeRegistrations.entrySet()) {
            final CallbackRegistration reg = entry.getValue();
            try {
                final HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(serverUrl.orElseThrow() + "/api/callbacks/heartbeat/" + reg.id()))
                        .PUT(HttpRequest.BodyPublishers.noBody())
                        .build();

                final HttpResponse<String> response = httpClient.send(
                        request, HttpResponse.BodyHandlers.ofString());

                if (response.statusCode() == 404) {
                    LOG.infof("Registration expired for SPI '%s' — re-registering", entry.getKey());
                    registerWithServer(entry.getKey());
                }
            } catch (final Exception e) {
                LOG.warnf(e, "Heartbeat failed for SPI '%s' — will retry next tick", entry.getKey());
            }
        }
    }

    @jakarta.annotation.PreDestroy
    void shutdown() {
        registrationExecutor.shutdownNow();
        for (final var entry : activeRegistrations.entrySet()) {
            try {
                final HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(serverUrl.orElseThrow() + "/api/callbacks/deregister/" + entry.getValue().id()))
                        .DELETE()
                        .build();
                httpClient.send(request, HttpResponse.BodyHandlers.ofString());
                LOG.infof("Deregistered callback for SPI '%s'", entry.getKey());
            } catch (final Exception e) {
                LOG.warnf(e, "Failed to deregister callback for SPI '%s'", entry.getKey());
            }
        }
    }

    @Override
    public HealthCheckResponse call() {
        return HealthCheckResponse.named("callback-registrar")
                .status(registrationComplete)
                .withData("registrations", activeRegistrations.size())
                .build();
    }

    Map<String, CallbackRegistration> getActiveRegistrations() {
        return Map.copyOf(activeRegistrations);
    }

    static String toKebabCase(final String camelCase) {
        if (camelCase == null || camelCase.isEmpty()) return camelCase;
        final StringBuilder result = new StringBuilder();
        for (int i = 0; i < camelCase.length(); i++) {
            final char c = camelCase.charAt(i);
            if (Character.isUpperCase(c)) {
                if (i > 0) {
                    final boolean prevUpper = Character.isUpperCase(camelCase.charAt(i - 1));
                    final boolean nextLower = (i + 1 < camelCase.length())
                            && Character.isLowerCase(camelCase.charAt(i + 1));
                    if (!prevUpper || nextLower) {
                        result.append('-');
                    }
                }
                result.append(Character.toLowerCase(c));
            } else {
                result.append(c);
            }
        }
        return result.toString();
    }
}
