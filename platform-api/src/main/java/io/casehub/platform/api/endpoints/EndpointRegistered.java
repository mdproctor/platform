package io.casehub.platform.api.endpoints;

/**
 * CDI event fired by non-no-op {@link EndpointRegistry} implementations after every
 * successful {@link EndpointRegistry#register(EndpointDescriptor)} call.
 *
 * <p>Consumers use this event to react to new endpoint registrations at runtime
 * (e.g. stream modules building Camel routes). The no-op {@code @DefaultBean}
 * implementation must NOT fire this event — firing it would trigger route
 * creation for phantom endpoints that are never actually stored.
 *
 * <p>Any future non-no-op {@link EndpointRegistry} implementation has a required
 * obligation to fire this event after storing the descriptor.
 */
public record EndpointRegistered(EndpointDescriptor descriptor) {}
