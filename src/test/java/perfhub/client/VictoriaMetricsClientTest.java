package perfhub.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import perfhub.service.ConfigurationProvider;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
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
        ConfigurationProvider configurationProvider = new InMemoryConfigurationProvider(Map.of(
                key("base-url"), "http://localhost:8428",
                key("query-range-path"), "/api/v1/query_range",
                key("default-step-seconds"), "60"));
        VictoriaMetricsClient client = new VictoriaMetricsClient(restTemplate, configurationProvider);

        Instant start = Instant.parse("2024-10-01T10:00:00Z");
        Instant end = start.plusSeconds(120);

        server.expect(requestTo("http://localhost:8428/api/v1/query_range?query=metric_name&start=1727776800&end=1727776920&step=60"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("{\"status\":\"ok\"}", MediaType.APPLICATION_JSON));

        String body = client.queryRange("metric_name", start, end);

        assertThat(body).isEqualTo("{\"status\":\"ok\"}");
        server.verify();
    }

    @Test
    void encodesSpecialCharactersInQueryExpression() {
        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.createServer(restTemplate);
        ConfigurationProvider configurationProvider = new InMemoryConfigurationProvider(Map.of(
                key("base-url"), "http://localhost:8428/",
                key("query-range-path"), "api/v1/query_range",
                key("default-step-seconds"), "60"));
        VictoriaMetricsClient client = new VictoriaMetricsClient(restTemplate, configurationProvider);

        Instant start = Instant.parse("2024-10-01T10:00:00Z");
        Instant end = start.plusSeconds(300);
        String query = "sum_over_time(jmeterall.a.count[5m])";
        String expectedEncoded = "sum_over_time%28jmeterall.a.count%5B5m%5D%29";

        server.expect(requestTo("http://localhost:8428/api/v1/query_range?query="
                        + expectedEncoded
                        + "&start=1727776800&end=1727777100&step=60"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("{\"status\":\"ok\"}", MediaType.APPLICATION_JSON));

        client.queryRange(query, start, end);
        server.verify();
    }

    private static String key(String key) {
        return "victoria-metrics:" + key;
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
