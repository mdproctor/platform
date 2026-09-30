package io.casehub.platform.agent.config;

public record SourceDeclaration(String uri, int priority, SourceAuth auth, SourceIntegrity integrity) {}
