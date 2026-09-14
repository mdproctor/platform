package io.casehub.platform.api.model;

import io.casehub.platform.api.mcp.McpDomain;
import io.casehub.platform.api.mcp.PlatformMutation;
import io.casehub.platform.api.mcp.PlatformQuery;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;

class ModelRegistryApiTest {

    @Test
    void interface_has_McpDomain_annotation() {
        McpDomain ann = ModelRegistryApi.class.getAnnotation(McpDomain.class);
        assertThat(ann).isNotNull();
        assertThat(ann.value()).isEqualTo("models");
    }

    @Test
    void listModels_has_PlatformQuery_annotation() throws Exception {
        Method method = ModelRegistryApi.class.getMethod(
            "listModels", String.class, String.class, String.class, String.class, String.class);
        assertThat(method.getAnnotation(PlatformQuery.class)).isNotNull();
    }

    @Test
    void getModel_has_PlatformQuery_annotation() throws Exception {
        Method method = ModelRegistryApi.class.getMethod("getModel", String.class);
        assertThat(method.getAnnotation(PlatformQuery.class)).isNotNull();
    }

    @Test
    void refreshRegistry_has_PlatformMutation_annotation() throws Exception {
        Method method = ModelRegistryApi.class.getMethod("refreshRegistry");
        assertThat(method.getAnnotation(PlatformMutation.class)).isNotNull();
    }
}
