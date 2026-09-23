package io.casehub.platform.expression.spring;

import io.casehub.platform.api.expression.InvocationPolicy;
import io.casehub.platform.expression.AllowListInvocationPolicy;
import io.casehub.platform.expression.ReflectiveBeanInvoker;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;

import java.util.Set;

@AutoConfiguration
@EnableConfigurationProperties(SpringBeanInvokerAutoConfiguration.SpringInvokeProperties.class)
public class SpringBeanInvokerAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public InvocationPolicy invocationPolicy(SpringInvokeProperties properties) {
        return new AllowListInvocationPolicy(properties.allowedPackages());
    }

    @Bean
    @ConditionalOnMissingBean
    public ReflectiveBeanInvoker beanInvoker(ApplicationContext ctx, InvocationPolicy policy) {
        return new ReflectiveBeanInvoker(className -> {
            try {
                return ctx.getBean(Class.forName(className));
            } catch (ClassNotFoundException e) {
                throw new IllegalArgumentException("Class not found: " + className, e);
            }
        }, policy);
    }

    @ConfigurationProperties(prefix = "casehub.expression.invoke")
    public record SpringInvokeProperties(Set<String> allowedPackages) {
        public SpringInvokeProperties {
            if (allowedPackages == null) allowedPackages = Set.of();
        }
    }
}
