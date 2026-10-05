package io.casehub.yaml.core.playbook;

import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import static io.casehub.yaml.core.playbook.PlaybookCapabilities.*;

public final class MapPlaybookSchemaRegistry implements PlaybookSchemaRegistry {

    private static final Set<String> SHARED_CAPABILITIES = Set.of(
            STEPS, VARIABLE_RESOLUTION, DECORATORS, CONTROL_FLOW,
            MODULES, CONCURRENCY, STATE_MACHINE
    );

    private final Map<String, PlaybookSchemaDescriptor> schemas = new ConcurrentHashMap<>();

    public MapPlaybookSchemaRegistry() {
        registerBuiltIns();
    }

    @Override
    public void register(PlaybookSchemaDescriptor descriptor) {
        schemas.put(descriptor.name(), descriptor);
    }

    @Override
    public Optional<PlaybookSchemaDescriptor> resolve(String schemaName) {
        return Optional.ofNullable(schemas.get(schemaName));
    }

    @Override
    public Collection<PlaybookSchemaDescriptor> all() {
        return schemas.values();
    }

    private void registerBuiltIns() {
        var clientCaps = new java.util.HashSet<>(SHARED_CAPABILITIES);
        clientCaps.addAll(Set.of(CHAPTERS, SECTIONS, ARIA, SPOTLIGHT, TUTORIALS));
        register(PlaybookSchemaDescriptor.builtIn(PlaybookSchemas.CLIENT, clientCaps));

        var serverCaps = new java.util.HashSet<>(SHARED_CAPABILITIES);
        serverCaps.addAll(Set.of(ORCHESTRATION, CORRELATION, MCP_INVOKE, GRAPHQL_INVOKE, CODEGEN));
        register(PlaybookSchemaDescriptor.builtIn(PlaybookSchemas.SERVER, serverCaps));
    }
}
