package io.casehub.platform.callback;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.casehub.platform.api.callback.CallbackRegistration;
import io.casehub.platform.api.governance.BackoffStrategy;
import io.casehub.platform.api.governance.ExecutionPolicy;
import io.casehub.platform.api.governance.RetryPolicy;
import io.casehub.platform.api.util.UUIDv7;
import io.casehub.platform.governance.PolicyEnforcer;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

public class CallbackInvoker implements AutoCloseable {

    private final ObjectMapper mapper;
    private final HttpClient httpClient;
    private final PolicyEnforcer policyEnforcer;

    public CallbackInvoker(PolicyEnforcer policyEnforcer) {
        this.policyEnforcer = policyEnforcer;
        this.mapper = new ObjectMapper();
        this.mapper.configure(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        this.mapper.registerModule(new JavaTimeModule());
        var callbackModule = new com.fasterxml.jackson.databind.module.SimpleModule("callback-types");
        callbackModule.addAbstractTypeMapping(
                io.casehub.platform.api.preferences.Preferences.class,
                io.casehub.platform.api.preferences.MapPreferences.class);
        this.mapper.registerModule(callbackModule);
        this.httpClient = HttpClient.newHttpClient();
    }

    @Override
    public void close() {
        httpClient.close();
    }

    public <T> T invoke(CallbackRegistration registration,
                        String methodName,
                        Object[] args,
                        Class<T> returnType) {
        String url = registration.callbackUrl() + "/" + methodName;
        String invocationId = UUIDv7.generate();

        var retryPolicy = new RetryPolicy(3, 1000,
                BackoffStrategy.EXPONENTIAL_WITH_JITTER, 10000);
        var executionPolicy = new ExecutionPolicy(
                registration.timeoutMs(), retryPolicy);

        return policyEnforcer.execute(executionPolicy, () -> {
            try {
                String body = mapper.writeValueAsString(args);

                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(url))
                        .header("Content-Type", "application/json")
                        .header("X-CaseHub-Invocation-Id", invocationId)
                        .header("X-CaseHub-SPI", registration.spiName())
                        .POST(HttpRequest.BodyPublishers.ofString(body))
                        .build();

                HttpResponse<String> response = httpClient.send(
                        request, HttpResponse.BodyHandlers.ofString());

                if (response.statusCode() >= 400) {
                    throw new CallbackInvocationException(
                            "Callback failed: HTTP " + response.statusCode()
                                    + " from " + url);
                }

                if (returnType == void.class || returnType == Void.class) {
                    return null;
                }

                return mapper.readValue(response.body(), returnType);
            } catch (JsonProcessingException e) {
                throw new CallbackInvocationException(
                        "Failed to serialize callback args for " + url, e);
            } catch (IOException | InterruptedException e) {
                if (e instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                }
                throw new CallbackInvocationException(
                        "Callback invocation failed for " + url, e);
            }
        });
    }
}
