package com.example.chronicler.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.chronicler.client.VictoriaMetricsClient;
import com.example.chronicler.dto.RequirementEvaluationView;
import com.example.chronicler.exception.RequirementEvaluationException;
import com.example.chronicler.model.LoadTest;
import com.example.chronicler.model.LoadTestType;
import com.example.chronicler.model.NonFunctionalRequirement;
import com.example.chronicler.model.RequirementEvaluationStatus;
import com.example.chronicler.model.RequirementMetricType;
import com.example.chronicler.model.TestRequirementEvaluation;
import com.example.chronicler.registry.VmQueryRegistry;
import com.example.chronicler.repository.LoadTestRepository;
import com.example.chronicler.repository.NonFunctionalRequirementRepository;
import com.example.chronicler.repository.TestRequirementEvaluationRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.client.RestTemplate;

class RequirementEvaluationServiceTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private StubNonFunctionalRequirementRepository requirementRepository;
    private StubTestRequirementEvaluationRepository evaluationRepository;
    private StubLoadTestRepository loadTestRepository;
    private StubVmQueryRegistry vmQueryRegistry;
    private StubVictoriaMetricsClient victoriaMetricsClient;

    private RequirementEvaluationService service;

    @BeforeEach
    void setUp() throws Exception {
        requirementRepository = new StubNonFunctionalRequirementRepository();
        evaluationRepository = new StubTestRequirementEvaluationRepository();
        loadTestRepository = new StubLoadTestRepository();
        vmQueryRegistry = new StubVmQueryRegistry();
        victoriaMetricsClient = new StubVictoriaMetricsClient();
        service = new RequirementEvaluationService(
                requirementRepository,
                evaluationRepository,
                loadTestRepository,
                vmQueryRegistry,
                victoriaMetricsClient,
                OBJECT_MAPPER);
    }

    @Test
    void evaluateReturnsCalculatedViews() throws Exception {
        UUID testUuid = UUID.randomUUID();
        Instant start = Instant.parse("2024-01-01T00:00:00Z");
        Instant end = Instant.parse("2024-01-01T01:00:00Z");

        loadTestRepository.put(new LoadTest(testUuid, start, end, LoadTestType.UNKNOWN));

        List<NonFunctionalRequirement> requirements = List.of(
                requirement(1, "LATENCY_P90", RequirementMetricType.LATENCY_P90, 1.0, "seconds", "{\"query\":\"latency\"}"),
                requirement(
                        2,
                        "ERROR_RATE",
                        RequirementMetricType.ERROR_RATE,
                        1.0,
                        "percent",
                        "{\"totalQuery\":\"total\",\"errorQuery\":\"error\"}"),
                requirement(
                        3,
                        "CPU",
                        RequirementMetricType.CPU_UTILIZATION,
                        80.0,
                        "percent",
                        "{\"query\":\"cpu\",\"groupLabel\":\"container\"}"),
                requirement(
                        4,
                        "MEMORY",
                        RequirementMetricType.MEMORY_UTILIZATION,
                        80.0,
                        "percent",
                        "{\"query\":\"memory\",\"groupLabel\":\"container\"}"),
                requirement(
                        5,
                        "RESTARTS",
                        RequirementMetricType.POD_RESTARTS,
                        0.0,
                        "count",
                        "{\"query\":\"restarts\",\"groupLabel\":\"pod\"}"));

        requirementRepository.setRequirements(requirements);
        vmQueryRegistry.register("latency", "latency_expr");
        vmQueryRegistry.register("total", "total_expr");
        vmQueryRegistry.register("error", "error_expr");
        vmQueryRegistry.register("cpu", "cpu_expr");
        vmQueryRegistry.register("memory", "memory_expr");
        vmQueryRegistry.register("restarts", "restarts_expr");

        victoriaMetricsClient.registerResponse("latency_expr", start, end, matrixResponse(Map.of("", new double[] {0.4, 0.8})));
        victoriaMetricsClient.registerResponse("total_expr", start, end, matrixResponse(Map.of("", new double[] {100.0, 200.0})));
        victoriaMetricsClient.registerResponse("error_expr", start, end, matrixResponse(Map.of("", new double[] {0.0, 1.0})));
        victoriaMetricsClient.registerResponse("cpu_expr", start, end, matrixResponse(Map.of(
                "c1", new double[] {65.0, 70.0},
                "c2", new double[] {40.0, 60.0})));
        victoriaMetricsClient.registerResponse("memory_expr", start, end, matrixResponse(Map.of(
                "c1", new double[] {55.0, 60.0},
                "c2", new double[] {30.0, 35.0})));
        victoriaMetricsClient.registerResponse("restarts_expr", start, end, matrixResponse(Map.of(
                "pod-a", new double[] {0.0, 0.0},
                "pod-b", new double[] {0.0, 0.0})));

        List<RequirementEvaluationView> views = service.evaluate(testUuid);

        assertThat(views).hasSize(5);
        assertThat(views.get(0).requirementId()).isEqualTo(1);
        assertThat(views.get(0).status()).isEqualTo(RequirementEvaluationStatus.PASS);
        assertThat(views.get(0).measuredValue()).isEqualTo(0.8);

        assertThat(views.get(1).status()).isEqualTo(RequirementEvaluationStatus.PASS);
        assertThat(views.get(1).measuredValue()).isEqualTo(0.5);

        assertThat(views.get(2).status()).isEqualTo(RequirementEvaluationStatus.PASS);
        assertThat(views.get(2).measuredValue()).isEqualTo(70.0);

        assertThat(views.get(3).status()).isEqualTo(RequirementEvaluationStatus.PASS);
        assertThat(views.get(3).measuredValue()).isEqualTo(60.0);

        assertThat(views.get(4).status()).isEqualTo(RequirementEvaluationStatus.PASS);
        assertThat(views.get(4).measuredValue()).isEqualTo(0.0);

        assertThat(evaluationRepository.getUpserts()).hasSize(5);
    }

    @Test
    void evaluateFailsWhenNoRequirementsConfigured() {
        UUID testUuid = UUID.randomUUID();
        Instant start = Instant.parse("2024-01-01T00:00:00Z");
        Instant end = Instant.parse("2024-01-01T01:00:00Z");
        loadTestRepository.put(new LoadTest(testUuid, start, end, LoadTestType.UNKNOWN));
        requirementRepository.setRequirements(List.of());

        assertThatThrownBy(() -> service.evaluate(testUuid))
                .isInstanceOf(RequirementEvaluationException.class)
                .hasMessage("No active non-functional requirements configured");
    }

    private NonFunctionalRequirement requirement(
            int id,
            String code,
            RequirementMetricType metricType,
            double threshold,
            String unit,
            String parametersJson)
            throws Exception {
        JsonNode parameters = OBJECT_MAPPER.readTree(parametersJson);
        return new NonFunctionalRequirement(id, code, metricType, threshold, unit, true, parameters, null, null);
    }

    private String matrixResponse(Map<String, double[]> series) throws Exception {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("status", "success");
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("resultType", "matrix");
        data.put("result", series.entrySet().stream()
                .map(entry -> {
                    Map<String, Object> metric = new LinkedHashMap<>();
                    if (!entry.getKey().isEmpty()) {
                        metric.put("container", entry.getKey());
                        metric.put("pod", entry.getKey());
                    }
                    List<List<Object>> values = List.of(
                            List.of(1, String.valueOf(entry.getValue()[0])),
                            List.of(2, String.valueOf(entry.getValue()[1])));
                    Map<String, Object> seriesMap = new LinkedHashMap<>();
                    seriesMap.put("metric", metric);
                    seriesMap.put("values", values);
                    return seriesMap;
                })
                .toList());
        root.put("data", data);
        return OBJECT_MAPPER.writeValueAsString(root);
    }

    private static final class StubNonFunctionalRequirementRepository extends NonFunctionalRequirementRepository {

        private List<NonFunctionalRequirement> requirements = List.of();

        private StubNonFunctionalRequirementRepository() throws Exception {
            super(new JdbcTemplate(), new ObjectMapper());
        }

        void setRequirements(List<NonFunctionalRequirement> requirements) {
            this.requirements = requirements;
        }

        @Override
        public List<NonFunctionalRequirement> findAllActive() {
            return requirements;
        }
    }

    private static final class StubTestRequirementEvaluationRepository extends TestRequirementEvaluationRepository {

        private final List<UpsertRecord> upserts = new ArrayList<>();

        private StubTestRequirementEvaluationRepository() {
            super(new JdbcTemplate(), new ObjectMapper());
        }

        List<UpsertRecord> getUpserts() {
            return upserts;
        }

        @Override
        public void upsert(
                UUID testUuid,
                int requirementId,
                RequirementEvaluationStatus status,
                Double measuredValue,
                String unit,
                JsonNode details,
                Instant evaluatedAt) {
            upserts.add(new UpsertRecord(testUuid, requirementId, status, measuredValue, unit, details, evaluatedAt));
        }

        @Override
        public List<TestRequirementEvaluation> findByTest(UUID testUuid) {
            throw new UnsupportedOperationException();
        }

        private record UpsertRecord(
                UUID testUuid,
                int requirementId,
                RequirementEvaluationStatus status,
                Double measuredValue,
                String unit,
                JsonNode details,
                Instant evaluatedAt) {}
    }

    private static final class StubLoadTestRepository extends LoadTestRepository {

        private final Map<UUID, LoadTest> storage = new HashMap<>();

        private StubLoadTestRepository() {
            super(new JdbcTemplate());
        }

        void put(LoadTest loadTest) {
            storage.put(loadTest.id(), loadTest);
        }

        @Override
        public Optional<LoadTest> findById(UUID id) {
            return Optional.ofNullable(storage.get(id));
        }

        @Override
        public LoadTest saveOrUpdate(Instant start, Instant end, LoadTestType type) {
            throw new UnsupportedOperationException();
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
        public LoadTest update(UUID id, Instant start, Instant end, LoadTestType type) {
            throw new UnsupportedOperationException();
        }
    }

    private static final class StubVmQueryRegistry extends VmQueryRegistry {

        private final Map<String, String> queries = new HashMap<>();

        private StubVmQueryRegistry() {
            super(Map.of());
        }

        void register(String type, String query) {
            queries.put(type, query);
        }

        @Override
        public Optional<String> findQuery(String type) {
            return Optional.ofNullable(queries.get(type));
        }

        @Override
        public Map<String, String> getAll() {
            return Map.copyOf(queries);
        }
    }

    private static final class StubVictoriaMetricsClient extends VictoriaMetricsClient {

        private final Map<QueryKey, String> responses = new HashMap<>();

        private StubVictoriaMetricsClient() {
            super(new RestTemplate(), new InMemoryConfigurationProvider(Map.of(
                    key("base-url"), "http://localhost",
                    key("query-range-path"), "/api",
                    key("default-step-seconds"), "60")));
        }

        void registerResponse(String query, Instant start, Instant end, String response) {
            responses.put(new QueryKey(query, start, end), response);
        }

        @Override
        public String queryRange(String query, Instant start, Instant end) {
            return responses.getOrDefault(new QueryKey(query, start, end), "{}");
        }

        private record QueryKey(String query, Instant start, Instant end) {}
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
