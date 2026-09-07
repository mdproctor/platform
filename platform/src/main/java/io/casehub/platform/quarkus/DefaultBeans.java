package io.casehub.platform.quarkus;

import io.casehub.platform.acl.AutoApproveWorkerAuthorizationPolicy;
import io.casehub.platform.acl.NoOpAccessControlProvider;
import io.casehub.platform.acl.NoOpWorkerCredentialStore;
import io.casehub.platform.datasource.NoOpDataSourceRegistry;
import io.casehub.platform.datasource.NoOpMarshallerRegistry;
import io.casehub.platform.delivery.NoOpDeliveryAttemptStore;
import io.casehub.platform.delivery.NoOpDeliveryChannelRegistry;
import io.casehub.platform.delivery.NoOpDigestBuffer;
import io.casehub.platform.endpoints.NoOpEndpointRegistry;
import io.casehub.platform.expression.NoOpExpressionEngineRegistry;
import io.casehub.platform.identity.NoOpActorDIDProvider;
import io.casehub.platform.identity.NoOpCredentialValidator;
import io.casehub.platform.identity.NoOpDIDResolver;
import io.casehub.platform.mock.MockCurrentPrincipal;
import io.casehub.platform.mock.MockGroupMembershipProvider;
import io.casehub.platform.mock.MockPreferenceProvider;
import io.casehub.platform.mock.NoOpMcpResourceRegistry;
import io.casehub.platform.mock.NoOpPreferenceSchemaRegistry;
import io.casehub.platform.mock.NoOpPreferenceStore;
import io.casehub.platform.mock.NoOpSessionIsolator;
import io.casehub.platform.notification.NoOpNotificationStore;
import io.casehub.platform.notification.settings.NoOpNotificationPreferenceStore;
import io.casehub.platform.notification.settings.NoOpSuppressionStore;
import io.casehub.platform.pdf.NoOpPdfGenerator;
import io.casehub.platform.signing.NoOpSigningProvider;
import io.casehub.platform.signing.document.NoOpDocumentSigningService;
import io.casehub.platform.signing.document.NoOpDocumentVerificationService;
import io.casehub.platform.subscription.NoOpEntityWatcherProvider;
import io.casehub.platform.subscription.NoOpEventTypeRegistry;
import io.casehub.platform.subscription.NoOpSubscriptionStore;
import io.casehub.platform.view.NoOpCrossTenantSubjectViewStore;
import io.casehub.platform.view.NoOpSubjectViewStore;
import io.casehub.platform.view.NoOpViewMembershipTracker;
import io.quarkus.arc.DefaultBean;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.util.List;
import java.util.Map;
import java.util.Optional;

@ApplicationScoped
public class DefaultBeans {

    // --- Identity ---

    @Produces @DefaultBean @ApplicationScoped
    public MockCurrentPrincipal mockCurrentPrincipal(
            @ConfigProperty(name = "casehub.platform.principal.actorId", defaultValue = "system") String actorId,
            @ConfigProperty(name = "casehub.platform.principal.groups") Optional<List<String>> groups,
            @ConfigProperty(name = "casehub.tenancy.default-id", defaultValue = "278776f9-e1b0-46fb-9032-8bddebdcf9ce") String tenancyId,
            @ConfigProperty(name = "casehub.platform.principal.crossTenantAdmin", defaultValue = "false") boolean crossTenantAdmin) {
        return new MockCurrentPrincipal(actorId, groups.orElse(List.of()), tenancyId, crossTenantAdmin);
    }

    @Produces @DefaultBean @ApplicationScoped
    public MockGroupMembershipProvider mockGroupMembershipProvider() {
        return new MockGroupMembershipProvider();
    }

    @Produces @DefaultBean @ApplicationScoped
    public NoOpDIDResolver noOpDIDResolver() { return new NoOpDIDResolver(); }

    @Produces @DefaultBean @ApplicationScoped
    public NoOpActorDIDProvider noOpActorDIDProvider() { return new NoOpActorDIDProvider(); }

    @Produces
    @DefaultBean
    @ApplicationScoped
    public NoOpCredentialValidator noOpCredentialValidator() {return new NoOpCredentialValidator();}


    // --- Preferences ---

    @Produces @DefaultBean @ApplicationScoped
    public MockPreferenceProvider mockPreferenceProvider(
            @ConfigProperty(name = "casehub.platform.preferences.defaults") Optional<Map<String, String>> defaults) {
        return new MockPreferenceProvider(defaults.orElse(Map.of()));
    }

    @Produces @DefaultBean @ApplicationScoped
    public NoOpPreferenceStore noOpPreferenceStore() { return new NoOpPreferenceStore(); }

    @Produces @DefaultBean @ApplicationScoped
    public NoOpPreferenceSchemaRegistry noOpPreferenceSchemaRegistry() { return new NoOpPreferenceSchemaRegistry(); }

    // --- ACL ---

    @Produces @DefaultBean @ApplicationScoped
    public NoOpAccessControlProvider noOpAccessControlProvider() { return new NoOpAccessControlProvider(); }

    @Produces @DefaultBean @ApplicationScoped
    public NoOpWorkerCredentialStore noOpWorkerCredentialStore() { return new NoOpWorkerCredentialStore(); }

    @Produces @DefaultBean @ApplicationScoped
    public AutoApproveWorkerAuthorizationPolicy autoApproveWorkerAuthorizationPolicy() { return new AutoApproveWorkerAuthorizationPolicy(); }

    // --- Notifications ---

    @Produces @DefaultBean @ApplicationScoped
    public NoOpNotificationStore noOpNotificationStore() { return new NoOpNotificationStore(); }

    @Produces @DefaultBean @ApplicationScoped
    public NoOpNotificationPreferenceStore noOpNotificationPreferenceStore() { return new NoOpNotificationPreferenceStore(); }

    @Produces @DefaultBean @ApplicationScoped
    public NoOpSuppressionStore noOpSuppressionStore() { return new NoOpSuppressionStore(); }

    // --- Subscriptions ---

    @Produces @DefaultBean @ApplicationScoped
    public NoOpSubscriptionStore noOpSubscriptionStore() { return new NoOpSubscriptionStore(); }

    @Produces @DefaultBean @ApplicationScoped
    public NoOpEventTypeRegistry noOpEventTypeRegistry() { return new NoOpEventTypeRegistry(); }

    @Produces @DefaultBean @ApplicationScoped
    public NoOpEntityWatcherProvider noOpEntityWatcherProvider() { return new NoOpEntityWatcherProvider(); }

    // --- DataSource ---

    @Produces @DefaultBean @ApplicationScoped
    public NoOpDataSourceRegistry noOpDataSourceRegistry() { return new NoOpDataSourceRegistry(); }

    @Produces @DefaultBean @ApplicationScoped
    public NoOpMarshallerRegistry noOpMarshallerRegistry() { return new NoOpMarshallerRegistry(); }

    // --- Endpoints ---

    @Produces @DefaultBean @ApplicationScoped
    public NoOpEndpointRegistry noOpEndpointRegistry() { return new NoOpEndpointRegistry(); }

    // --- Delivery ---

    @Produces @DefaultBean @ApplicationScoped
    public NoOpDeliveryChannelRegistry noOpDeliveryChannelRegistry() { return new NoOpDeliveryChannelRegistry(); }

    @Produces @DefaultBean @ApplicationScoped
    public NoOpDeliveryAttemptStore noOpDeliveryAttemptStore() { return new NoOpDeliveryAttemptStore(); }

    @Produces @DefaultBean @ApplicationScoped
    public NoOpDigestBuffer noOpDigestBuffer() { return new NoOpDigestBuffer(); }

    // --- View ---

    @Produces @DefaultBean @ApplicationScoped
    public NoOpSubjectViewStore noOpSubjectViewStore() { return new NoOpSubjectViewStore(); }

    @Produces @DefaultBean @ApplicationScoped
    public NoOpViewMembershipTracker noOpViewMembershipTracker() { return new NoOpViewMembershipTracker(); }

    @Produces @DefaultBean @ApplicationScoped
    public NoOpCrossTenantSubjectViewStore noOpCrossTenantSubjectViewStore() { return new NoOpCrossTenantSubjectViewStore(); }

    // --- Expression ---

    @Produces @DefaultBean @ApplicationScoped
    public NoOpExpressionEngineRegistry noOpExpressionEngineRegistry() { return new NoOpExpressionEngineRegistry(); }

    // --- Signing ---

    @Produces @DefaultBean @ApplicationScoped
    public NoOpSigningProvider noOpSigningProvider() { return new NoOpSigningProvider(); }

    @Produces @DefaultBean @ApplicationScoped
    public NoOpDocumentSigningService noOpDocumentSigningService() { return new NoOpDocumentSigningService(); }

    @Produces @DefaultBean @ApplicationScoped
    public NoOpDocumentVerificationService noOpDocumentVerificationService() { return new NoOpDocumentVerificationService(); }

    // --- PDF ---

    @Produces @DefaultBean @ApplicationScoped
    public NoOpPdfGenerator noOpPdfGenerator() { return new NoOpPdfGenerator(); }

    // --- MCP ---

    @Produces @DefaultBean @ApplicationScoped
    public NoOpMcpResourceRegistry noOpMcpResourceRegistry() { return new NoOpMcpResourceRegistry(); }

    // --- Governance ---

    @Produces @DefaultBean @ApplicationScoped
    public NoOpSessionIsolator noOpSessionIsolator() { return new NoOpSessionIsolator(); }
}
