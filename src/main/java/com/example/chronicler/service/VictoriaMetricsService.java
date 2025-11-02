package com.example.chronicler.service;

import com.example.chronicler.client.VictoriaMetricsClient;
import com.example.chronicler.dto.VictoriaMetricsRequest;
import com.example.chronicler.exception.InvalidTimeRangeException;
import com.example.chronicler.exception.VictoriaMetricsResponseProcessingException;
import com.example.chronicler.exception.VmQueryNotFoundException;
import com.example.chronicler.model.LoadTest;
import com.example.chronicler.registry.VmQueryRegistry;
import com.example.chronicler.repository.LoadTestRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Objects;
import java.util.TreeMap;

import org.springframework.stereotype.Service;

@Service
public class VictoriaMetricsService {

    private static final ZoneId MOSCOW_ZONE = ZoneId.of("Europe/Moscow");
    private static final DateTimeFormatter DASHBOARD_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final String INTENSITY_TYPE = "intensity";
    private static final Duration TEST_GAP_THRESHOLD = Duration.ofMinutes(3);
    private static final Duration MIN_TEST_DURATION = Duration.ofMinutes(5);
    private final VmQueryRegistry queryRegistry;
    private final VictoriaMetricsClient client;
    private final VmDataCache vmDataCache;
    private final VictoriaMetricsTestsCache testsCache;
    private final LoadTestRepository loadTestRepository;
    private final ObjectMapper objectMapper;

    /** Конструктор подготавливает зависимости сервиса VictoriaMetrics. */
    VictoriaMetricsService(
            VmQueryRegistry queryRegistry,
            VictoriaMetricsClient client,
            VmDataCache vmDataCache,
            VictoriaMetricsTestsCache testsCache,
            LoadTestRepository loadTestRepository,
            ObjectMapper objectMapper) {
        this.queryRegistry = queryRegistry;
        this.client = client;
        this.vmDataCache = vmDataCache;
        this.testsCache = testsCache;
        this.loadTestRepository = loadTestRepository;
        this.objectMapper = objectMapper;
    }

    /** Метод собирает данные VictoriaMetrics по списку запросов и кеширует результат. */
    public String fetchMetrics(List<VictoriaMetricsRequest> requests) {
        if (requests == null || requests.isEmpty()) {
            throw new InvalidTimeRangeException("At least one VictoriaMetrics request must be provided");
        }

        Instant commonStart = null;
        Instant commonEnd = null;
        List<String> types = new ArrayList<>(requests.size());
        ArrayNode resultsArray = objectMapper.createArrayNode();

        for (VictoriaMetricsRequest request : requests) {
            Objects.requireNonNull(request, "VictoriaMetrics request must not be null");

            Instant start = parseInstant(request.start(), "start");
            Instant end = parseInstant(request.end(), "end");

            if (!end.isAfter(start)) {
                throw new InvalidTimeRangeException("End time must be after start time");
            }

            if (commonStart == null) {
                commonStart = start;
                commonEnd = end;
            } else if (!commonStart.equals(start) || !commonEnd.equals(end)) {
                throw new InvalidTimeRangeException("All VictoriaMetrics requests must have the same start and end");
            }

            String type = request.type();
            String query = queryRegistry
                    .findQuery(type)
                    .orElseThrow(() -> new VmQueryNotFoundException(type));

            String response = client.queryRange(query, start, end);
            types.add(type);
            resultsArray.add(createResultNode(type, response));
        }

        ObjectNode aggregated = objectMapper.createObjectNode();
        aggregated.put("start", Objects.requireNonNull(commonStart).toString());
        aggregated.put("end", Objects.requireNonNull(commonEnd).toString());
        aggregated.set("results", resultsArray);

        String payload = writeAggregated(aggregated);
        vmDataCache.store(types, commonStart, commonEnd, payload);
        return payload;
    }

    /** Метод анализирует интенсивность нагрузки и формирует список нагрузочных тестов. */
    public String detectLoadTests(String start, String end) {
        String payload = fetchMetrics(List.of(new VictoriaMetricsRequest(INTENSITY_TYPE, start, end)));
        JsonNode aggregated = readAggregated(payload);
        JsonNode intensityNode = findIntensityNode(aggregated.path("results"));
        NavigableMap<Instant, Double> samples = extractSamples(intensityNode);
        List<LoadTest> persistedTests = detectTests(samples).stream()
                .map(interval -> loadTestRepository.saveOrUpdate(interval.start(), interval.end()))
                .toList();

        ArrayNode testsNode = objectMapper.createArrayNode();
        for (LoadTest test : persistedTests) {
            ObjectNode testNode = objectMapper.createObjectNode();
            testNode.put("id", test.id().toString());
            testNode.put("start", formatInstant(test.start()));
            testNode.put("end", formatInstant(test.end()));
            testsNode.add(testNode);
        }

        ObjectNode responseNode = objectMapper.createObjectNode();
        responseNode.set("tests", testsNode);

        String response = writeAggregated(responseNode);
        testsCache.store(response);
        return response;
    }

    /** Метод преобразует время из формата Grafana (МСК) в Instant. */
    private Instant parseInstant(String value, String field) {
        try {
            LocalDateTime localDateTime = LocalDateTime.parse(value, DASHBOARD_FORMAT);
            return localDateTime.atZone(MOSCOW_ZONE).toInstant();
        } catch (DateTimeParseException ex) {
            throw new InvalidTimeRangeException(
                    "Unable to parse " + field + " as 'yyyy-MM-dd HH:mm:ss' in Europe/Moscow timezone");
        }
    }

    /** Метод формирует узел результата с типом и данными. */
    private ObjectNode createResultNode(String type, String response) {
        ObjectNode resultNode = objectMapper.createObjectNode();
        resultNode.put("type", type);
        resultNode.set("data", readResponse(response));
        return resultNode;
    }

    /** Метод преобразует строковый ответ VictoriaMetrics в JSON. */
    private JsonNode readResponse(String response) {
        if (response == null || response.isBlank()) {
            return objectMapper.nullNode();
        }
        try {
            return objectMapper.readTree(response);
        } catch (JsonProcessingException ex) {
            throw new VictoriaMetricsResponseProcessingException(
                    "Failed to parse VictoriaMetrics response as JSON", ex);
        }
    }

    /** Метод сериализует агрегированные данные в строку JSON. */
    private String writeAggregated(ObjectNode aggregated) {
        try {
            return objectMapper.writeValueAsString(aggregated);
        } catch (JsonProcessingException ex) {
            throw new VictoriaMetricsResponseProcessingException(
                    "Failed to serialize aggregated VictoriaMetrics response", ex);
        }
    }

    private String formatInstant(Instant instant) {
        return DASHBOARD_FORMAT.format(instant.atZone(MOSCOW_ZONE));
    }

    private JsonNode readAggregated(String payload) {
        if (payload == null || payload.isBlank()) {
            return objectMapper.createObjectNode();
        }
        try {
            return objectMapper.readTree(payload);
        } catch (JsonProcessingException ex) {
            throw new VictoriaMetricsResponseProcessingException(
                    "Failed to parse aggregated VictoriaMetrics payload", ex);
        }
    }

    private JsonNode findIntensityNode(JsonNode resultsNode) {
        if (!resultsNode.isArray()) {
            return objectMapper.createObjectNode();
        }
        for (JsonNode node : resultsNode) {
            if (INTENSITY_TYPE.equals(node.path("type").asText())) {
                return node;
            }
        }
        return objectMapper.createObjectNode();
    }

    private NavigableMap<Instant, Double> extractSamples(JsonNode intensityNode) {
        NavigableMap<Instant, Double> samples = new TreeMap<>();
        JsonNode resultNode = intensityNode.path("data").path("data").path("result");
        if (!resultNode.isArray()) {
            return samples;
        }
        for (JsonNode series : resultNode) {
            JsonNode values = series.path("values");
            if (!values.isArray()) {
                continue;
            }
            for (JsonNode valueNode : values) {
                if (!valueNode.isArray() || valueNode.size() < 2) {
                    continue;
                }
                Instant timestamp = parseEpoch(valueNode.get(0));
                if (timestamp == null) {
                    continue;
                }
                double value = parseDouble(valueNode.get(1));
                samples.merge(timestamp, value, Math::max);
            }
        }
        return samples;
    }

    private Instant parseEpoch(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        try {
            if (node.isNumber()) {
                return Instant.ofEpochSecond(node.asLong());
            }
            double epochSeconds = Double.parseDouble(node.asText());
            return Instant.ofEpochSecond((long) epochSeconds);
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private double parseDouble(JsonNode node) {
        if (node == null || node.isNull()) {
            return 0.0d;
        }
        if (node.isNumber()) {
            return node.asDouble();
        }
        try {
            return Double.parseDouble(node.asText());
        } catch (NumberFormatException ex) {
            return 0.0d;
        }
    }

    private List<TestInterval> detectTests(NavigableMap<Instant, Double> samples) {
        List<TestInterval> tests = new ArrayList<>();
        if (samples.isEmpty()) {
            return tests;
        }

        Instant currentStart = null;
        Instant lastActive = null;

        for (Map.Entry<Instant, Double> entry : samples.entrySet()) {
            Instant timestamp = entry.getKey();
            double value = entry.getValue();
            if (value > 0.0d) {
                if (currentStart == null) {
                    currentStart = timestamp;
                } else if (lastActive != null
                        && Duration.between(lastActive, timestamp).compareTo(TEST_GAP_THRESHOLD) > 0) {
                    addTestIfLongEnough(tests, currentStart, lastActive);
                    currentStart = timestamp;
                }
                lastActive = timestamp;
            } else if (currentStart != null && lastActive != null) {
                addTestIfLongEnough(tests, currentStart, lastActive);
                currentStart = null;
                lastActive = null;
            }
        }

        if (currentStart != null && lastActive != null) {
            addTestIfLongEnough(tests, currentStart, lastActive);
        }

        return tests;
    }

    private void addTestIfLongEnough(List<TestInterval> tests, Instant start, Instant end) {
        if (Duration.between(start, end).compareTo(MIN_TEST_DURATION) >= 0) {
            tests.add(new TestInterval(start, end));
        }
    }

    private record TestInterval(Instant start, Instant end) {}
}
