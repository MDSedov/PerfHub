package com.example.chronicler.client;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.chronicler.service.ConfigurationProvider;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.client.match.MockRestRequestMatchers;
import org.springframework.test.web.client.response.MockRestResponseCreators;
import org.springframework.web.client.RestTemplate;

class GrafanaClientTest {

    private RestTemplate restTemplate;
    private MockRestServiceServer mockServer;
    private GrafanaClient grafanaClient;

    @BeforeEach
    void setUp() {
        restTemplate = new RestTemplate();
        mockServer = MockRestServiceServer.createServer(restTemplate);
        ConfigurationProvider configurationProvider = new InMemoryConfigurationProvider(Map.of(
                key("base-url"), "http://localhost:3000",
                key("render-path"), "/render/d-solo",
                key("api-key"), "test-token",
                key("image-width"), "1000",
                key("image-height"), "500",
                key("timezone"), "UTC"));
        grafanaClient = new GrafanaClient(restTemplate, configurationProvider);
    }

    @Test
    void renderPanelShouldSendBearerToken() {
        Instant from = Instant.parse("2024-05-01T00:00:00Z");
        Instant to = Instant.parse("2024-05-01T01:00:00Z");

        mockServer.expect(MockRestRequestMatchers.requestTo(
                        "http://localhost:3000/render/d-solo/dashboard-uid/dashboard-slug"
                                + "?panelId=1&from=1714521600000&to=1714525200000&width=1000&height=500&tz=UTC"))
                .andExpect(MockRestRequestMatchers.method(HttpMethod.GET))
                .andExpect(MockRestRequestMatchers.header(HttpHeaders.AUTHORIZATION, "Bearer test-token"))
                .andRespond(MockRestResponseCreators.withSuccess(new byte[] {1, 2, 3}, MediaType.IMAGE_PNG));

        byte[] result = grafanaClient.renderPanel("dashboard-uid", "dashboard-slug", 1, from, to);

        mockServer.verify();
        assertThat(result).containsExactly(1, 2, 3);
    }

    @Test
    void renderPanelShouldIncludeTemplateVariablesInQuery() {
        Instant from = Instant.parse("2024-05-01T00:00:00Z");
        Instant to = Instant.parse("2024-05-01T01:00:00Z");

        mockServer.expect(MockRestRequestMatchers.requestTo(
                        "http://localhost:3000/render/d-solo/dashboard-uid/dashboard-slug"
                                + "?panelId=7&from=1714521600000&to=1714525200000&width=1000&height=500&tz=UTC"
                                + "&var-service=backend&var-namespace=prod"))
                .andExpect(MockRestRequestMatchers.method(HttpMethod.GET))
                .andExpect(MockRestRequestMatchers.header(HttpHeaders.AUTHORIZATION, "Bearer test-token"))
                .andRespond(MockRestResponseCreators.withSuccess(new byte[] {9}, MediaType.IMAGE_PNG));

        Map<String, String> variables = new LinkedHashMap<>();
        variables.put("service", "backend");
        variables.put("namespace", "prod");

        byte[] result = grafanaClient.renderPanel(
                "dashboard-uid",
                "dashboard-slug",
                7,
                from,
                to,
                variables);

        mockServer.verify();
        assertThat(result).containsExactly(9);
    }

    private static String key(String key) {
        return "grafana:" + key;
    }

    private static final class InMemoryConfigurationProvider implements ConfigurationProvider {

        private final Map<String, String> values;

        private InMemoryConfigurationProvider(Map<String, String> values) {
            this.values = values;
        }

        @Override
        public String getRequiredValue(String service, String key) {
            return findValue(service, key)
                    .orElseThrow(() -> new IllegalStateException("Missing config for " + service + ":" + key));
        }

        @Override
        public Optional<String> findValue(String service, String key) {
            return Optional.ofNullable(values.get(service + ":" + key));
        }
    }
}
