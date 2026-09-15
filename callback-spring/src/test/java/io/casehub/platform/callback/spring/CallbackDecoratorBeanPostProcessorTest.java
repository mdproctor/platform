package io.casehub.platform.callback.spring;

import io.casehub.platform.api.callback.CallbackRegistration;
import io.casehub.platform.api.callback.CallbackRegistrationRequest;
import io.casehub.platform.api.callback.CallbackRegistry;
import io.casehub.platform.api.governance.ExecutionPolicy;
import io.casehub.platform.api.identity.CurrentPrincipal;
import io.casehub.platform.api.mcp.CallbackEligible;
import io.casehub.platform.callback.CallbackInvoker;
import io.casehub.platform.callback.inmem.InMemoryCallbackRegistry;
import io.casehub.platform.governance.PolicyEnforcer;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

class CallbackDecoratorBeanPostProcessorTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(CallbackSpringAutoConfiguration.class))
            .withBean(CallbackRegistry.class, InMemoryCallbackRegistry::new)
            .withBean(CurrentPrincipal.class, () -> new StubPrincipal("tenant-1"))
            .withBean(PolicyEnforcer.class, PassthroughPolicyEnforcer::new);

    @Test
    void wrapsCallbackEligibleBean() {
        runner.withBean("greeter", GreeterImpl.class, GreeterImpl::new)
                .run(context -> {
                    Greeter greeter = context.getBean(Greeter.class);
                    assertThat(greeter).isNotInstanceOf(GreeterImpl.class);
                    assertThat(greeter.greet("world")).isEqualTo("hello world");
                });
    }

    @Test
    void delegatesWhenNoRegistrations() {
        runner.withBean("greeter", GreeterImpl.class, GreeterImpl::new)
                .run(context -> {
                    Greeter greeter = context.getBean(Greeter.class);
                    assertThat(greeter.greet("test")).isEqualTo("hello test");
                });
    }

    @Test
    void doesNotWrapNonEligibleBean() {
        runner.withBean("plain", PlainServiceImpl.class, PlainServiceImpl::new)
                .run(context -> {
                    PlainService service = context.getBean(PlainService.class);
                    assertThat(service).isInstanceOf(PlainServiceImpl.class);
                });
    }

    @Test
    void fanOutSingleImplDelegatesOnNoResult() {
        runner.withBean("selector", SelectorImpl.class, SelectorImpl::new)
                .run(context -> {
                    Selector selector = context.getBean(Selector.class);
                    assertThat(selector).isNotInstanceOf(SelectorImpl.class);
                    assertThat(selector.select("x")).isEqualTo("selected: x");
                });
    }

    @Test
    void toKebabCaseConversions() {
        assertThat(CallbackDecoratorBeanPostProcessor.toKebabCase("GreetingService"))
                .isEqualTo("greeting-service");
        assertThat(CallbackDecoratorBeanPostProcessor.toKebabCase("HTMLParser"))
                .isEqualTo("html-parser");
        assertThat(CallbackDecoratorBeanPostProcessor.toKebabCase("simple"))
                .isEqualTo("simple");
    }

    @CallbackEligible(name = "greeter")
    interface Greeter {
        String greet(String name);
        void log(String message);
    }

    static class GreeterImpl implements Greeter {
        final List<String> logged = new ArrayList<>();

        @Override
        public String greet(String name) {
            return "hello " + name;
        }

        @Override
        public void log(String message) {
            logged.add(message);
        }
    }

    @CallbackEligible(name = "selector", fanOut = false)
    interface Selector {
        String select(String input);
    }

    static class SelectorImpl implements Selector {
        @Override
        public String select(String input) {
            return "selected: " + input;
        }
    }

    interface PlainService {
        String doWork();
    }

    static class PlainServiceImpl implements PlainService {
        @Override
        public String doWork() {
            return "done";
        }
    }

    static class StubPrincipal implements CurrentPrincipal {
        private final String tenancyId;

        StubPrincipal(String tenancyId) {
            this.tenancyId = tenancyId;
        }

        @Override
        public String actorId() {
            return "test-actor";
        }

        @Override
        public Set<String> groups() {
            return Set.of();
        }

        @Override
        public String tenancyId() {
            return tenancyId;
        }

        @Override
        public boolean isCrossTenantAdmin() {
            return false;
        }
    }

    static class PassthroughPolicyEnforcer implements PolicyEnforcer {
        @Override
        public <T> T execute(ExecutionPolicy policy, Supplier<T> action) {
            return action.get();
        }
    }
}
