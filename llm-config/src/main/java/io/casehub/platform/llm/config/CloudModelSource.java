package io.casehub.platform.llm.config;

import io.casehub.platform.api.model.ModelSource;

public interface CloudModelSource extends ModelSource {
    CloudSourceStatus status();
}
