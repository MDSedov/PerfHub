package com.example.chronicler.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.chronicler.client.VictoriaMetricsClient;
import com.example.chronicler.dto.VictoriaMetricsRequest;
import com.example.chronicler.exception.InvalidTimeRangeException;
import com.example.chronicler.exception.VmQueryNotFoundException;
import com.example.chronicler.model.LoadTest;
import com.example.chronicler.model.LoadTestType;
import com.example.chronicler.repository.GrafanaDashboardPanelRepository;
import com.example.chronicler.repository.LoadTestRepository;
import com.example.chronicler.repository.TestTypeRepository;
import com.example.chronicler.repository.model.GrafanaDashboardPanelRecord;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.client.RestTemplate;

class VictoriaMetricsServiceTest {

    private static final DateTimeFormatter DASHBOARD_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final ZoneId MOSCOW_ZONE = ZoneId.of("Europe/Moscow");

    private StubGrafanaDashboardPanelRepository panelRepository;

    private StubVictoriaMetricsClient client;

    private InMemoryConfigurationProvider configurationProvider;

    private VmDataCache vmDataCache;

    private VictoriaMetricsTestsCache testsCache;

    private StubLoadTestRepository loadTestRepository;

    private ObjectMapper objectMapper;
    private VictoriaMetricsService service;

    @BeforeEach
    void setUp() throws Exception {
        objectMapper = new ObjectMapper();
        panelRepository = new StubGrafanaDashboardPanelRepository();
        client = new StubVictoriaMetricsClient();
        configurationProvider = new InMemoryConfigurationProvider(new HashMap<>());
        vmDataCache = new VmDataCache();
        testsCache = new VictoriaMetricsTestsCache();
        loadTestRepository = new StubLoadTestRepository();
        service = new VictoriaMetricsService(
                panelRepository,
                client,
                vmDataCache,
                testsCache,
                loadTestRepository,
                configurationProvider,
                objectMapper);
    }

    @Test
    void fetchMetricsThrowsWhenRequestsMissing() {
        assertThatThrownBy(() -> service.fetchMetrics(null))
                .isInstanceOf(InvalidTimeRangeException.class)
                .hasMessageContaining("At least one VictoriaMetrics request must be provided");

        assertThatThrownBy(() -> service.fetchMetrics(List.of()))
                .isInstanceOf(InvalidTimeRangeException.class)
                .hasMessageContaining("At least one VictoriaMetrics request must be provided");
    }

    @Test
    void fetchMetricsThrowsWhenQueryNotRegistered() {
        String start = "2024-01-01 10:00:00";
        String end = "2024-01-01 10:05:00";
        VictoriaMetricsRequest request = new VictoriaMetricsRequest("missing", start, end);

        assertThatThrownBy(() -> service.fetchMetrics(List.of(request)))
                .isInstanceOf(VmQueryNotFoundException.class);
        assertThat(client.invocationCount()).isZero();
    }

    @Test
    void fetchMetricsAggregatesResponsesAndWritesToCache() throws Exception {
        String start = "2024-01-02 09:00:00";
        String end = "2024-01-02 09:10:00";
        VictoriaMetricsRequest request = new VictoriaMetricsRequest("10", start, end);

        Instant expectedStart = toInstant(start);
        Instant expectedEnd = toInstant(end);

        String vmResponse = """
                {
                  "status": "success",
                  "data": {
                    "resultType": "matrix",
                    "result": []
                  }
                }
                """;

        panelRepository.register(new GrafanaDashboardPanelRecord("uid", "slug", "title", 10, "Latency", "latency_expr", null, false));
        client.setResponse(vmResponse);

        String aggregated = service.fetchMetrics(List.of(request));

        JsonNode aggregatedJson = objectMapper.readTree(aggregated);
        assertThat(aggregatedJson.path("start").asText()).isEqualTo(expectedStart.toString());
        assertThat(aggregatedJson.path("end").asText()).isEqualTo(expectedEnd.toString());
        assertThat(aggregatedJson.path("results")).hasSize(1);
        JsonNode resultNode = aggregatedJson.path("results").get(0);
        assertThat(resultNode.path("type").asText()).isEqualTo("10");
        assertThat(resultNode.path("data")).isEqualTo(objectMapper.readTree(vmResponse));

        assertThat(client.recordedQuery()).isEqualTo("latency_expr");
        assertThat(client.recordedStart()).isEqualTo(expectedStart);
        assertThat(client.recordedEnd()).isEqualTo(expectedEnd);

        VmDataCache.CachedVmData cached = vmDataCache.getLatest().orElseThrow();
        assertThat(cached.types()).containsExactly("10");
        assertThat(cached.start()).isEqualTo(expectedStart);
        assertThat(cached.end()).isEqualTo(expectedEnd);
        assertThat(cached.payload()).isEqualTo(aggregated);
    }

    @Test
    void detectLoadTestsPersistsIntervalsAndCachesResult() throws Exception {
        String start = "2024-01-05 10:00:00";
        String end = "2024-01-05 10:10:00";
        Instant intervalStart = toInstant(start);
        Instant intervalEnd = toInstant(end);

        configurationProvider.put(key("tests-search-query"), "intensity_expr");
        client.setResponse(intensityPayload(intervalStart, new double[] {0, 5, 5, 5, 5, 5, 5, 0}, 60));

        UUID testId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        loadTestRepository.setIdToReturn(testId);

        String response = service.detectLoadTests(start, end);

        JsonNode root = objectMapper.readTree(response);
        assertThat(root.path("tests")).hasSize(1);
        JsonNode testNode = root.path("tests").get(0);
        assertThat(testNode.path("id").asText()).isEqualTo(testId.toString());
        assertThat(testNode.path("start").asText()).isEqualTo("2024-01-05 10:01:00");
        assertThat(testNode.path("end").asText()).isEqualTo("2024-01-05 10:06:00");
        assertThat(testNode.path("type").asText()).isNotBlank();

        assertThat(loadTestRepository.getLastStart()).isEqualTo(intervalStart.plusSeconds(60));
        assertThat(loadTestRepository.getLastEnd()).isEqualTo(intervalStart.plusSeconds(360));
        assertThat(loadTestRepository.getLastType()).isNotNull();

        VmDataCache.CachedVmData cachedData = vmDataCache.getLatest().orElseThrow();
        assertThat(cachedData.types()).containsExactly("intensity");
        assertThat(cachedData.start()).isEqualTo(intervalStart);
        assertThat(cachedData.end()).isEqualTo(intervalEnd);
        assertThat(objectMapper.readTree(cachedData.payload()).path("results")).hasSize(1);

        assertThat(testsCache.getLatest()).contains(response);
        assertThat(client.recordedQuery()).isEqualTo("intensity_expr");
        assertThat(client.recordedStart()).isEqualTo(intervalStart);
        assertThat(client.recordedEnd()).isEqualTo(intervalEnd);
    }

    @Test
    void detectLoadTestsThrowsWhenSearchQueryMissing() {
        String start = "2024-01-06 12:00:00";
        String end = "2024-01-06 12:10:00";

        assertThatThrownBy(() -> service.detectLoadTests(start, end))
                .isInstanceOf(VmQueryNotFoundException.class);
        assertThat(client.invocationCount()).isZero();
    }

    private Instant toInstant(String value) {
        LocalDateTime localDateTime = LocalDateTime.parse(value, DASHBOARD_FORMAT);
        return localDateTime.atZone(MOSCOW_ZONE).toInstant();
    }

    private String intensityPayload(Instant start, double[] values, long stepSeconds) throws Exception {
        ArrayNode samples = objectMapper.createArrayNode();
        Instant current = start;
        for (double value : values) {
            ArrayNode pair = objectMapper.createArrayNode();
            pair.add(current.getEpochSecond());
            pair.add(String.valueOf(value));
            samples.add(pair);
            current = current.plusSeconds(stepSeconds);
        }

        ObjectNode series = objectMapper.createObjectNode();
        series.set("metric", objectMapper.createObjectNode());
        series.set("values", samples);

        ArrayNode result = objectMapper.createArrayNode();
        result.add(series);

        ObjectNode data = objectMapper.createObjectNode();
        data.put("resultType", "matrix");
        data.set("result", result);

        ObjectNode root = objectMapper.createObjectNode();
        root.put("status", "success");
        root.set("data", data);
        return objectMapper.writeValueAsString(root);
    }

    private static final class StubGrafanaDashboardPanelRepository extends GrafanaDashboardPanelRepository {

        private final Map<String, GrafanaDashboardPanelRecord> panels = new HashMap<>();

        private StubGrafanaDashboardPanelRepository() {
            super(new JdbcTemplate());
        }

        void register(GrafanaDashboardPanelRecord record) {
            panels.put(record.dashboardUid() + ":" + record.panelId(), record);
        }

        @Override
        public Optional<GrafanaDashboardPanelRecord> findFirstByPanelId(int panelId) {
            return panels.values().stream()
                    .filter(record -> record.panelId() == panelId)
                    .findFirst();
        }

        @Override
        public Optional<GrafanaDashboardPanelRecord> findTestQueryPanel() {
            return panels.values().stream()
                    .filter(record -> Boolean.TRUE.equals(record.testQuery()))
                    .findFirst();
        }

        @Override
        public Optional<GrafanaDashboardPanelRecord> findByUidAndPanelId(String dashboardUid, int panelId) {
            return panels.values().stream()
                    .filter(record -> record.dashboardUid().equals(dashboardUid) && record.panelId() == panelId)
                    .findFirst();
        }
    }

    private static final class StubVictoriaMetricsClient extends VictoriaMetricsClient {

        private String response = "{}";
        private String recordedQuery;
        private Instant recordedStart;
        private Instant recordedEnd;
        private int invocationCount;

        private StubVictoriaMetricsClient() {
            super(new RestTemplate(), new InMemoryConfigurationProvider(Map.of(
                    key("base-url"), "http://localhost",
                    key("query-range-path"), "/api",
                    key("default-step-seconds"), "60")));
        }

        void setResponse(String response) {
            this.response = response;
        }

        String recordedQuery() {
            return recordedQuery;
        }

        Instant recordedStart() {
            return recordedStart;
        }

        Instant recordedEnd() {
            return recordedEnd;
        }

        int invocationCount() {
            return invocationCount;
        }

        @Override
        public String queryRange(String query, Instant start, Instant end) {
            invocationCount++;
            recordedQuery = query;
            recordedStart = start;
            recordedEnd = end;
            return response;
        }
    }

    private static String key(String key) {
        return "victoria-metrics:" + key;
    }

    private static final class InMemoryConfigurationProvider implements ConfigurationProvider {

        private final Map<String, String> values;

        private InMemoryConfigurationProvider(Map<String, String> values) {
            this.values = values;
        }

        void put(String key, String value) {
            values.put(key, value);
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

    private static final class StubLoadTestRepository extends LoadTestRepository {

        private UUID idToReturn = UUID.randomUUID();
        private Instant lastStart;
        private Instant lastEnd;
        private LoadTestType lastType;

        private StubLoadTestRepository() {
            super(new JdbcTemplate(), new TestTypeRepository(new JdbcTemplate()));
        }

        void setIdToReturn(UUID id) {
            this.idToReturn = id;
        }

        Instant getLastStart() {
            return lastStart;
        }

        Instant getLastEnd() {
            return lastEnd;
        }

        LoadTestType getLastType() {
            return lastType;
        }

        @Override
        public LoadTest saveOrUpdate(Instant start, Instant end, LoadTestType type) {
            lastStart = start;
            lastEnd = end;
            lastType = type;
            return new LoadTest(idToReturn, start, end, type);
        }

        @Override
        public Optional<LoadTest> findOverlap(Instant start, Instant end) {
            throw new UnsupportedOperationException();
        }

        @Override
        public LoadTest insert(Instant start, Instant end, LoadTestType type) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<LoadTest> findById(UUID id) {
            throw new UnsupportedOperationException();
        }

        @Override
        public LoadTest update(UUID id, Instant start, Instant end, LoadTestType type) {
            throw new UnsupportedOperationException();
        }
    }
}
