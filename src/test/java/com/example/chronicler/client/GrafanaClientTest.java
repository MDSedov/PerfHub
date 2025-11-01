package com.example.chronicler.client;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;

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
        grafanaClient = new GrafanaClient(
                restTemplate,
                "http://localhost:3000",
                "/render/d-solo",
                "test-token",
                1000,
                500,
                "UTC");
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
}
