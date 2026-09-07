package io.casehub.platform.callback;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import io.casehub.platform.api.callback.CallbackRegistration;
import io.casehub.platform.api.callback.CallbackRegistrationRequest;
import io.casehub.platform.api.callback.CallbackRegistry;
import io.casehub.platform.callback.inmem.InMemoryCallbackRegistry;
import io.casehub.platform.governance.DefaultPolicyEnforcer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

class CallbackEndToEndTest {

    private static WireMockServer wireMock;
    private CallbackRegistry registry;
    private CallbackInvoker invoker;

    @BeforeAll
    static void startWireMock() {
        wireMock = new WireMockServer(WireMockConfiguration.wireMockConfig().dynamicPort());
        wireMock.start();
    }

    @AfterAll
    static void stopWireMock() {
        wireMock.stop();
    }

    @BeforeEach
    void setUp() {
        wireMock.resetAll();
        registry = new InMemoryCallbackRegistry();
        invoker = new CallbackInvoker(new DefaultPolicyEnforcer());
    }

    @Test
    void fullChain_registerCallback_invokeMethod_receivesResponse() {
        wireMock.stubFor(post(urlEqualTo("/callbacks/selectWorker"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("\"worker-42\"")));

        var reg = registry.register(new CallbackRegistrationRequest(
                "worker-selector",
                "http://localhost:" + wireMock.port() + "/callbacks",
                null, "tenant-1", 30000, 300, Map.of()));

        String result = invoker.invoke(reg, "selectWorker",
                new Object[]{"task-123"}, String.class);

        assertThat(result).isEqualTo("worker-42");

        wireMock.verify(postRequestedFor(urlEqualTo("/callbacks/selectWorker"))
                .withHeader("Content-Type", equalTo("application/json"))
                .withHeader("X-CaseHub-Invocation-Id", WireMock.matching(".+")));
    }

    @Test
    void fullChain_voidMethod_invokesWithoutResponse() {
        wireMock.stubFor(post(urlEqualTo("/callbacks/provision"))
                .willReturn(aResponse().withStatus(200)));

        var reg = registry.register(new CallbackRegistrationRequest(
                "provisioner",
                "http://localhost:" + wireMock.port() + "/callbacks",
                null, "tenant-1", 30000, 300, Map.of()));

        Void result = invoker.invoke(reg, "provision",
                new Object[]{"resource-1", 5}, void.class);

        assertThat(result).isNull();

        wireMock.verify(postRequestedFor(urlEqualTo("/callbacks/provision"))
                .withHeader("X-CaseHub-SPI", equalTo("provisioner")));
    }

    @Test
    void fullChain_fanOut_invokesAllRegistrations() {
        wireMock.stubFor(post(urlEqualTo("/app1/provision"))
                .willReturn(aResponse().withStatus(200)));
        wireMock.stubFor(post(urlEqualTo("/app2/provision"))
                .willReturn(aResponse().withStatus(200)));

        var reg1 = registry.register(new CallbackRegistrationRequest(
                "provisioner",
                "http://localhost:" + wireMock.port() + "/app1",
                null, "tenant-1", 30000, 300, Map.of()));
        var reg2 = registry.register(new CallbackRegistrationRequest(
                "provisioner",
                "http://localhost:" + wireMock.port() + "/app2",
                null, "tenant-1", 30000, 300, Map.of()));

        List<CallbackRegistration> registrations =
                registry.findBySpi("provisioner", "tenant-1");
        assertThat(registrations).hasSize(2);

        for (CallbackRegistration reg : registrations) {
            invoker.invoke(reg, "provision",
                    new Object[]{"resource-1"}, void.class);
        }

        wireMock.verify(postRequestedFor(urlEqualTo("/app1/provision")));
        wireMock.verify(postRequestedFor(urlEqualTo("/app2/provision")));
    }

    @Test
    void fullChain_noRegistrations_delegateCanFallThrough() {
        List<CallbackRegistration> registrations =
                registry.findBySpi("provisioner", "tenant-1");

        assertThat(registrations).isEmpty();
    }

    @Test
    void fullChain_requestBodyContainsSerializedArgs() {
        wireMock.stubFor(post(urlEqualTo("/callbacks/process"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("\"processed\"")));

        var reg = registry.register(new CallbackRegistrationRequest(
                "processor",
                "http://localhost:" + wireMock.port() + "/callbacks",
                null, "tenant-1", 30000, 300, Map.of()));

        invoker.invoke(reg, "process",
                new Object[]{"input-data", 42}, String.class);

        wireMock.verify(postRequestedFor(urlEqualTo("/callbacks/process"))
                .withRequestBody(containing("\"input-data\"")));
    }

    @Test
    void fullChain_heartbeatExtendsLease_registrationStillDiscoverable() {
        wireMock.stubFor(post(urlEqualTo("/callbacks/check"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("\"ok\"")));

        var reg = registry.register(new CallbackRegistrationRequest(
                "checker",
                "http://localhost:" + wireMock.port() + "/callbacks",
                null, "tenant-1", 30000, 300, Map.of()));

        registry.heartbeat(reg.id());

        var found = registry.findBySpi("checker", "tenant-1");
        assertThat(found).hasSize(1);

        String result = invoker.invoke(found.get(0), "check",
                new Object[]{}, String.class);
        assertThat(result).isEqualTo("ok");
    }

    @Test
    void fullChain_deregisteredCallback_noLongerDiscoverable() {
        var reg = registry.register(new CallbackRegistrationRequest(
                "temp-spi",
                "http://localhost:" + wireMock.port() + "/callbacks",
                null, "tenant-1", 30000, 300, Map.of()));

        registry.deregister(reg.id());

        var found = registry.findBySpi("temp-spi", "tenant-1");
        assertThat(found).isEmpty();
    }
}
