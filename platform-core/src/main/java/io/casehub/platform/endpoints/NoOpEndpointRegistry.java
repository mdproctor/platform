package io.casehub.platform.endpoints;

import io.casehub.platform.api.endpoints.EndpointDescriptor;
import io.casehub.platform.api.endpoints.EndpointQuery;
import io.casehub.platform.api.endpoints.EndpointRegistry;
import io.casehub.platform.api.path.Path;

import java.util.List;
import java.util.Optional;

public class NoOpEndpointRegistry implements EndpointRegistry {
    @Override public void register(final EndpointDescriptor endpoint) {}
    @Override public Optional<EndpointDescriptor> resolve(final Path path, final String tenancyId) { return Optional.empty(); }
    @Override public List<EndpointDescriptor> discover(final EndpointQuery query) { return List.of(); }
    @Override public void deregister(final Path path, final String tenancyId) {}
}
