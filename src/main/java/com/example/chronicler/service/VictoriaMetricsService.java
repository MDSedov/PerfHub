package com.example.chronicler.service;

import com.example.chronicler.client.VictoriaMetricsClient;
import com.example.chronicler.dto.VictoriaMetricsRequest;
import com.example.chronicler.exception.InvalidTimeRangeException;
import com.example.chronicler.exception.VictoriaMetricsResponseProcessingException;
import com.example.chronicler.exception.VmQueryNotFoundException;
import com.example.chronicler.model.LoadTest;
import com.example.chronicler.model.LoadTestType;
import com.example.chronicler.repository.GrafanaDashboardPanelRepository;
import com.example.chronicler.repository.LoadTestRepository;
import com.example.chronicler.repository.model.GrafanaDashboardPanelRecord;
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
import java.util.Optional;
import java.util.TreeMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class VictoriaMetricsService {

    private static final Logger log = LoggerFactory.getLogger(VictoriaMetricsService.class);
    private static final ZoneId MOSCOW_ZONE = ZoneId.of("Europe/Moscow");
    private static final DateTimeFormatter DASHBOARD_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final String INTENSITY_TYPE = "intensity";
    private static final Duration TEST_GAP_THRESHOLD = Duration.ofMinutes(3);
    private static final Duration MIN_TEST_DURATION = Duration.ofMinutes(5);
    private static final Duration SOAK_THRESHOLD = Duration.ofHours(3);
    private static final int MIN_SAMPLES_FOR_ANALYSIS = 6;
    private static final int MIN_PLATEAU_POINTS = 6;
    private static final double PLATEAU_MIN_FRACTION = 0.25d;
    private static final Duration PLATEAU_MIN_DURATION = Duration.ofMinutes(15);
    private static final double PLATEAU_VARIATION_THRESHOLD = 0.2d;
    private static final double PLATEAU_LEVEL_RATIO = 0.75d;
    private static final double SECONDARY_PLATEAU_RATIO = 0.6d;
    private static final double PLATEAU_MIN_COVERAGE = 0.4d;
    private static final double PLATEAU_DURATION_RATIO = 0.4d;
    private static final double RAMP_UP_RATIO = 1.2d;
    private static final double GROWTH_RATIO_FOR_MAX = 1.4d;
    private static final double FINAL_DROP_RATIO = 0.35d;
    private static final double QUARTILE_FRACTION = 0.25d;
    private static final double EPS = 1e-6d;
    private final GrafanaDashboardPanelRepository grafanaPanelRepository;
    private final VictoriaMetricsClient client;
    private final VmDataCache vmDataCache;
    private final VictoriaMetricsTestsCache testsCache;
    private final LoadTestRepository loadTestRepository;
    private final ObjectMapper objectMapper;

    /** Конструктор подготавливает зависимости сервиса VictoriaMetrics. */
    VictoriaMetricsService(
            GrafanaDashboardPanelRepository grafanaPanelRepository,
            VictoriaMetricsClient client,
            VmDataCache vmDataCache,
            VictoriaMetricsTestsCache testsCache,
            LoadTestRepository loadTestRepository,
            ObjectMapper objectMapper) {
        this.grafanaPanelRepository = grafanaPanelRepository;
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
            String query = resolveQuery(type);

            log.info("VictoriaMetrics query resolved for type '{}': {}", type, query);
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
        Instant startInstant = parseInstant(start, "start");
        Instant endInstant = parseInstant(end, "end");
        List<LoadTest> persistedTests = detectAndPersistTests(startInstant, endInstant, true);

        ArrayNode testsNode = objectMapper.createArrayNode();
        for (LoadTest test : persistedTests) {
            ObjectNode testNode = objectMapper.createObjectNode();
            testNode.put("id", test.id().toString());
            testNode.put("start", formatInstant(test.start()));
            testNode.put("end", formatInstant(test.end()));
            testNode.put("type", test.type().value());
            testsNode.add(testNode);
        }

        ObjectNode responseNode = objectMapper.createObjectNode();
        responseNode.set("tests", testsNode);

        String response = writeAggregated(responseNode);
        testsCache.store(response);
        return response;
    }

    /** Метод анализирует интенсивность нагрузки и сохраняет найденные тесты без формирования ответа. */
    public List<LoadTest> detectAndPersistTests(Instant start, Instant end) {
        return detectAndPersistTests(start, end, false);
    }

    private List<LoadTest> detectAndPersistTests(Instant start, Instant end, boolean cacheResponse) {
        Objects.requireNonNull(start, "start must not be null");
        Objects.requireNonNull(end, "end must not be null");
        if (!end.isAfter(start)) {
            throw new InvalidTimeRangeException("End time must be after start time");
        }
        NavigableMap<Instant, Double> samples = loadIntensitySamples(start, end, cacheResponse);
        return persistDetectedTests(samples);
    }

    private NavigableMap<Instant, Double> loadIntensitySamples(
            Instant start, Instant end, boolean cacheResponse) {
        if (cacheResponse) {
            String startStr = formatInstant(start);
            String endStr = formatInstant(end);
            String payload = fetchMetrics(List.of(new VictoriaMetricsRequest(INTENSITY_TYPE, startStr, endStr)));
            JsonNode aggregated = readAggregated(payload);
            JsonNode intensityNode = findIntensityNode(aggregated.path("results"));
            return extractSamples(intensityNode);
        }
        String query = resolveQuery(INTENSITY_TYPE);
        String response = client.queryRange(query, start, end);
        JsonNode intensityNode = createResultNode(INTENSITY_TYPE, response);
        return extractSamples(intensityNode);
    }

    private List<LoadTest> persistDetectedTests(NavigableMap<Instant, Double> samples) {
        List<TestInterval> intervals = detectTests(samples);
        List<LoadTest> persistedTests = new ArrayList<>(intervals.size());
        for (TestInterval interval : intervals) {
            NavigableMap<Instant, Double> intervalSamples =
                    samples.subMap(interval.start(), true, interval.end(), true);
            LoadTestType type = classifyTest(intervalSamples, interval.start(), interval.end());
            LoadTest persisted = loadTestRepository.saveOrUpdate(interval.start(), interval.end(), type);
            persistedTests.add(persisted);
        }
        return persistedTests;
    }

    private String resolveQuery(String type) {
        if (INTENSITY_TYPE.equals(type)) {
            return grafanaPanelRepository.findTestQueryPanel()
                    .map(GrafanaDashboardPanelRecord::panelQuery)
                    .filter(StringUtils::hasText)
                    .orElseThrow(() -> new VmQueryNotFoundException(INTENSITY_TYPE));
        }

        Optional<GrafanaDashboardPanelRecord> panel = resolvePanel(type);
        return panel.map(GrafanaDashboardPanelRecord::panelQuery)
                .filter(StringUtils::hasText)
                .orElseThrow(() -> new VmQueryNotFoundException(type));
    }

    private Optional<GrafanaDashboardPanelRecord> resolvePanel(String type) {
        return tryResolveByUid(type).or(() -> {
            Integer panelId = parsePanelId(type);
            if (panelId == null) {
                return Optional.empty();
            }
            return grafanaPanelRepository.findFirstByPanelId(panelId);
        });
    }

    private Optional<GrafanaDashboardPanelRecord> tryResolveByUid(String value) {
        int delimiter = value.indexOf(':');
        if (delimiter <= 0 || delimiter >= value.length() - 1) {
            return Optional.empty();
        }
        String uid = value.substring(0, delimiter);
        Integer panelId = parsePanelId(value.substring(delimiter + 1));
        if (!StringUtils.hasText(uid) || panelId == null) {
            return Optional.empty();
        }
        return grafanaPanelRepository.findByUidAndPanelId(uid, panelId);
    }

    private Integer parsePanelId(String value) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException ex) {
            return null;
        }
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

    private LoadTestType classifyTest(NavigableMap<Instant, Double> intervalSamples, Instant start, Instant end) {
        if (intervalSamples == null || intervalSamples.isEmpty()) {
            return LoadTestType.UNKNOWN;
        }

        List<Instant> timestamps = new ArrayList<>(intervalSamples.keySet());
        List<Double> values = new ArrayList<>(intervalSamples.values());
        if (values.size() < MIN_SAMPLES_FOR_ANALYSIS) {
            return LoadTestType.UNKNOWN;
        }

        double max = values.stream().mapToDouble(Double::doubleValue).max().orElse(0.0d);
        if (max <= EPS) {
            return LoadTestType.UNKNOWN;
        }

        Duration totalDuration = Duration.between(start, end);
        PlateauSegment plateau = findStablePlateau(timestamps, values, max);
        LoadTestType plateauType = null;
        boolean stablePlateau = false;
        if (plateau != null) {
            int plateauLength = plateau.length();
            int requiredLength = Math.max(MIN_PLATEAU_POINTS, (int) Math.round(values.size() * PLATEAU_MIN_FRACTION));
            boolean longEnough = plateauLength >= requiredLength;
            boolean durationEnough = plateau.duration().compareTo(PLATEAU_MIN_DURATION) >= 0;
            double coverage = values.isEmpty() ? 0.0d : plateauLength / (double) values.size();
            double durationRatio = totalDuration.isZero()
                    ? 0.0d
                    : (double) plateau.duration().toMillis() / (double) totalDuration.toMillis();
            boolean coverageEnough = coverage >= PLATEAU_MIN_COVERAGE || durationRatio >= PLATEAU_DURATION_RATIO;

            if ((longEnough || durationEnough) && coverageEnough) {
                List<Double> plateauValues = values.subList(plateau.startIndex(), plateau.endIndex() + 1);
                double plateauMean = mean(plateauValues);
                if (plateauMean > EPS) {
                    double variation = coefficientOfVariation(plateauValues, plateauMean);
                    if (variation <= PLATEAU_VARIATION_THRESHOLD) {
                        List<Double> beforeValues = plateau.startIndex() > 0
                                ? values.subList(0, plateau.startIndex())
                                : List.of();
                        double beforeMean = mean(beforeValues);
                        if (Double.isNaN(beforeMean) || beforeMean <= EPS || plateauMean >= beforeMean * RAMP_UP_RATIO) {
                            stablePlateau = true;
                            plateauType = totalDuration.compareTo(SOAK_THRESHOLD) >= 0
                                    ? LoadTestType.SOAK
                                    : LoadTestType.CONFIRM;
                        }
                    }
                }
            }
        }

        int peakIndex = findPeakIndex(values);
        int quartileSize = Math.max(1, (int) Math.round(values.size() * QUARTILE_FRACTION));
        double earlyMean = mean(values.subList(0, Math.min(quartileSize, values.size())));
        int peakWindowStart = Math.max(0, peakIndex - quartileSize);
        int peakWindowEnd = Math.min(values.size(), peakIndex + quartileSize + 1);
        double peakWindowMean = mean(values.subList(peakWindowStart, peakWindowEnd));
        double lastValue = values.get(values.size() - 1);
        boolean upwardTrend;
        if (Double.isNaN(earlyMean) || earlyMean <= EPS) {
            upwardTrend = peakWindowMean > EPS;
        } else {
            upwardTrend = !Double.isNaN(peakWindowMean) && peakWindowMean >= earlyMean * GROWTH_RATIO_FOR_MAX;
        }
        boolean strongDrop = max > EPS && (max - lastValue) / max >= FINAL_DROP_RATIO;
        boolean peakNearEnd = peakIndex >= values.size() - Math.max(2, quartileSize);

        if (upwardTrend && (strongDrop || peakNearEnd) && !stablePlateau) {
            return LoadTestType.MAX;
        }

        if (plateauType != null) {
            return plateauType;
        }

        return LoadTestType.UNKNOWN;
    }

    private PlateauSegment findStablePlateau(List<Instant> timestamps, List<Double> values, double maxValue) {
        PlateauSegment plateau = findPlateauSegment(timestamps, values, maxValue * PLATEAU_LEVEL_RATIO);
        if (plateau == null) {
            plateau = findPlateauSegment(timestamps, values, maxValue * SECONDARY_PLATEAU_RATIO);
        }
        return plateau;
    }

    private PlateauSegment findPlateauSegment(List<Instant> timestamps, List<Double> values, double threshold) {
        int bestStart = -1;
        int bestEnd = -1;
        int currentStart = -1;

        for (int i = 0; i < values.size(); i++) {
            if (values.get(i) >= threshold) {
                if (currentStart < 0) {
                    currentStart = i;
                }
            } else if (currentStart >= 0) {
                if (bestStart < 0 || (i - 1) - currentStart > bestEnd - bestStart) {
                    bestStart = currentStart;
                    bestEnd = i - 1;
                }
                currentStart = -1;
            }
        }

        if (currentStart >= 0) {
            if (bestStart < 0 || (values.size() - 1) - currentStart > bestEnd - bestStart) {
                bestStart = currentStart;
                bestEnd = values.size() - 1;
            }
        }

        if (bestStart >= 0 && bestEnd >= bestStart) {
            return new PlateauSegment(bestStart, bestEnd, timestamps.get(bestStart), timestamps.get(bestEnd));
        }
        return null;
    }

    private int findPeakIndex(List<Double> values) {
        if (values.isEmpty()) {
            return 0;
        }
        double peak = values.get(0);
        int peakIndex = 0;
        for (int i = 1; i < values.size(); i++) {
            double value = values.get(i);
            if (value > peak) {
                peak = value;
                peakIndex = i;
            }
        }
        return peakIndex;
    }

    private double mean(List<Double> values) {
        if (values == null || values.isEmpty()) {
            return Double.NaN;
        }
        double sum = 0.0d;
        for (double value : values) {
            sum += value;
        }
        return sum / values.size();
    }

    private double coefficientOfVariation(List<Double> values, double mean) {
        if (values == null || values.isEmpty() || mean <= EPS) {
            return Double.POSITIVE_INFINITY;
        }
        double stdDev = standardDeviation(values, mean);
        return stdDev / mean;
    }

    private double standardDeviation(List<Double> values, double mean) {
        if (values == null || values.isEmpty()) {
            return Double.NaN;
        }
        double sumSquares = 0.0d;
        for (double value : values) {
            double diff = value - mean;
            sumSquares += diff * diff;
        }
        return Math.sqrt(sumSquares / values.size());
    }

    private record PlateauSegment(int startIndex, int endIndex, Instant startTimestamp, Instant endTimestamp) {

        int length() {
            return endIndex - startIndex + 1;
        }

        Duration duration() {
            if (endTimestamp.isAfter(startTimestamp)) {
                return Duration.between(startTimestamp, endTimestamp);
            }
            return Duration.ZERO;
        }
    }

    private record TestInterval(Instant start, Instant end) {}
}
