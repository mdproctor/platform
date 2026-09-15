package io.casehub.platform.spring.rest;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.casehub.platform.api.delivery.DeliveryAttemptStore;
import io.casehub.platform.api.delivery.EngagementCallbackHandler;
import io.casehub.platform.api.expression.ExpressionEngineRegistry;
import io.casehub.platform.api.identity.CurrentPrincipal;
import io.casehub.platform.api.preferences.PreferenceProvider;
import io.casehub.platform.api.preferences.PreferenceSchemaRegistry;
import io.casehub.platform.api.subscription.EventTypeRegistry;
import io.casehub.platform.api.subscription.SubscriptionStore;
import io.casehub.platform.callback.client.CallbackDispatcher;
import io.casehub.platform.notification.dispatch.EngagementCallbackService;
import io.casehub.platform.notification.dispatch.EngagementRecorder;
import io.casehub.platform.preferences.editor.PreferenceSchemaService;
import io.casehub.platform.subscription.EventTypeService;
import io.casehub.platform.subscription.SubscriptionService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

import java.util.Map;
import java.util.stream.Collectors;

@AutoConfiguration
public class RestControllersAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBean(EventTypeRegistry.class)
    public EventTypeService eventTypeService(EventTypeRegistry registry) {
        return new EventTypeService(registry);
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBean(SubscriptionStore.class)
    public SubscriptionService subscriptionService(SubscriptionStore store,
                                                   CurrentPrincipal principal,
                                                   ExpressionEngineRegistry expressionRegistry) {
        return new SubscriptionService(store, principal, expressionRegistry);
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBean(PreferenceSchemaRegistry.class)
    public PreferenceSchemaService preferenceSchemaService(PreferenceSchemaRegistry registry) {
        return new PreferenceSchemaService(registry);
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBean(DeliveryAttemptStore.class)
    public EngagementCallbackService engagementCallbackService(
            DeliveryAttemptStore store,
            EngagementRecorder recorder,
            CurrentPrincipal principal,
            ObjectProvider<EngagementCallbackHandler> handlers,
            PreferenceProvider preferenceProvider) {
        Map<String, EngagementCallbackHandler> handlerMap = handlers.stream()
                .collect(Collectors.toMap(EngagementCallbackHandler::channelId, h -> h));
        return new EngagementCallbackService(store, recorder, principal, handlerMap, preferenceProvider);
    }

    @Bean
    @ConditionalOnMissingBean
    public CallbackDispatcher callbackDispatcher(ObjectMapper objectMapper) {
        return new CallbackDispatcher(objectMapper);
    }
}
