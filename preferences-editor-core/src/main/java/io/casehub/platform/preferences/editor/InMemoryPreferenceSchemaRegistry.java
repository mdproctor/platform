package io.casehub.platform.preferences.editor;

import io.casehub.platform.api.preferences.PreferenceSchemaDescriptor;
import io.casehub.platform.api.preferences.PreferenceSchemaRegistry;

import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;
import java.util.logging.Logger;

public class InMemoryPreferenceSchemaRegistry implements PreferenceSchemaRegistry {

    private static final Logger LOG = Logger.getLogger(InMemoryPreferenceSchemaRegistry.class.getName());

    private final ConcurrentHashMap<String, PreferenceSchemaDescriptor> entries = new ConcurrentHashMap<>();
    private final AtomicLong version = new AtomicLong();

    @Override
    public void register(PreferenceSchemaDescriptor descriptor) {
        PreferenceSchemaDescriptor existing = entries.put(descriptor.qualifiedName(), descriptor);
        version.incrementAndGet();
        if (existing != null && !existing.equals(descriptor)) {
            LOG.log(Level.WARNING, "PreferenceSchemaDescriptor overwritten for ''{0}''", descriptor.qualifiedName());
        }
    }

    @Override
    public Optional<PreferenceSchemaDescriptor> resolve(String qualifiedName) {
        return Optional.ofNullable(entries.get(qualifiedName));
    }

    @Override
    public Set<PreferenceSchemaDescriptor> discover() {
        return Set.copyOf(entries.values());
    }

    @Override
    public long version() {
        return version.get();
    }
}
