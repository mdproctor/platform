package io.casehub.platform.streams.webhook;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.Map;

@ApplicationScoped
public class WebhookService implements WebhookApi {

    private final WebhookReceiver receiver;

    @Inject
    public WebhookService(WebhookReceiver receiver) {
        this.receiver = receiver;
    }

    @Override
    public WebhookResult receive(byte[] body,
                                 String tenancyId,
                                 String streamId,
                                 String authorization) {
        Map<String, String> headers = authorization != null
                ? Map.of("Authorization", authorization)
                : Map.of();
        return receiver.receive(body, tenancyId, streamId, headers);
    }
}
