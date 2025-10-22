package com.example.chronicler.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.time.Instant;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

class VictoriaMetricsClientTest {

    @Test
    void buildsExpectedQueryRangeRequest() {
        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.createServer(restTemplate);
        VictoriaMetricsClient client = new VictoriaMetricsClient(restTemplate, "http://localhost:8428", "/api/v1/query_range", 60);

        Instant start = Instant.parse("2024-10-01T10:00:00Z");
        Instant end = start.plusSeconds(120);

        server.expect(requestTo("http://localhost:8428/api/v1/query_range?query=metric_name&start=1727776800&end=1727776920&step=60"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("{\"status\":\"ok\"}", MediaType.APPLICATION_JSON));

        String body = client.queryRange("metric_name", start, end);

        assertThat(body).isEqualTo("{\"status\":\"ok\"}");
        server.verify();
    }
}
