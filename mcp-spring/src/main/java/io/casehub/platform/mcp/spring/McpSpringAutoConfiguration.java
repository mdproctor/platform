package io.casehub.platform.mcp.spring;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.casehub.platform.mcp.DomainModelRegistry;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;

@AutoConfiguration
public class McpSpringAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    DomainModelRegistry domainModelRegistry() {
        return new DomainModelRegistry();
    }

    @Bean
    SpringModelScanner springModelScanner(ApplicationContext context,
                                           DomainModelRegistry registry,
                                           ApplicationEventPublisher eventPublisher) {
        var scanner = new SpringModelScanner(context, registry, eventPublisher);
        scanner.scan();
        return scanner;
    }

    @Bean
    SpringOperationDispatcher springOperationDispatcher(DomainModelRegistry registry,
                                                         ApplicationContext context) {
        ObjectMapper mapper = new ObjectMapper();
        mapper.registerModule(new JavaTimeModule());
        return new SpringOperationDispatcher(registry, context, mapper);
    }

    @Bean
    CaseHubToolCallbackProvider caseHubToolCallbackProvider(DomainModelRegistry registry,
                                                             SpringOperationDispatcher dispatcher) {
        return new CaseHubToolCallbackProvider(registry, dispatcher);
    }
}
