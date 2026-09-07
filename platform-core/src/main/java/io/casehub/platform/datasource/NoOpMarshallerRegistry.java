package io.casehub.platform.datasource;

import io.casehub.platform.api.datasource.Marshaller;
import io.casehub.platform.api.datasource.MarshallerRegistry;

import java.util.Optional;

public class NoOpMarshallerRegistry implements MarshallerRegistry {
    @Override public void register(String key, Marshaller<?, ?> marshaller) {}
    @Override public Optional<Marshaller<?, ?>> resolve(String key) { return Optional.empty(); }
}
