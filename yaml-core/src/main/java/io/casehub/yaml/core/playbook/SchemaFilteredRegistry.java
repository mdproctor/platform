package io.casehub.yaml.core.playbook;

import io.casehub.yaml.plugin.api.Definition;
import io.casehub.yaml.plugin.api.PluginRegistry;

import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

public final class SchemaFilteredRegistry implements PluginRegistry {

    private final PluginRegistry delegate;
    private final Set<String> allowedCapabilities;

    public SchemaFilteredRegistry(PluginRegistry delegate,
                                   Set<String> allowedCapabilities) {
        this.delegate = Objects.requireNonNull(delegate);
        this.allowedCapabilities = Set.copyOf(allowedCapabilities);
    }

    public static SchemaFilteredRegistry forSchema(
            PluginRegistry delegate,
            PlaybookSchemaRegistry schemas,
            String schemaName) {
        return new SchemaFilteredRegistry(
                delegate, schemas.effectiveCapabilities(schemaName));
    }

    @Override
    public void register(Definition definition) {
        delegate.register(definition);
    }

    @Override
    public Optional<Definition> resolve(String actionName) {
        return delegate.resolve(actionName)
                .filter(d -> allowedCapabilities.contains(d.capability()));
    }

    @Override
    public Set<String> availableActions() {
        return delegate.availableActions().stream()
                .filter(name -> delegate.resolve(name)
                        .map(d -> allowedCapabilities.contains(d.capability()))
                        .orElse(false))
                .collect(Collectors.toUnmodifiableSet());
    }
}
