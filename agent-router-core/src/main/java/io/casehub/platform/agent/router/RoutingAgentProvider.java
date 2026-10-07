package io.casehub.platform.agent.router;

import io.casehub.platform.agent.AgentBackend;
import io.casehub.platform.agent.AgentEvent;
import io.casehub.platform.agent.AgentProvider;
import io.casehub.platform.agent.AgentRateLimitException;
import io.casehub.platform.agent.AgentSession;
import io.casehub.platform.agent.AgentSessionConfig;
import io.casehub.platform.agent.AgentSessionInit;
import io.casehub.platform.agent.BackendInstanceRegistry;
import io.casehub.platform.agent.config.ManifestResult;
import io.casehub.platform.api.FactoryMethod;
import io.casehub.platform.api.model.ModelAvailabilityFilter;
import io.casehub.platform.api.model.ModelChain;
import io.casehub.platform.api.model.ModelDescriptor;
import io.casehub.platform.api.model.ModelQuery;
import io.casehub.platform.api.model.ModelRef;
import io.casehub.platform.api.model.ModelRegistry;
import io.casehub.platform.api.model.ModelTier;
import io.smallrye.mutiny.Multi;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public class RoutingAgentProvider implements AgentProvider {

    private static final Logger LOG = Logger.getLogger(RoutingAgentProvider.class);

    private final BackendInstanceRegistry registry;
    private final String                  defaultBackendKey;
    private final ModelRegistry           modelRegistry;
    private final Map<String, ModelQuery> aliases;
    private final io.casehub.platform.agent.session.SessionPoolRegistry poolRegistry;

    private final ModelAvailabilityFilter availabilityFilter;

    private record ResolvedRoute(AgentBackend backend, String apiModelId) {}

    public RoutingAgentProvider(BackendInstanceRegistry registry,
                                String defaultBackendKey,
                                ModelRegistry modelRegistry) {
        this(registry, defaultBackendKey, modelRegistry, Map.of(), (ModelAvailabilityFilter) null, null);
    }

    public RoutingAgentProvider(BackendInstanceRegistry registry,
                                String defaultBackendKey,
                                ModelRegistry modelRegistry,
                                Map<String, ModelQuery> aliases) {
        this(registry, defaultBackendKey, modelRegistry, aliases, (ModelAvailabilityFilter) null, null);
    }

    public RoutingAgentProvider(BackendInstanceRegistry registry,
                                String defaultBackendKey,
                                ModelRegistry modelRegistry,
                                Map<String, ModelQuery> aliases,
                                ModelAvailabilityFilter availabilityFilter,
                                io.casehub.platform.agent.session.SessionPoolRegistry poolRegistry) {
        this.registry           = registry;
        this.defaultBackendKey  = defaultBackendKey;
        this.modelRegistry      = modelRegistry;
        this.aliases            = aliases != null ? Map.copyOf(aliases) : Map.of();
        this.availabilityFilter = availabilityFilter != null ? availabilityFilter : ModelAvailabilityFilter.ALWAYS_AVAILABLE;
        this.poolRegistry       = poolRegistry;
        LOG.infof("Agent router initialized with registry, default=%s, aliases=%d, pool=%s",
                  defaultBackendKey, this.aliases.size(), poolRegistry != null ? "enabled" : "disabled");
    }


    public RoutingAgentProvider(BackendInstanceRegistry registry,
                                String defaultBackendKey,
                                ModelRegistry modelRegistry,
                                Map<String, ModelQuery> aliases,
                                ModelAvailabilityFilter availabilityFilter) {
        this(registry, defaultBackendKey, modelRegistry, aliases, availabilityFilter, null);
    }


    public static RoutingAgentProvider create(
            BackendInstanceRegistry registry,
            String configDefaultBackend,
            ModelRegistry modelRegistry,
            Optional<ManifestResult> manifestResult) {
        return create(registry, configDefaultBackend, modelRegistry, manifestResult, null, null);
    }

    @FactoryMethod
    public static RoutingAgentProvider create(
            BackendInstanceRegistry registry,
            String configDefaultBackend,
            ModelRegistry modelRegistry,
            Optional<ManifestResult> manifestResult,
            ModelAvailabilityFilter availabilityFilter,
            io.casehub.platform.agent.session.SessionPoolRegistry poolRegistry) {
        if (manifestResult.isPresent()) {
            var result = manifestResult.get();
            var defaultBackend = result.defaultBackendKey() != null
                                 ? result.defaultBackendKey() : configDefaultBackend;
            return new RoutingAgentProvider(registry, defaultBackend, modelRegistry,
                                            result.aliases(), availabilityFilter, poolRegistry);
        }
        return new RoutingAgentProvider(registry, configDefaultBackend, modelRegistry,
                                        Map.of(), availabilityFilter, poolRegistry);
    }


    @Override
    public Multi<AgentEvent> invoke(AgentSessionConfig config) {
        var route = config.modelQuery() != null ? resolveQuery(config.modelQuery()) : resolve(config.model());
        var rewritten = new AgentSessionConfig(
                config.systemPrompt(), config.userPrompt(), config.mcpServers(),
                config.timeout(), config.correlationId(), route.apiModelId());
        if (poolRegistry != null) {
            var pool = poolRegistry.findPool(route.backend().key(), rewritten.model());
            if (pool.isPresent()) {
                return pool.get().invokeViaSession(rewritten);
            }
        }
        return route.backend().invoke(rewritten);
    }

    private Multi<AgentEvent> resolveAndInvokeWithRetry(AgentSessionConfig config) {
        var entries = config.modelChain().entries();

        Multi<AgentEvent> chain = Multi.createFrom().failure(
            new ModelChainExhaustedException(config.modelChain(), entries));

        for (int i = entries.size() - 1; i >= 0; i--) {
            var entry = entries.get(i);
            final Multi<AgentEvent> fallback = chain;
            chain = Multi.createFrom().deferred(() -> attemptInvoke(entry, config))
                .onFailure(ModelChainRetryableException.class)
                .recoverWithMulti(fallback);
        }
        return chain;
    }

    private Multi<AgentEvent> attemptInvoke(ModelChain.ModelChainEntry entry, AgentSessionConfig config) {
        ResolvedRoute route;
        try {
            route = switch (entry) {
                case ModelChain.ModelChainEntry.Named n -> resolve(n.modelRef());
                case ModelChain.ModelChainEntry.Queried q -> resolveQuery(q.query());
            };
        } catch (IllegalArgumentException e) {
            return Multi.createFrom().failure(new ModelChainRetryableException(entry, e));
        }

        var rewritten = new AgentSessionConfig(
                config.systemPrompt(), config.userPrompt(), config.mcpServers(),
                config.timeout(), config.correlationId(), route.apiModelId());
        return route.backend().invoke(rewritten)
            .onFailure(this::isRetryableApiError)
            .recoverWithMulti(err -> Multi.createFrom().failure(
                new ModelChainRetryableException(entry, err)));
    }

    private boolean isRetryableApiError(Throwable t) {
        return t instanceof AgentRateLimitException
            || t instanceof java.net.ConnectException;
    }

    @Override
    public AgentSession openSession(AgentSessionInit init) {
        var route = init.modelQuery() != null ? resolveQuery(init.modelQuery()) : resolve(init.model());
        var rewritten = new AgentSessionInit(
                init.systemPrompt(), init.mcpServers(),
                init.timeout(), init.correlationId(), route.apiModelId());
        if (poolRegistry != null) {
            var pool = poolRegistry.findPool(route.backend().key(), rewritten.model());
            if (pool.isPresent()) {
                return pool.get().checkout(rewritten);
            }
        }
        return route.backend().openSession(rewritten);
    }

    private ResolvedRoute resolveFromConfig(AgentSessionConfig config) {
        if (config.modelChain() != null && !config.modelChain().isEmpty()) {
            return resolveChain(config.modelChain(), availabilityFilter);
        }
        return config.modelQuery() != null
               ? resolveQuery(config.modelQuery())
               : resolve(config.model());
    }

    private ResolvedRoute resolveFromInit(AgentSessionInit init) {
        if (init.modelChain() != null && !init.modelChain().isEmpty()) {
            return resolveChain(init.modelChain(), availabilityFilter);
        }
        return init.modelQuery() != null
               ? resolveQuery(init.modelQuery())
               : resolve(init.model());
    }

    private ResolvedRoute resolveChain(ModelChain chain, ModelAvailabilityFilter filter) {
        List<ModelChain.ModelChainEntry> attempted = new ArrayList<>();
        for (var entry : chain.entries()) {
            attempted.add(entry);
            try {
                ResolvedRoute route = switch (entry) {
                    case ModelChain.ModelChainEntry.Named n -> resolve(n.modelRef());
                    case ModelChain.ModelChainEntry.Queried q -> resolveQuery(q.query());
                };

                if (route.apiModelId() == null) return route;
                var descriptor = modelRegistry.resolveById(route.apiModelId());
                if (descriptor.isPresent() && !filter.isAvailable(descriptor.get())) {
                    LOG.debugf("Chain entry %s resolved but unavailable, trying next", entry);
                    continue;
                }

                LOG.infof("Chain resolved to %s after %d attempt(s) (fallback=%s)",
                           route.apiModelId(), attempted.size(), attempted.size() > 1);
                return route;
            } catch (IllegalArgumentException e) {
                LOG.debugf("Chain entry %s failed resolution: %s", entry, e.getMessage());
            }
        }
        throw new ModelChainExhaustedException(chain, attempted);
    }

    private ResolvedRoute resolveQuery(ModelQuery query) {
        var candidates = modelRegistry.query(query);

        if (candidates.isEmpty()) {
            if (modelRegistry.all().isEmpty()) {
                throw new IllegalArgumentException("No model sources configured — query resolution requires at least one ModelSource.");
            }
            if (query.tier() != null) {
                var availableTiers = modelRegistry.all().stream()
                                                  .map(ModelDescriptor::tier)
                                                  .distinct().sorted().toList();
                throw new IllegalArgumentException(
                        "No model matching tier " + query.tier()
                        + " (default backend: " + defaultBackendKey
                        + "). Available tiers: " + availableTiers);
            }
            throw new IllegalArgumentException("No model matching query " + query);
        }

        var preferred = candidates.stream()
                                  .filter(d -> d.backendKey().equals(defaultBackendKey))
                                  .toList();

        ModelDescriptor selected;
        if (!preferred.isEmpty()) {
            selected = preferred.get(0);
        } else if (query.preferVendor() != null) {
            selected = candidates.stream()
                                 .filter(d -> d.vendor().equals(query.preferVendor()))
                                 .findFirst()
                                 .orElse(candidates.get(0));
        } else {
            selected = candidates.get(0);
        }

        String instanceId = selected.backendInstanceId() != null
                            ? selected.backendInstanceId() : "default";
        var backend = registry.resolve(selected.backendKey(), instanceId);
        if (backend.isEmpty()) {
            throw new IllegalStateException(
                    "Model '" + selected.id() + "' resolved to backend "
                    + selected.backendKey() + "/" + instanceId
                    + ", but no backend with that key/instance is registered");
        }

        LOG.debugf("Query resolved to model %s (backend: %s/%s)",
                   selected.id(), selected.backendKey(), instanceId);
        return new ResolvedRoute(backend.get(), selected.apiModelId());
    }

    private ResolvedRoute resolveTier(ModelTier tier) {
        var query = ModelQuery.builder().tier(tier).build();
        return resolveQuery(query);
    }

    private ResolvedRoute resolve(String model) {
        if (model == null) {
            var backend = registry.resolve(defaultBackendKey, "default");
            if (backend.isEmpty()) {
                throw new IllegalStateException(
                        "No default backend configured: " + defaultBackendKey);
            }
            return new ResolvedRoute(backend.get(), null);
        }

        // Step 0: Alias?
        if (aliases.containsKey(model)) {
            LOG.debugf("Resolving alias: %s", model);
            return resolveQuery(aliases.get(model));
        }

        // Step 1: Tier reference (unambiguous prefix — check first)
        if (ModelRef.isTierRef(model)) {
            return resolveTier(ModelRef.parseTier(model));
        }

        // Step 2: Registry ID
        Optional<ModelDescriptor> descriptor = modelRegistry.resolveById(model);
        if (descriptor.isPresent()) {
            var    d          = descriptor.get();
            String instanceId = d.backendInstanceId() != null ? d.backendInstanceId() : "default";
            var    backend    = registry.resolve(d.backendKey(), instanceId);
            if (backend.isEmpty()) {
                throw new IllegalStateException(
                        "Model '" + model + "' resolved to backend " + d.backendKey()
                        + "/" + instanceId + ", but no backend with that key/instance is registered");
            }
            return new ResolvedRoute(backend.get(), d.apiModelId());
        }

        // Step 3: Backend key
        var backend = registry.resolve(model, "default");
        if (backend.isPresent()) {
            return new ResolvedRoute(backend.get(), null);
        }

        // Step 4: Fail-fast
        throw new IllegalArgumentException("No model or backend for: " + model);
    }
}
