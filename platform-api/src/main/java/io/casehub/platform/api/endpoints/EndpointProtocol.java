package io.casehub.platform.api.endpoints;

/**
 * Identifies how to reach an endpoint — either by wire protocol or by invocation mechanism.
 *
 * <p>{@link #HTTP}, {@link #GRPC}, {@link #KAFKA}, and {@link #AMQP} name the underlying transport.
 * {@link #CAMEL} and {@link #QHORUS} name the invocation mechanism: {@code CAMEL} routes
 * through the Apache Camel dispatcher regardless of the route's own transport;
 * {@code QHORUS} invokes via the Qhorus channel dispatch API.
 * {@link #MCP} is the Model Context Protocol — a protocol specification for LLM tool
 * invocation over a defined wire format, treated at the same tier as HTTP and GRPC.
 *
 * <p>Closed set — adding a protocol requires an enum change, which forces every caller
 * to name the protocol explicitly and eliminates silent inconsistencies in
 * {@link EndpointRegistry#discover(EndpointQuery)} filters.
 */
public enum EndpointProtocol {

    /** HTTP or HTTPS transport. HTTP subsumes HTTPS — the scheme lives in the URL property. */
    HTTP,

    /** gRPC transport. */
    GRPC,

    /** Apache Kafka message streaming. Use {@link EndpointPropertyKeys#TOPIC} for the topic name. */
    KAFKA,

    /**
     * AMQP message broker transport. Use {@link EndpointPropertyKeys#TOPIC} for queue or
     * topic name. {@link EndpointPropertyKeys#URL} does not apply — broker connection
     * is Quarkus-managed via standard config (e.g. {@code amqp-host}, {@code amqp-port}).
     */
    AMQP,

    /**
     * Model Context Protocol — LLM tool invocation protocol.
     * Use {@link EndpointPropertyKeys#URL} for the MCP server base URL.
     */
    MCP,

    /**
     * Apache Camel invocation mechanism. The endpoint URI (any valid Camel endpoint
     * expression) is stored under {@link EndpointPropertyKeys#URL}. The Camel route's
     * own underlying transport is an implementation detail.
     */
    CAMEL,

    /**
     * Qhorus channel dispatch API invocation mechanism.
     * Use {@link EndpointPropertyKeys#URL} for the Qhorus REST base URL.
     */
    QHORUS
}
