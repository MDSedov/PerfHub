package com.example.chronicler.service;

import com.example.chronicler.client.VictoriaMetricsClient;
import com.example.chronicler.dto.RequirementEvaluationView;
import com.example.chronicler.exception.RequirementEvaluationException;
import com.example.chronicler.model.GrafanaDashboard;
import com.example.chronicler.model.RequirementEvaluationStatus;
import com.example.chronicler.model.TestRequirementEvaluation;
import com.example.chronicler.repository.GrafanaDashboardPanelRepository;
import com.example.chronicler.repository.GrafanaDashboardRepository;
import com.example.chronicler.repository.LoadTestRepository;
import com.example.chronicler.repository.TestRequirementEvaluationRepository;
import com.example.chronicler.repository.model.GrafanaDashboardPanelRecord;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalDouble;
import java.util.UUID;
import java.util.Objects;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class RequirementEvaluationService {

    private static final Duration TRIM_DURATION = Duration.ofMinutes(3);
    private static final Duration MIN_EVALUATION_DURATION = TRIM_DURATION.multipliedBy(2);

    private final TestRequirementEvaluationRepository evaluationRepository;
    private final LoadTestRepository loadTestRepository;
    private final GrafanaDashboardRepository grafanaDashboardRepository;
    private final GrafanaDashboardPanelRepository grafanaDashboardPanelRepository;
    private final VictoriaMetricsClient victoriaMetricsClient;
    private final GrafanaVariableService grafanaVariableService;
    private final ObjectMapper objectMapper;

    public RequirementEvaluationService(
            TestRequirementEvaluationRepository evaluationRepository,
            LoadTestRepository loadTestRepository,
            GrafanaDashboardRepository grafanaDashboardRepository,
            GrafanaDashboardPanelRepository grafanaDashboardPanelRepository,
            VictoriaMetricsClient victoriaMetricsClient,
            GrafanaVariableService grafanaVariableService,
            ObjectMapper objectMapper) {
        this.evaluationRepository = evaluationRepository;
        this.loadTestRepository = loadTestRepository;
        this.grafanaDashboardRepository = grafanaDashboardRepository;
        this.grafanaDashboardPanelRepository = grafanaDashboardPanelRepository;
        this.victoriaMetricsClient = victoriaMetricsClient;
        this.grafanaVariableService = grafanaVariableService;
        this.objectMapper = objectMapper;
    }

    public List<RequirementEvaluationView> evaluate(UUID testUuid) {
        var loadTest = loadTestRepository.findById(testUuid)
                .orElseThrow(() -> new RequirementEvaluationException("Test with UUID " + testUuid + " not found"));
        Instant start = loadTest.start();
        Instant end = loadTest.end();
        if (start == null || end == null) {
            throw new RequirementEvaluationException("Test " + testUuid + " must have start and end timestamps");
        }
        if (!end.isAfter(start)) {
            throw new RequirementEvaluationException("Test " + testUuid + " end timestamp must be after start");
        }

        List<PanelRequirement> requirements = loadPanelRequirements();
        if (requirements.isEmpty()) {
            throw new RequirementEvaluationException("No active panel requirements configured");
        }

        Instant evaluationStart = start.plus(TRIM_DURATION);
        Instant evaluationEnd = end.minus(TRIM_DURATION);
        if (!evaluationEnd.isAfter(evaluationStart)) {
            throw new RequirementEvaluationException("Test " + testUuid + " duration must exceed "
                    + MIN_EVALUATION_DURATION.toMinutes()
                    + " minutes to evaluate requirements");
        }

        Instant evaluatedAt = Instant.now();
        List<RequirementEvaluationView> views = new ArrayList<>(requirements.size());
        for (PanelRequirement requirement : requirements) {
            RequirementResult result = evaluatePanel(requirement, evaluationStart, evaluationEnd);
            evaluationRepository.upsert(
                    testUuid,
                    requirement.dashboardUid(),
                    requirement.panelId(),
                    result.status(),
                    result.measuredValue(),
                    result.unit(),
                    result.details(),
                    evaluatedAt);
            views.add(toView(requirement, result, evaluatedAt));
        }
        return views;
    }

    public List<RequirementEvaluationView> getCachedOrEvaluate(UUID testUuid) {
        List<PanelRequirement> requirements = loadPanelRequirements();
        List<TestRequirementEvaluation> stored = evaluationRepository.findByTest(testUuid);
        Map<String, TestRequirementEvaluation> storedByRequirement = stored.stream()
                .collect(Collectors.toMap(
                        eval -> eval.dashboardUid() + "#" + eval.panelId(),
                        eval -> eval,
                        (first, second) -> first));
        boolean hasAllEvaluations = !stored.isEmpty()
                && requirements.stream()
                        .allMatch(req -> storedByRequirement.containsKey(req.dashboardUid() + "#" + req.panelId()));
        if (hasAllEvaluations) {
            List<RequirementEvaluationView> views = new ArrayList<>(requirements.size());
            for (PanelRequirement requirement : requirements) {
                TestRequirementEvaluation evaluation =
                        storedByRequirement.get(requirement.dashboardUid() + "#" + requirement.panelId());
                views.add(toView(
                        requirement,
                        new RequirementResult(
                                evaluation.status(),
                                evaluation.measuredValue(),
                                evaluation.unit(),
                                evaluation.details()),
                        evaluation.evaluatedAt()));
            }
            return views;
        }
        return evaluate(testUuid);
    }

    private RequirementEvaluationView toView(
            PanelRequirement requirement, RequirementResult result, Instant evaluatedAt) {
        return new RequirementEvaluationView(
                requirement.dashboardUid(),
                requirement.panelId(),
                requirement.title(),
                requirement.threshold(),
                result.status(),
                result.measuredValue(),
                result.unit(),
                result.details(),
                evaluatedAt);
    }

    private RequirementResult evaluatePanel(PanelRequirement requirement, Instant start, Instant end) {
        if (!StringUtils.hasText(requirement.query()) || requirement.threshold() == null) {
            ObjectNode details = objectMapper.createObjectNode();
            details.put("reason", "Query or threshold is not set");
            return new RequirementResult(RequirementEvaluationStatus.NOT_APPLICABLE, null, null, details);
        }
        JsonNode response = executeExpression(requirement.query(), start, end);
        OptionalDouble maxValueOpt = extractMaxValue(response);
        if (maxValueOpt.isEmpty()) {
            ObjectNode details = objectMapper.createObjectNode();
            details.put("reason", "No data for the selected interval");
            return new RequirementResult(RequirementEvaluationStatus.NOT_APPLICABLE, null, null, details);
        }
        double maxValue = maxValueOpt.getAsDouble();
        RequirementEvaluationStatus status = maxValue <= requirement.threshold()
                ? RequirementEvaluationStatus.PASS
                : RequirementEvaluationStatus.FAIL;
        ObjectNode details = objectMapper.createObjectNode();
        details.put("maxValue", maxValue);
        details.put("threshold", requirement.threshold());
        details.put("rule", "value <= threshold");
        return new RequirementResult(status, maxValue, null, details);
    }

    private JsonNode executeExpression(String expression, Instant start, Instant end) {
        try {
            String response = victoriaMetricsClient.queryRange(expression, start, end);
            if (response == null || response.isBlank()) {
                return objectMapper.createObjectNode();
            }
            return objectMapper.readTree(response);
        } catch (JsonProcessingException ex) {
            throw new RequirementEvaluationException("Failed to parse VictoriaMetrics response", ex);
        }
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

    private OptionalDouble extractMaxValue(JsonNode dataNode) {
        List<Double> values = extractValues(dataNode);
        return values.isEmpty()
                ? OptionalDouble.empty()
                : OptionalDouble.of(values.stream()
                        .filter(value -> !Double.isNaN(value) && !Double.isInfinite(value))
                        .mapToDouble(Double::doubleValue)
                        .max()
                        .orElse(Double.NaN));
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

    private List<PanelRequirement> loadPanelRequirements() {
        Map<String, List<GrafanaDashboardPanelRecord>> panelsByUid =
                grafanaDashboardPanelRepository.findAllGroupedByUid();
        return grafanaDashboardRepository.findAll().stream()
                .filter(GrafanaDashboard::enabled)
                .flatMap(dashboard -> dashboard.panels().stream()
                        .map(panelId -> findPanel(panelsByUid, dashboard.uid(), panelId)))
                .filter(Objects::nonNull)
                .toList();
    }

    private PanelRequirement findPanel(Map<String, List<GrafanaDashboardPanelRecord>> panelsByUid, String uid, int panelId) {
        List<GrafanaDashboardPanelRecord> panels = panelsByUid.getOrDefault(uid, List.of());
        return panels.stream()
                .filter(panel -> panel.panelId() == panelId)
                .findFirst()
                .map(panel -> new PanelRequirement(
                        panel.dashboardUid(),
                        panel.panelId(),
                        panel.panelTitle(),
                        grafanaVariableService.applyVariables(panel.panelQuery(), panel.dashboardUid()),
                        panel.panelThreshold(),
                        Boolean.TRUE.equals(panel.testQuery())))
                .orElse(null);
    }

    private record PanelRequirement(
            String dashboardUid, int panelId, String title, String query, Double threshold, boolean testQuery) {}

    private record RequirementResult(
            RequirementEvaluationStatus status, Double measuredValue, String unit, JsonNode details) {}
}
