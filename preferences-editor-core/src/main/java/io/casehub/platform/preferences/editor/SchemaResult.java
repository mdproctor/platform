package io.casehub.platform.preferences.editor;

import io.casehub.platform.api.preferences.PreferenceSchemaDescriptor;

import java.util.List;

public record SchemaResult(List<PreferenceSchemaDescriptor> schemas, String version) {}
