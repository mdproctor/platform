package io.casehub.yaml.step.catalog;

import io.casehub.yaml.core.step.Declaration;
import io.casehub.yaml.core.step.InvokeBinding;
import io.casehub.yaml.plugin.api.Definition;
import io.casehub.yaml.plugin.api.Parameter;
import io.casehub.yaml.plugin.api.ParameterType;
import io.casehub.yaml.plugin.api.Portability;
import io.casehub.yaml.plugin.api.Result;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiFunction;

import static org.assertj.core.api.Assertions.assertThat;

class McpToolSourceTest {

    @Test
    void populatesRegistryFromDeclarations() {
        Declaration decl = new Declaration("acl_canAccess", "Check access",
                Map.of("actorId", new Parameter(ParameterType.STRING, true, null, null, null, "Actor ID"),
                       "resourceId", new Parameter(ParameterType.STRING, true, null, null, null, "Resource ID")),
                Map.of(), new InvokeBinding.Mcp("acl_canAccess"));

        BiFunction<String, Map<String, Object>, Map<String, Object>> invoker =
                (name, params) -> Map.of("allowed", true);

        McpToolSource source = new McpToolSource(Map.of("acl_canAccess", decl), invoker);

        var registry = new CompositePluginRegistry();
        source.populate(registry);

        assertThat(registry.resolve("acl_canAccess")).isPresent();
        Definition def = registry.resolve("acl_canAccess").get();
        assertThat(def.inputs()).containsKey("actorId");
        assertThat(def.inputs()).containsKey("resourceId");
    }

    @Test
    void actionPassesParamsToInvoker() {
        Declaration decl = new Declaration("acl_canAccess", null,
                Map.of("actorId", new Parameter(ParameterType.STRING, true, null, null, null, null)),
                Map.of(), new InvokeBinding.Mcp("acl_canAccess"));

        AtomicReference<Map<String, Object>> captured = new AtomicReference<>();
        BiFunction<String, Map<String, Object>, Map<String, Object>> invoker =
                (name, params) -> { captured.set(params); return Map.of("ok", true); };

        McpToolSource source = new McpToolSource(Map.of("acl_canAccess", decl), invoker);

        var registry = new CompositePluginRegistry();
        source.populate(registry);

        Map<String, Object> params = Map.of("actorId", "user-1", "resourceId", "case:42");
        Result              result = registry.resolve("acl_canAccess").get().action().execute(params, null);

        assertThat(result.isSuccess()).isTrue();
        assertThat(captured.get()).containsEntry("actorId", "user-1");
    }

    @Test
    void actionHandlesInvokerException() {
        Declaration decl = new Declaration("bad_tool", null,
                Map.of(), Map.of(), new InvokeBinding.Mcp("bad_tool"));

        McpToolSource source = new McpToolSource(Map.of("bad_tool", decl),
                (name, params) -> { throw new RuntimeException("connection refused"); });

        var registry = new CompositePluginRegistry();
        source.populate(registry);

        Result result = registry.resolve("bad_tool").get().action().execute(Map.of(), null);

        assertThat(result.isSuccess()).isFalse();
        assertThat(result).isInstanceOf(Result.Failure.class);
    }

    @Test
    void firstRegistrationWins() {
        Declaration decl = new Declaration("tool", null, Map.of(), Map.of(),
                new InvokeBinding.Mcp("tool"));

        McpToolSource source = new McpToolSource(Map.of("tool", decl),
                (name, params) -> Map.of());

        var registry = new CompositePluginRegistry();
        registry.register(new Definition("tool", "existing", Map.of(), Map.of(),
                Portability.JAVA, (params, services) -> Result.of(Map.of()), null));

        source.populate(registry);

        assertThat(registry.resolve("tool").get().description()).isEqualTo("existing");
    }
}
