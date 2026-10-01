package io.casehub.yaml.core.error;

import java.util.List;
import java.util.stream.Collectors;

public class YamlValidationException extends RuntimeException {

    private final List<YamlError> errors;

    public YamlValidationException(List<YamlError> errors) {
        super(errors.size() + " validation error(s):\n"
                + errors.stream().map(YamlError::summary)
                        .collect(Collectors.joining("\n  - ", "  - ", "")));
        this.errors = List.copyOf(errors);
    }

    public List<YamlError> errors() { return errors; }
}
