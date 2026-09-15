package io.casehub.platform.llm.config;

import io.casehub.platform.api.model.ModelDescriptor;
import java.util.List;

public record ValidationResult(boolean valid, String errorMessage, List<ModelDescriptor> models) {

    public static ValidationResult success(List<ModelDescriptor> models) {
        return new ValidationResult(true, null, models);
    }

    public static ValidationResult failure(String errorMessage) {
        return new ValidationResult(false, errorMessage, List.of());
    }
}
