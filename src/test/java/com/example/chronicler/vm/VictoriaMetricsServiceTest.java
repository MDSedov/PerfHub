package com.example.chronicler.vm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.web.client.RestTemplate;

class VictoriaMetricsServiceTest {

    private VictoriaMetricsService service;
    private StubQueryRegistry registry;
    private StubVictoriaMetricsClient client;

    @BeforeEach
    void setUp() throws Exception {
        registry = new StubQueryRegistry();
        registry.register("demo", "metric");
        client = new StubVictoriaMetricsClient();
        service = new VictoriaMetricsService(registry, client);
    }

    @Test
    void throwsWhenTypeUnknown() {
        Instant start = Instant.parse("2024-10-01T10:00:00Z");
        Instant end = start.plusSeconds(60);

        assertThatThrownBy(() -> service.fetchMetric("missing", start, end))
                .isInstanceOf(VmQueryNotFoundException.class);
    }

    @Test
    void throwsWhenEndBeforeStart() {
        Instant start = Instant.parse("2024-10-01T10:00:00Z");
        Instant end = start.minusSeconds(10);

        assertThatThrownBy(() -> service.fetchMetric("demo", start, end))
                .isInstanceOf(InvalidTimeRangeException.class);
    }

    @Test
    void delegatesToClientWhenInputValid() {
        Instant start = Instant.parse("2024-10-01T10:00:00Z");
        Instant end = start.plusSeconds(60);

        service.fetchMetric("demo", start, end);

        assertThat(client.invoked).isTrue();
        assertThat(client.capturedQuery).isEqualTo("metric");
    }

    private static final class StubQueryRegistry extends VmQueryRegistry {

        private final Map<String, String> queries;

        private StubQueryRegistry() throws Exception {
            super(new ByteArrayResource(new byte[0]));
            this.queries = new java.util.HashMap<>();
        }

        void register(String type, String query) {
            queries.put(type, query);
        }

        @Override
        public java.util.Optional<String> findQuery(String type) {
            return java.util.Optional.ofNullable(queries.get(type));
        }

        @Override
        public Map<String, String> getAll() {
            return java.util.Map.copyOf(queries);
        }
    }

    private static final class StubVictoriaMetricsClient extends VictoriaMetricsClient {

        private boolean invoked;
        private String capturedQuery;

        private StubVictoriaMetricsClient() {
            super(new RestTemplate(), "http://localhost", "/api", 60);
        }

        @Override
        String queryRange(String query, Instant start, Instant end) {
            invoked = true;
            capturedQuery = query;
            return "{}";
        }
    }
}
