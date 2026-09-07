package io.casehub.platform.endpoints.memory;

import io.casehub.platform.api.endpoints.EndpointCapability;
import io.casehub.platform.api.endpoints.EndpointDescriptor;
import io.casehub.platform.api.endpoints.EndpointProtocol;
import io.casehub.platform.api.endpoints.EndpointPropertyKeys;
import io.casehub.platform.api.endpoints.EndpointQuery;
import io.casehub.platform.api.endpoints.EndpointRegistry;
import io.casehub.platform.api.endpoints.EndpointType;
import io.casehub.platform.api.identity.TenancyConstants;
import io.casehub.platform.api.path.Path;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

class InMemoryEndpointRegistryTest {

    private static final String TENANT_A  = "tenant-a";
    private static final String TENANT_B  = "tenant-b";
    private static final String PLATFORM  = TenancyConstants.PLATFORM_TENANT_ID;

    private static final Path SF_PATH = Path.of("external", "salesforce", "prod");
    private static final Path QH_PATH = Path.of("casehubio", "qhorus", "api");

    private EndpointRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new InMemoryEndpointRegistry();
    }

    // --- register + resolve round-trip ---

    @Test
    void register_and_resolve_returns_descriptor() {
        final var desc = descriptor(SF_PATH, TENANT_A, EndpointType.SYSTEM, EndpointProtocol.HTTP);
        registry.register(desc);

        final Optional<EndpointDescriptor> result = registry.resolve(SF_PATH, TENANT_A);

        assertThat(result).contains(desc);
    }

    @Test
    void resolve_unknown_path_returns_empty() {
        assertThat(registry.resolve(Path.of("no", "such", "path"), TENANT_A)).isEmpty();
    }

    // --- tenant isolation ---

    @Test
    void tenant_a_endpoint_invisible_to_tenant_b() {
        registry.register(descriptor(SF_PATH, TENANT_A, EndpointType.SYSTEM, EndpointProtocol.HTTP));

        assertThat(registry.resolve(SF_PATH, TENANT_B)).isEmpty();
    }

    @Test
    void discover_never_returns_tenant_a_endpoints_for_tenant_b_query() {
        registry.register(descriptor(SF_PATH, TENANT_A, EndpointType.SYSTEM, EndpointProtocol.HTTP));

        final List<EndpointDescriptor> results = registry.discover(
                new EndpointQuery(TENANT_B, null, null, Set.of()));

        assertThat(results).isEmpty();
    }

    // --- platform-global visibility ---

    @Test
    void platform_global_endpoint_visible_to_all_tenants_via_resolve() {
        final var global = descriptor(QH_PATH, PLATFORM, EndpointType.SERVICE, EndpointProtocol.QHORUS);
        registry.register(global);

        assertThat(registry.resolve(QH_PATH, TENANT_A)).contains(global);
        assertThat(registry.resolve(QH_PATH, TENANT_B)).contains(global);
    }

    @Test
    void platform_global_endpoint_returned_by_discover_for_any_tenant() {
        final var global = descriptor(QH_PATH, PLATFORM, EndpointType.SERVICE, EndpointProtocol.QHORUS);
        registry.register(global);

        final List<EndpointDescriptor> a = registry.discover(
                new EndpointQuery(TENANT_A, null, null, Set.of()));
        final List<EndpointDescriptor> b = registry.discover(
                new EndpointQuery(TENANT_B, null, null, Set.of()));

        assertThat(a).contains(global);
        assertThat(b).contains(global);
    }

    // --- resolve() priority: tenant-specific wins over platform-global ---

    @Test
    void resolve_prefers_tenant_specific_over_platform_global_for_same_path() {
        final var global  = descriptor(SF_PATH, PLATFORM,  EndpointType.SYSTEM, EndpointProtocol.HTTP,
                Map.of(EndpointPropertyKeys.URL, "https://global.salesforce.com"), null,
                Set.of(EndpointCapability.SEND));
        final var tenant  = descriptor(SF_PATH, TENANT_A,  EndpointType.SYSTEM, EndpointProtocol.HTTP,
                Map.of(EndpointPropertyKeys.URL, "https://tenanta.salesforce.com"), null,
                Set.of(EndpointCapability.SEND, EndpointCapability.QUERY));

        registry.register(global);
        registry.register(tenant);

        assertThat(registry.resolve(SF_PATH, TENANT_A)).contains(tenant);
    }

    @Test
    void resolve_falls_back_to_platform_global_when_no_tenant_specific_exists() {
        final var global = descriptor(SF_PATH, PLATFORM, EndpointType.SYSTEM, EndpointProtocol.HTTP);
        registry.register(global);

        assertThat(registry.resolve(SF_PATH, TENANT_A)).contains(global);
    }

    // --- discover() returns both tenant-specific and platform-global for same path ---

    @Test
    void discover_returns_both_tenant_specific_and_platform_global_for_same_path() {
        final var global = descriptor(SF_PATH, PLATFORM,  EndpointType.SYSTEM, EndpointProtocol.HTTP);
        final var tenant = descriptor(SF_PATH, TENANT_A,  EndpointType.SYSTEM, EndpointProtocol.HTTP);

        registry.register(global);
        registry.register(tenant);

        final List<EndpointDescriptor> results = registry.discover(
                new EndpointQuery(TENANT_A, null, null, Set.of()));

        assertThat(results).containsExactlyInAnyOrder(global, tenant);
    }

    // --- deregister ---

    @Test
    void deregister_removes_endpoint_and_resolve_returns_empty() {
        registry.register(descriptor(SF_PATH, TENANT_A, EndpointType.SYSTEM, EndpointProtocol.HTTP));
        registry.deregister(SF_PATH, TENANT_A);

        assertThat(registry.resolve(SF_PATH, TENANT_A)).isEmpty();
    }

    @Test
    void deregister_is_no_op_when_endpoint_not_registered() {
        // Must not throw
        registry.deregister(Path.of("not", "there"), TENANT_A);
    }

    @Test
    void deregister_only_removes_the_specified_tenancy_slot() {
        final var global = descriptor(SF_PATH, PLATFORM, EndpointType.SYSTEM, EndpointProtocol.HTTP);
        final var tenant = descriptor(SF_PATH, TENANT_A, EndpointType.SYSTEM, EndpointProtocol.HTTP);

        registry.register(global);
        registry.register(tenant);
        registry.deregister(SF_PATH, TENANT_A);

        // Global remains; tenant-specific resolve falls back to global
        assertThat(registry.resolve(SF_PATH, TENANT_A)).contains(global);
    }

    // --- discover: type filter ---

    @Test
    void discover_type_filter_null_matches_all_types() {
        registry.register(descriptor(SF_PATH, TENANT_A, EndpointType.SYSTEM,  EndpointProtocol.HTTP));
        registry.register(descriptor(QH_PATH, TENANT_A, EndpointType.SERVICE, EndpointProtocol.QHORUS));

        final List<EndpointDescriptor> results = registry.discover(
                new EndpointQuery(TENANT_A, null, null, Set.of()));

        assertThat(results).hasSize(2);
    }

    @Test
    void discover_type_filter_restricts_to_matching_type() {
        final var system  = descriptor(SF_PATH, TENANT_A, EndpointType.SYSTEM,  EndpointProtocol.HTTP);
        final var service = descriptor(QH_PATH, TENANT_A, EndpointType.SERVICE, EndpointProtocol.QHORUS);

        registry.register(system);
        registry.register(service);

        final List<EndpointDescriptor> results = registry.discover(
                new EndpointQuery(TENANT_A, EndpointType.SYSTEM, null, Set.of()));

        assertThat(results).containsExactly(system);
    }

    // --- discover: protocol filter ---

    @Test
    void discover_protocol_filter_null_matches_all_protocols() {
        // Two endpoints with the SAME type but DIFFERENT protocols — isolates the protocol wildcard
        final var httpEp  = descriptor(SF_PATH, TENANT_A, EndpointType.SYSTEM, EndpointProtocol.HTTP);
        final var kafkaEp = descriptor(Path.of("events", "orders"), TENANT_A,
                EndpointType.SYSTEM, EndpointProtocol.KAFKA);

        registry.register(httpEp);
        registry.register(kafkaEp);

        final List<EndpointDescriptor> results = registry.discover(
                new EndpointQuery(TENANT_A, EndpointType.SYSTEM, null, Set.of()));

        assertThat(results).containsExactlyInAnyOrder(httpEp, kafkaEp);
    }

    @Test
    void discover_protocol_filter_restricts_to_matching_protocol() {
        final var http   = descriptor(SF_PATH, TENANT_A, EndpointType.SYSTEM,  EndpointProtocol.HTTP);
        final var kafka  = descriptor(Path.of("events", "orders"), TENANT_A, EndpointType.SYSTEM, EndpointProtocol.KAFKA);

        registry.register(http);
        registry.register(kafka);

        final List<EndpointDescriptor> results = registry.discover(
                new EndpointQuery(TENANT_A, null, EndpointProtocol.KAFKA, Set.of()));

        assertThat(results).containsExactly(kafka);
    }

    // --- discover: capability filter ---

    @Test
    void discover_empty_requiredCapabilities_matches_all_descriptors() {
        final var send    = descriptorWithCaps(SF_PATH,  TENANT_A, Set.of(EndpointCapability.SEND));
        final var receive = descriptorWithCaps(QH_PATH,  TENANT_A, Set.of(EndpointCapability.RECEIVE));

        registry.register(send);
        registry.register(receive);

        final List<EndpointDescriptor> results = registry.discover(
                new EndpointQuery(TENANT_A, null, null, Set.of()));

        assertThat(results).containsExactlyInAnyOrder(send, receive);
    }

    @Test
    void discover_capability_filter_requires_subset_match() {
        final var sendOnly     = descriptorWithCaps(SF_PATH, TENANT_A,
                Set.of(EndpointCapability.SEND));
        final var sendAndQuery = descriptorWithCaps(QH_PATH, TENANT_A,
                Set.of(EndpointCapability.SEND, EndpointCapability.QUERY));

        registry.register(sendOnly);
        registry.register(sendAndQuery);

        // Require SEND + QUERY — only sendAndQuery satisfies both
        final List<EndpointDescriptor> results = registry.discover(
                new EndpointQuery(TENANT_A, null, null,
                        Set.of(EndpointCapability.SEND, EndpointCapability.QUERY)));

        assertThat(results).containsExactly(sendAndQuery);
    }

    // --- discover: query with PLATFORM_TENANT_ID returns only platform-global ---

    @Test
    void discover_with_platform_tenancy_id_returns_only_platform_global_endpoints() {
        final var global = descriptor(SF_PATH, PLATFORM, EndpointType.SYSTEM, EndpointProtocol.HTTP);
        final var tenant = descriptor(QH_PATH, TENANT_A, EndpointType.SERVICE, EndpointProtocol.QHORUS);

        registry.register(global);
        registry.register(tenant);

        final List<EndpointDescriptor> results = registry.discover(
                new EndpointQuery(PLATFORM, null, null, Set.of()));

        // Only platform-global matches — the two OR clauses both reduce to
        // descriptor.tenancyId == PLATFORM_TENANT_ID, so no tenant-specific leak
        assertThat(results).containsExactly(global);
    }

    // --- upsert semantics ---

    @Test
    void register_same_key_replaces_descriptor_last_write_wins() {
        final var original = descriptor(SF_PATH, TENANT_A, EndpointType.SYSTEM,
                EndpointProtocol.HTTP,
                Map.of(EndpointPropertyKeys.URL, "https://v1.salesforce.com"),
                null, Set.of(EndpointCapability.SEND));
        final var updated  = descriptor(SF_PATH, TENANT_A, EndpointType.SYSTEM,
                EndpointProtocol.HTTP,
                Map.of(EndpointPropertyKeys.URL, "https://v2.salesforce.com"),
                null, Set.of(EndpointCapability.SEND, EndpointCapability.QUERY));

        registry.register(original);
        registry.register(updated);

        assertThat(registry.resolve(SF_PATH, TENANT_A)).contains(updated);
    }

    @Test
    void upsert_has_no_capability_merge_semantics() {
        // A producer registering SEND then a consumer registering RECEIVE:
        // second wins; no merge — final descriptor has only RECEIVE
        final var producer = descriptorWithCaps(SF_PATH, TENANT_A, Set.of(EndpointCapability.SEND));
        final var consumer = descriptorWithCaps(SF_PATH, TENANT_A, Set.of(EndpointCapability.RECEIVE));

        registry.register(producer);
        registry.register(consumer);

        assertThat(registry.resolve(SF_PATH, TENANT_A))
                .hasValueSatisfying(d -> assertThat(d.capabilities())
                        .containsExactly(EndpointCapability.RECEIVE));
    }

    // --- constructor null-rejection ---

    @Test
    void endpointDescriptor_rejects_null_properties() {
        assertThatNullPointerException().isThrownBy(() ->
                new EndpointDescriptor(SF_PATH, TENANT_A, EndpointType.SYSTEM,
                        EndpointProtocol.HTTP, null, null, Set.of()));
    }

    @Test
    void endpointDescriptor_rejects_null_capabilities() {
        assertThatNullPointerException().isThrownBy(() ->
                new EndpointDescriptor(SF_PATH, TENANT_A, EndpointType.SYSTEM,
                        EndpointProtocol.HTTP, Map.of(), null, null));
    }

    // --- RegistryKey equality ---

    @Test
    void two_paths_with_same_value_and_same_tenancyId_resolve_to_same_slot() {
        // Path.of and Path.parse with "/" separator produce the same value
        final var viaOf    = Path.of("external", "salesforce", "prod");
        final var viaParse = Path.parse("external/salesforce/prod");

        registry.register(descriptor(viaOf,   TENANT_A, EndpointType.SYSTEM, EndpointProtocol.HTTP));
        // Re-register via parsed path — must overwrite the same slot
        final var updated = descriptor(viaParse, TENANT_A, EndpointType.SYSTEM, EndpointProtocol.GRPC);
        registry.register(updated);

        assertThat(registry.resolve(viaOf, TENANT_A)).contains(updated);
    }

    // --- EndpointPropertyKeys ---

    @Test
    void endpointPropertyKeys_url_constant_is_url() {
        assertThat(EndpointPropertyKeys.URL).isEqualTo("url");
    }

    @Test
    void endpointPropertyKeys_topic_constant_is_topic() {
        assertThat(EndpointPropertyKeys.TOPIC).isEqualTo("topic");
    }

    // --- helpers ---

    private static EndpointDescriptor descriptor(
            final Path path, final String tenancyId,
            final EndpointType type, final EndpointProtocol protocol) {
        return new EndpointDescriptor(
                path, tenancyId, type, protocol,
                Map.of(EndpointPropertyKeys.URL, "https://example.com"),
                null,
                Set.of(EndpointCapability.SEND));
    }

    private static EndpointDescriptor descriptor(
            final Path path, final String tenancyId,
            final EndpointType type, final EndpointProtocol protocol,
            final Map<String, String> properties, final String credentialRef,
            final Set<EndpointCapability> capabilities) {
        return new EndpointDescriptor(path, tenancyId, type, protocol,
                properties, credentialRef, capabilities);
    }

    private static EndpointDescriptor descriptorWithCaps(
            final Path path, final String tenancyId,
            final Set<EndpointCapability> capabilities) {
        return new EndpointDescriptor(
                path, tenancyId, EndpointType.SYSTEM, EndpointProtocol.HTTP,
                Map.of(), null, capabilities);
    }
}
