package io.casehub.platform.mcp.spring;

import io.casehub.platform.api.mcp.McpDomain;
import io.casehub.platform.api.mcp.PlatformMutation;
import io.casehub.platform.api.mcp.PlatformQuery;
import io.casehub.platform.mcp.DomainModel;
import io.casehub.platform.mcp.DomainModelRegistry;
import io.casehub.platform.mcp.OperationDescriptor;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SpringModelScannerTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(McpSpringAutoConfiguration.class));

    @Test
    void discoversBeansImplementingMcpDomainInterface() {
        runner.withBean("testService", TestServiceImpl.class, TestServiceImpl::new)
                .run(context -> {
                    DomainModelRegistry registry = context.getBean(DomainModelRegistry.class);
                    assertThat(registry.getDomains()).hasSize(1);

                    DomainModel domain = registry.getDomain("test-domain").orElseThrow();
                    assertThat(domain.name()).isEqualTo("test-domain");
                    assertThat(domain.operations()).hasSize(2);

                    List<String> opNames = domain.operations().stream()
                            .map(OperationDescriptor::name).toList();
                    assertThat(opNames).containsExactlyInAnyOrder("getItems", "createItem");

                    OperationDescriptor getItems = domain.operations().stream()
                            .filter(op -> op.name().equals("getItems")).findFirst().orElseThrow();
                    assertThat(getItems.type()).isEqualTo(OperationDescriptor.OperationType.QUERY);
                    assertThat(getItems.summary()).isEqualTo("List all items");

                    OperationDescriptor createItem = domain.operations().stream()
                            .filter(op -> op.name().equals("createItem")).findFirst().orElseThrow();
                    assertThat(createItem.type()).isEqualTo(OperationDescriptor.OperationType.MUTATION);
                });
    }

    @Test
    void ignoresBeansWithoutMcpDomainInterface() {
        runner.withBean("plainService", PlainService.class, PlainService::new)
                .run(context -> {
                    DomainModelRegistry registry = context.getBean(DomainModelRegistry.class);
                    assertThat(registry.getDomains()).isEmpty();
                });
    }

    @Test
    void dispatchesOperationToBean() {
        runner.withBean("testService", TestServiceImpl.class, TestServiceImpl::new)
                .run(context -> {
                    SpringOperationDispatcher dispatcher = context.getBean(SpringOperationDispatcher.class);
                    Object result = dispatcher.dispatch("test-domain", "getItems", null);
                    assertThat(result).isInstanceOf(List.class);
                    @SuppressWarnings("unchecked")
                    List<String> items = (List<String>) result;
                    assertThat(items).containsExactly("item1", "item2");
                });
    }

    @Test
    void toolCallbackProviderRegistersAllTools() {
        runner.withBean("testService", TestServiceImpl.class, TestServiceImpl::new)
                .run(context -> {
                    CaseHubToolCallbackProvider provider = context.getBean(CaseHubToolCallbackProvider.class);
                    var tools = provider.getToolCallbacks();

                    assertThat(tools).hasSizeGreaterThanOrEqualTo(4);

                    List<String> toolNames = java.util.Arrays.stream(tools)
                            .map(t -> t.getToolDefinition().name())
                            .toList();
                    assertThat(toolNames).contains(
                            "casehub_model",
                            "casehub_action",
                            "test_domain_getItems",
                            "test_domain_createItem");
                });
    }

    @McpDomain("test-domain")
    interface TestService {
        @PlatformQuery("List all items")
        List<String> getItems();

        @PlatformMutation("Create a new item")
        String createItem(String name);
    }

    static class TestServiceImpl implements TestService {
        @Override
        public List<String> getItems() {
            return List.of("item1", "item2");
        }

        @Override
        public String createItem(String name) {
            return "created: " + name;
        }
    }

    static class PlainService {
        public String hello() { return "hi"; }
    }
}
