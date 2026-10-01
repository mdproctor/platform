package io.casehub.yaml.core.error;

import java.util.ArrayList;
import java.util.List;

public final class YamlErrorCollector {

    private final List<YamlError> errors = new ArrayList<>();

    public void add(YamlError error) { errors.add(error); }

    public boolean hasErrors() { return !errors.isEmpty(); }

    public List<YamlError> errors() { return List.copyOf(errors); }

    public void throwIfErrors() {
        if (!errors.isEmpty()) {
            throw new YamlValidationException(List.copyOf(errors));
        }
    }
}
