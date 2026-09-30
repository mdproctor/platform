package io.casehub.platform.agent.config;

import com.fasterxml.jackson.annotation.JsonProperty;

public record SourceAuth(
        String type,
        String credential,
        @JsonProperty("header-name") String headerName
) {}
