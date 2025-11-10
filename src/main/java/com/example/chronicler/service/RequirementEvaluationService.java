package com.example.chronicler.service;

import com.example.chronicler.client.VictoriaMetricsClient;
import com.example.chronicler.dto.RequirementEvaluationView;
import com.example.chronicler.exception.RequirementEvaluationException;
import com.example.chronicler.exception.VmQueryNotFoundException;
import com.example.chronicler.model.LoadTest;
import com.example.chronicler.model.NonFunctionalRequirement;
import com.example.chronicler.model.RequirementEvaluationStatus;
import com.example.chronicler.model.TestRequirementEvaluation;
import com.example.chronicler.registry.VmQueryRegistry;
import com.example.chronicler.repository.LoadTestRepository;
import com.example.chronicler.repository.NonFunctionalRequirementRepository;
import com.example.chronicler.repository.TestRequirementEvaluationRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalDouble;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class RequirementEvaluationService {

    private static final String DEFAULT_GROUP_LABEL = "container";

    private final NonFunctionalRequirementRepository requirementRepository;
    private final TestRequirementEvaluationRepository evaluationRepository;
    private final LoadTestRepository loadTestRepository;
    private final VmQueryRegistry vmQueryRegistry;
    private final VictoriaMetricsClient victoriaMetricsClient;
    private final ObjectMapper objectMapper;

    public RequirementEvaluationService(
            NonFunctionalRequirementRepository requirementRepository,
            TestRequirementEvaluationRepository evaluationRepository,
            LoadTestRepository loadTestRepository,
            VmQueryRegistry vmQueryRegistry,
            VictoriaMetricsClient victoriaMetricsClient,
            ObjectMapper objectMapper) {
        this.requirementRepository = requirementRepository;
        this.evaluationRepository = evaluationRepository;
        this.loadTestRepository = loadTestRepository;
        this.vmQueryRegistry = vmQueryRegistry;
        this.victoriaMetricsClient = victoriaMetricsClient;
        this.objectMapper = objectMapper;
    }

    public List<RequirementEvaluationView> evaluate(UUID testUuid) {
        LoadTest loadTest = loadTestRepository.findById(testUuid)
                .orElseThrow(() -> new RequirementEvaluationException("Test with UUID " + testUuid + " not found"));
        Instant start = loadTest.start();
        Instant end = loadTest.end();
        if (start == null || end == null) {
            throw new RequirementEvaluationException("Test " + testUuid + " must have start and end timestamps");
        }
        if (!end.isAfter(start)) {
            throw new RequirementEvaluationException("Test " + testUuid + " end timestamp must be after start");
        }

        List<NonFunctionalRequirement> requirements = requirementRepository.findAllActive();
        if (requirements.isEmpty()) {
            throw new RequirementEvaluationException("No active non-functional requirements configured");
        }

        Instant evaluatedAt = Instant.now();
        Map<String, JsonNode> queryCache = new HashMap<>();
        List<RequirementEvaluationView> views = new ArrayList<>(requirements.size());
        for (NonFunctionalRequirement requirement : requirements) {
            RequirementResult result = evaluateRequirement(requirement, start, end, queryCache);
            evaluationRepository.upsert(
                    testUuid,
                    requirement.id(),
                    result.status(),
                    result.measuredValue(),
                    result.unit(),
                    result.details(),
                    evaluatedAt);
            views.add(toView(
                    requirement,
                    result.status(),
                    result.measuredValue(),
                    result.unit(),
                    result.details(),
                    evaluatedAt));
        }
        return views;
    }

    public List<RequirementEvaluationView> getCachedOrEvaluate(UUID testUuid) {
        List<TestRequirementEvaluation> stored = evaluationRepository.findByTest(testUuid);
        List<NonFunctionalRequirement> requirements = requirementRepository.findAllActive();
        Map<Integer, TestRequirementEvaluation> storedByRequirement = stored.stream()
                .collect(Collectors.toMap(TestRequirementEvaluation::requirementId, eval -> eval));
        boolean hasAllEvaluations = !stored.isEmpty()
                && requirements.stream().allMatch(req -> storedByRequirement.containsKey(req.id()));
        if (hasAllEvaluations) {
            List<RequirementEvaluationView> views = new ArrayList<>(requirements.size());
            for (NonFunctionalRequirement requirement : requirements) {
                TestRequirementEvaluation evaluation = storedByRequirement.get(requirement.id());
                views.add(toView(
                        requirement,
                        evaluation.status(),
                        evaluation.measuredValue(),
                        evaluation.unit(),
                        evaluation.details(),
                        evaluation.evaluatedAt()));
            }
            return views;
        }
        return evaluate(testUuid);
    }

    private RequirementEvaluationView toView(
            NonFunctionalRequirement requirement,
            RequirementEvaluationStatus status,
            Double measuredValue,
            String unit,
            JsonNode details,
            Instant evaluatedAt) {
        return new RequirementEvaluationView(
                requirement.id(),
                requirement.code(),
                requirement.metricType(),
                requirement.thresholdValue(),
                requirement.thresholdUnit(),
                status,
                measuredValue,
                unit,
                details,
                evaluatedAt,
                requirement.descriptionRu(),
                requirement.descriptionEn());
    }

    private RequirementResult evaluateRequirement(
            NonFunctionalRequirement requirement, Instant start, Instant end, Map<String, JsonNode> queryCache) {
        return switch (requirement.metricType()) {
            case LATENCY_P90 -> evaluateLatency(requirement, start, end, queryCache);
            case ERROR_RATE -> evaluateErrorRate(requirement, start, end, queryCache);
            case CPU_UTILIZATION -> evaluateContainerUtilization(requirement, start, end, queryCache);
            case MEMORY_UTILIZATION -> evaluateContainerUtilization(requirement, start, end, queryCache);
            case POD_RESTARTS -> evaluatePodRestarts(requirement, start, end, queryCache);
        };
    }

    private RequirementResult evaluateLatency(
            NonFunctionalRequirement requirement, Instant start, Instant end, Map<String, JsonNode> queryCache) {
        String queryKey = jsonText(requirement.parameters(), "query");
        JsonNode response = resolveQuery(queryKey, start, end, queryCache);
        OptionalDouble maxValueOpt = extractMaxValue(response);
        if (maxValueOpt.isEmpty()) {
            ObjectNode details = objectMapper.createObjectNode();
            details.put("reason", "No latency samples available for the specified interval");
            return new RequirementResult(RequirementEvaluationStatus.NOT_APPLICABLE, null, requirement.thresholdUnit(), details);
        }
        double maxValue = maxValueOpt.getAsDouble();
        RequirementEvaluationStatus status = maxValue <= requirement.thresholdValue()
                ? RequirementEvaluationStatus.PASS
                : RequirementEvaluationStatus.FAIL;
        ObjectNode details = objectMapper.createObjectNode();
        details.put("maxLatency", maxValue);
        details.put("threshold", requirement.thresholdValue());
        details.put("rule", "value <= threshold");
        return new RequirementResult(status, maxValue, requirement.thresholdUnit(), details);
    }

    private RequirementResult evaluateErrorRate(
            NonFunctionalRequirement requirement, Instant start, Instant end, Map<String, JsonNode> queryCache) {
        String totalQueryKey = jsonText(requirement.parameters(), "totalQuery");
        String errorQueryKey = jsonText(requirement.parameters(), "errorQuery");
        JsonNode totalResponse = resolveQuery(totalQueryKey, start, end, queryCache);
        JsonNode errorResponse = resolveQuery(errorQueryKey, start, end, queryCache);
        OptionalDouble totalOpt = extractMaxValue(totalResponse);
        OptionalDouble errorOpt = extractMaxValue(errorResponse);
        if (totalOpt.isEmpty()) {
            ObjectNode details = objectMapper.createObjectNode();
            details.put("reason", "No request counters available for the specified interval");
            return new RequirementResult(
                    RequirementEvaluationStatus.NOT_APPLICABLE, null, requirement.thresholdUnit(), details);
        }

        double total = totalOpt.getAsDouble();
        double errors = errorOpt.orElse(0.0d);
        if (total <= 0.0d) {
            ObjectNode details = objectMapper.createObjectNode();
            details.put("reason", "Total request count is zero; cannot compute error rate");
            return new RequirementResult(
                    RequirementEvaluationStatus.NOT_APPLICABLE, null, requirement.thresholdUnit(), details);
        }

        double ratePercent = errors / total * 100.0d;
        RequirementEvaluationStatus status = ratePercent <= requirement.thresholdValue()
                ? RequirementEvaluationStatus.PASS
                : RequirementEvaluationStatus.FAIL;
        ObjectNode details = objectMapper.createObjectNode();
        details.put("totalRequests", total);
        details.put("errorRequests", errors);
        details.put("errorRatePercent", ratePercent);
        details.put("threshold", requirement.thresholdValue());
        details.put("rule", "value <= threshold");
        return new RequirementResult(status, ratePercent, requirement.thresholdUnit(), details);
    }

    private RequirementResult evaluateContainerUtilization(
            NonFunctionalRequirement requirement, Instant start, Instant end, Map<String, JsonNode> queryCache) {
        String queryKey = jsonText(requirement.parameters(), "query");
        String groupLabel =
                StringUtils.hasText(jsonText(requirement.parameters(), "groupLabel"))
                        ? jsonText(requirement.parameters(), "groupLabel")
                        : DEFAULT_GROUP_LABEL;
        JsonNode response = resolveQuery(queryKey, start, end, queryCache);
        Map<String, Double> maxByTarget = extractMaxBySeries(response, groupLabel);
        if (maxByTarget.isEmpty()) {
            ObjectNode details = objectMapper.createObjectNode();
            details.put("reason", "No utilization data found for the specified interval");
            return new RequirementResult(
                    RequirementEvaluationStatus.NOT_APPLICABLE, null, requirement.thresholdUnit(), details);
        }

        double worstValue = maxByTarget.values().stream()
                .mapToDouble(Double::doubleValue)
                .max()
                .orElse(0.0d);
        RequirementEvaluationStatus status = worstValue <= requirement.thresholdValue()
                ? RequirementEvaluationStatus.PASS
                : RequirementEvaluationStatus.FAIL;
        ArrayNode offenders = objectMapper.createArrayNode();
        maxByTarget.entrySet().stream()
                .sorted(Map.Entry.comparingByValue(Comparator.reverseOrder()))
                .forEach(entry -> {
                    ObjectNode node = objectMapper.createObjectNode();
                    node.put("target", entry.getKey());
                    node.put("maxValue", entry.getValue());
                    offenders.add(node);
                });
        ObjectNode details = objectMapper.createObjectNode();
        details.set("targets", offenders);
        details.put("threshold", requirement.thresholdValue());
        details.put("rule", "value <= threshold for each target");
        return new RequirementResult(status, worstValue, requirement.thresholdUnit(), details);
    }

    private RequirementResult evaluatePodRestarts(
            NonFunctionalRequirement requirement, Instant start, Instant end, Map<String, JsonNode> queryCache) {
        String queryKey = jsonText(requirement.parameters(), "query");
        String groupLabel =
                StringUtils.hasText(jsonText(requirement.parameters(), "groupLabel"))
                        ? jsonText(requirement.parameters(), "groupLabel")
                        : "pod";
        JsonNode response = resolveQuery(queryKey, start, end, queryCache);
        Map<String, Double> maxByTarget = extractMaxBySeries(response, groupLabel);
        if (maxByTarget.isEmpty()) {
            ObjectNode details = objectMapper.createObjectNode();
            details.put("reason", "No restart metrics found for the specified interval");
            return new RequirementResult(
                    RequirementEvaluationStatus.NOT_APPLICABLE, null, requirement.thresholdUnit(), details);
        }
        double totalRestarts = maxByTarget.values().stream().mapToDouble(Double::doubleValue).sum();
        RequirementEvaluationStatus status = totalRestarts <= requirement.thresholdValue()
                ? RequirementEvaluationStatus.PASS
                : RequirementEvaluationStatus.FAIL;
        ArrayNode restarts = objectMapper.createArrayNode();
        maxByTarget.entrySet().stream()
                .filter(entry -> entry.getValue() > 0.0d)
                .sorted(Map.Entry.comparingByValue(Comparator.reverseOrder()))
                .forEach(entry -> {
                    ObjectNode node = objectMapper.createObjectNode();
                    node.put("target", entry.getKey());
                    node.put("restarts", entry.getValue());
                    restarts.add(node);
                });
        ObjectNode details = objectMapper.createObjectNode();
        details.put("totalRestarts", totalRestarts);
        if (!restarts.isEmpty()) {
            details.set("restartsByTarget", restarts);
        }
        details.put("threshold", requirement.thresholdValue());
        details.put("rule", "value <= threshold");
        return new RequirementResult(status, totalRestarts, requirement.thresholdUnit(), details);
    }

    private JsonNode resolveQuery(String queryKey, Instant start, Instant end, Map<String, JsonNode> cache) {
        if (!StringUtils.hasText(queryKey)) {
            throw new RequirementEvaluationException("Requirement query key must be provided");
        }
        return cache.computeIfAbsent(queryKey, key -> {
            String expression = vmQueryRegistry
                    .findQuery(key)
                    .orElseThrow(() -> new VmQueryNotFoundException(key));
            String response = victoriaMetricsClient.queryRange(expression, start, end);
            try {
                if (response == null || response.isBlank()) {
                    return objectMapper.createObjectNode();
                }
                return objectMapper.readTree(response);
            } catch (JsonProcessingException ex) {
                throw new RequirementEvaluationException("Failed to parse VictoriaMetrics response", ex);
            }
        });
    }

    private OptionalDouble extractMaxValue(JsonNode dataNode) {
        List<Double> values = extractValues(dataNode);
        return values.isEmpty() ? OptionalDouble.empty() : OptionalDouble.of(values.stream()
                .filter(value -> !Double.isNaN(value) && !Double.isInfinite(value))
                .mapToDouble(Double::doubleValue)
                .max()
                .orElse(Double.NaN));
    }

    private Map<String, Double> extractMaxBySeries(JsonNode dataNode, String labelName) {
        Map<String, Double> result = new HashMap<>();
        JsonNode resultNode = dataNode.path("data").path("result");
        if (!resultNode.isArray()) {
            return result;
        }
        for (JsonNode series : resultNode) {
            String labelValue = series.path("metric").path(labelName).asText("");
            JsonNode valuesNode = series.path("values");
            if (!valuesNode.isArray()) {
                continue;
            }
            double max = Double.NEGATIVE_INFINITY;
            for (JsonNode sample : valuesNode) {
                if (!sample.isArray() || sample.size() < 2) {
                    continue;
                }
                double value = parseSampleValue(sample.get(1));
                if (!Double.isNaN(value) && !Double.isInfinite(value)) {
                    max = Math.max(max, value);
                }
            }
            if (max > Double.NEGATIVE_INFINITY) {
                result.merge(labelValue, max, Math::max);
            }
        }
        return result;
    }

    private List<Double> extractValues(JsonNode dataNode) {
        JsonNode resultNode = dataNode.path("data").path("result");
        if (!resultNode.isArray()) {
            return List.of();
        }
        List<Double> values = new ArrayList<>();
        for (JsonNode series : resultNode) {
            JsonNode seriesValues = series.path("values");
            if (!seriesValues.isArray()) {
                continue;
            }
            for (JsonNode sample : seriesValues) {
                if (!sample.isArray() || sample.size() < 2) {
                    continue;
                }
                double value = parseSampleValue(sample.get(1));
                if (!Double.isNaN(value) && !Double.isInfinite(value)) {
                    values.add(value);
                }
            }
        }
        return values;
    }

    private double parseSampleValue(JsonNode valueNode) {
        if (valueNode == null || valueNode.isNull()) {
            return Double.NaN;
        }
        if (valueNode.isNumber()) {
            return valueNode.asDouble();
        }
        if (valueNode.isTextual()) {
            try {
                return Double.parseDouble(valueNode.asText());
            } catch (NumberFormatException ignored) {
                return Double.NaN;
            }
        }
        return Double.NaN;
    }

    private String jsonText(JsonNode node, String field) {
        if (node == null || node.isNull()) {
            return null;
        }
        JsonNode valueNode = node.get(field);
        if (valueNode == null || valueNode.isNull()) {
            return null;
        }
        return valueNode.asText(null);
    }

    private record RequirementResult(
            RequirementEvaluationStatus status, Double measuredValue, String unit, JsonNode details) {}
}
