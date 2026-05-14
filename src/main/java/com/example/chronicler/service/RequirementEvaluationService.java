package com.example.chronicler.service;

import com.example.chronicler.client.VictoriaMetricsClient;
import com.example.chronicler.dto.RequirementEvaluationView;
import com.example.chronicler.dto.tests.RequirementComplianceView;
import com.example.chronicler.exception.RequirementEvaluationException;
import com.example.chronicler.model.ConfluenceTemplate;
import com.example.chronicler.model.ConfluenceTemplateChart;
import com.example.chronicler.model.LoadTest;
import com.example.chronicler.model.LoadTestType;
import com.example.chronicler.model.RequirementEvaluationStatus;
import com.example.chronicler.model.TestRequirementEvaluation;
import com.example.chronicler.repository.LoadTestRepository;
import com.example.chronicler.repository.GrafanaDashboardPanelRepository;
import com.example.chronicler.repository.TestRequirementEvaluationRepository;
import com.example.chronicler.repository.model.GrafanaDashboardPanelRecord;
import com.example.chronicler.util.DateTimeViewUtils;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class RequirementEvaluationService {

    private static final String TEMPLATE_MISSING_MESSAGE =
            "Для данного типа теста отсутствует шаблона отчета";
    private static final String TEMPLATE_CHARTS_MISSING_MESSAGE =
            "В шаблоне отчета для данного типа теста отсутствуют графики";
    private static final Duration TRIM_DURATION = Duration.ofMinutes(3);
    private static final Duration MIN_EVALUATION_DURATION = TRIM_DURATION.multipliedBy(2);

    private final TestRequirementEvaluationRepository evaluationRepository;
    private final LoadTestRepository loadTestRepository;
    private final GrafanaDashboardPanelRepository grafanaDashboardPanelRepository;
    private final VictoriaMetricsClient victoriaMetricsClient;
    private final GrafanaVariableService grafanaVariableService;
    private final ConfluenceTemplateSettingsService confluenceTemplateSettingsService;
    private final ObjectMapper objectMapper;

    public RequirementEvaluationService(
            TestRequirementEvaluationRepository evaluationRepository,
            LoadTestRepository loadTestRepository,
            GrafanaDashboardPanelRepository grafanaDashboardPanelRepository,
            VictoriaMetricsClient victoriaMetricsClient,
            GrafanaVariableService grafanaVariableService,
            ConfluenceTemplateSettingsService confluenceTemplateSettingsService,
            ObjectMapper objectMapper) {
        this.evaluationRepository = evaluationRepository;
        this.loadTestRepository = loadTestRepository;
        this.grafanaDashboardPanelRepository = grafanaDashboardPanelRepository;
        this.victoriaMetricsClient = victoriaMetricsClient;
        this.grafanaVariableService = grafanaVariableService;
        this.confluenceTemplateSettingsService = confluenceTemplateSettingsService;
        this.objectMapper = objectMapper;
    }

    public List<RequirementEvaluationView> evaluate(UUID testUuid) {
        LoadTest loadTest = getLoadTest(testUuid);
        try {
            List<PanelRequirement> requirements = resolvePanelRequirements(loadTest, true);
            return evaluate(loadTest, requirements);
        } catch (RequirementEvaluationException ex) {
            evaluationRepository.deleteByTest(loadTest.id());
            throw ex;
        }
    }

    public RequirementComplianceView buildComplianceView(LoadTest loadTest) {
        if (loadTest == null || loadTest.id() == null) {
            return new RequirementComplianceView(false, null, null);
        }
        List<PanelRequirement> requirements = resolvePanelRequirements(loadTest, false);
        if (requirements.isEmpty()) {
            return new RequirementComplianceView(false, null, null);
        }

        Map<String, TestRequirementEvaluation> evaluationsByKey = evaluationRepository.findByTest(loadTest.id()).stream()
                .collect(Collectors.toMap(
                        evaluation -> requirementKey(evaluation.dashboardUid(), evaluation.panelId()),
                        evaluation -> evaluation,
                        (first, second) -> first,
                        LinkedHashMap::new));
        List<TestRequirementEvaluation> relevantEvaluations = requirements.stream()
                .map(requirement -> evaluationsByKey.get(requirement.key()))
                .filter(Objects::nonNull)
                .toList();
        if (relevantEvaluations.size() != requirements.size()) {
            return new RequirementComplianceView(false, null, null);
        }

        boolean compliant = relevantEvaluations.stream()
                .allMatch(evaluation -> evaluation.status() == RequirementEvaluationStatus.PASS
                        || evaluation.status() == RequirementEvaluationStatus.NOT_APPLICABLE);
        Instant evaluatedAt = relevantEvaluations.stream()
                .map(TestRequirementEvaluation::evaluatedAt)
                .filter(Objects::nonNull)
                .max(Instant::compareTo)
                .orElse(null);
        return new RequirementComplianceView(
                true,
                compliant,
                DateTimeViewUtils.formatInstant(evaluatedAt));
    }

    private List<RequirementEvaluationView> evaluate(LoadTest loadTest, List<PanelRequirement> requirements) {
        if (loadTest == null || loadTest.id() == null) {
            throw new RequirementEvaluationException("Test must be specified for requirement evaluation");
        }
        Instant start = loadTest.start();
        Instant end = loadTest.end();
        if (start == null || end == null) {
            throw new RequirementEvaluationException("Test " + loadTest.id() + " must have start and end timestamps");
        }
        if (!end.isAfter(start)) {
            throw new RequirementEvaluationException("Test " + loadTest.id() + " end timestamp must be after start");
        }

        evaluationRepository.deleteByTest(loadTest.id());
        if (requirements.isEmpty()) {
            return List.of();
        }

        Instant evaluationStart = start.plus(TRIM_DURATION);
        Instant evaluationEnd = end.minus(TRIM_DURATION);
        boolean hasQueryableRequirements = requirements.stream()
                .anyMatch(requirement -> hasThreshold(requirement.threshold())
                        && StringUtils.hasText(requirement.query()));
        if (hasQueryableRequirements && !evaluationEnd.isAfter(evaluationStart)) {
            throw new RequirementEvaluationException("Test " + loadTest.id() + " duration must exceed "
                    + MIN_EVALUATION_DURATION.toMinutes()
                    + " minutes to evaluate requirements");
        }

        Instant evaluatedAt = Instant.now();
        List<RequirementEvaluationView> views = new ArrayList<>(requirements.size());
        for (PanelRequirement requirement : requirements) {
            RequirementResult result = evaluatePanel(requirement, evaluationStart, evaluationEnd);
            evaluationRepository.upsert(
                    loadTest.id(),
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
        LoadTest loadTest = getLoadTest(testUuid);
        try {
            List<PanelRequirement> requirements = resolvePanelRequirements(loadTest, true);
            List<TestRequirementEvaluation> stored = evaluationRepository.findByTest(testUuid);
            Map<String, TestRequirementEvaluation> storedByRequirement = stored.stream()
                    .collect(Collectors.toMap(
                            eval -> requirementKey(eval.dashboardUid(), eval.panelId()),
                            eval -> eval,
                            (first, second) -> first));
            boolean hasAllEvaluations = !stored.isEmpty()
                    && requirements.stream().allMatch(req -> {
                        TestRequirementEvaluation evaluation = storedByRequirement.get(req.key());
                        return evaluation != null && isStoredEvaluationCurrent(req, evaluation);
                    });
            if (hasAllEvaluations) {
                List<RequirementEvaluationView> views = new ArrayList<>(requirements.size());
                for (PanelRequirement requirement : requirements) {
                    TestRequirementEvaluation evaluation = storedByRequirement.get(requirement.key());
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
            return evaluate(loadTest, requirements);
        } catch (RequirementEvaluationException ex) {
            evaluationRepository.deleteByTest(testUuid);
            throw ex;
        }
    }

    private boolean isStoredEvaluationCurrent(PanelRequirement requirement, TestRequirementEvaluation evaluation) {
        String expectedQuery = normalizeVictoriaMetricsQuery(requirement.query());
        if (!StringUtils.hasText(expectedQuery)) {
            return true;
        }
        JsonNode storedQuery = evaluation.details() != null ? evaluation.details().path("query") : null;
        if (storedQuery != null && storedQuery.isTextual()) {
            return expectedQuery.equals(storedQuery.asText());
        }
        String previousQuery = StringUtils.trimWhitespace(requirement.query());
        return expectedQuery.equals(previousQuery);
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
        if (!hasThreshold(requirement.threshold())) {
            ObjectNode details = objectMapper.createObjectNode();
            details.put("reason", "Requirement threshold is not set");
            return new RequirementResult(RequirementEvaluationStatus.PASS, null, null, details);
        }
        if (!StringUtils.hasText(requirement.query())) {
            ObjectNode details = objectMapper.createObjectNode();
            details.put("reason", "Query is not set");
            return new RequirementResult(RequirementEvaluationStatus.NOT_APPLICABLE, null, null, details);
        }
        String query = normalizeVictoriaMetricsQuery(requirement.query());
        JsonNode response = executeExpression(query, start, end);
        OptionalDouble maxValueOpt = extractMaxValue(response);
        if (maxValueOpt.isEmpty()) {
            ObjectNode details = objectMapper.createObjectNode();
            details.put("reason", "No data for the selected interval");
            details.put("query", query);
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
        details.put("query", query);
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
                double instantValue = parseInstantVectorValue(series.path("value"));
                if (!Double.isNaN(instantValue) && !Double.isInfinite(instantValue)) {
                    values.add(instantValue);
                }
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

    private double parseInstantVectorValue(JsonNode valueNode) {
        if (valueNode == null || valueNode.isMissingNode()) {
            return Double.NaN;
        }
        if (valueNode.isArray() && valueNode.size() >= 2) {
            return parseSampleValue(valueNode.get(1));
        }
        return parseSampleValue(valueNode);
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

    private String normalizeVictoriaMetricsQuery(String query) {
        String normalized = StringUtils.trimWhitespace(query);
        if (!StringUtils.hasText(normalized)) {
            return normalized;
        }
        return normalizeGraphiteMetricSelector(normalized);
    }

    private String normalizeGraphiteMetricSelector(String query) {
        if (!query.startsWith("{") || !query.endsWith("}")) {
            return query;
        }
        String selectorBody = query.substring(1, query.length() - 1).trim();
        if (!isQuotedGraphiteMetric(selectorBody)) {
            return query;
        }
        String graphiteMetric = unquote(selectorBody);
        if (!StringUtils.hasText(graphiteMetric)) {
            return query;
        }
        return "{__graphite__=\"" + escapeLabelValue(graphiteMetric) + "\"}";
    }

    private boolean isQuotedGraphiteMetric(String value) {
        if (value.length() < 2) {
            return false;
        }
        char quote = value.charAt(0);
        if ((quote != '"' && quote != '\'') || value.charAt(value.length() - 1) != quote) {
            return false;
        }
        return value.indexOf('=') < 0;
    }

    private String unquote(String value) {
        String result = value.substring(1, value.length() - 1);
        return result.replace("\\\"", "\"")
                .replace("\\'", "'")
                .replace("\\\\", "\\");
    }

    private String escapeLabelValue(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private LoadTest getLoadTest(UUID testUuid) {
        return loadTestRepository.findById(testUuid)
                .orElseThrow(() -> new RequirementEvaluationException("Test with UUID " + testUuid + " not found"));
    }

    private List<PanelRequirement> resolvePanelRequirements(LoadTest loadTest, boolean strict) {
        if (loadTest == null) {
            return List.of();
        }
        Optional<ConfluenceTemplate> template = resolveTemplate(loadTest.type());
        if (template.isEmpty()) {
            if (strict) {
                throw new RequirementEvaluationException(TEMPLATE_MISSING_MESSAGE);
            }
            return List.of();
        }

        Map<String, PanelRequirement> requirementsByKey = new LinkedHashMap<>();
        List<ConfluenceTemplateChart> charts = template.get().charts() == null ? List.of() : template.get().charts();
        boolean hasConfiguredCharts = charts.stream()
                .anyMatch(chart -> chart != null && StringUtils.hasText(chart.dashboardUid()) && chart.panelId() > 0);
        if (!hasConfiguredCharts) {
            if (strict) {
                throw new RequirementEvaluationException(TEMPLATE_CHARTS_MISSING_MESSAGE);
            }
            return List.of();
        }
        for (ConfluenceTemplateChart chart : charts) {
            if (chart == null || !StringUtils.hasText(chart.dashboardUid()) || chart.panelId() <= 0) {
                continue;
            }
            GrafanaDashboardPanelRecord panel = grafanaDashboardPanelRepository
                    .findByUidAndPanelId(chart.dashboardUid(), chart.panelId())
                    .orElse(null);
            if (panel == null) {
                continue;
            }
            String query = grafanaVariableService.applyVariables(panel.panelQuery(), panel.dashboardUid());
            String title = StringUtils.hasText(chart.title()) ? chart.title().trim() : panel.panelTitle();
            PanelRequirement requirement = new PanelRequirement(
                    panel.dashboardUid(),
                    panel.panelId(),
                    title,
                    query,
                    panel.panelThreshold());
            requirementsByKey.putIfAbsent(requirement.key(), requirement);
        }
        if (requirementsByKey.isEmpty() && strict) {
            throw new RequirementEvaluationException(TEMPLATE_CHARTS_MISSING_MESSAGE);
        }
        return new ArrayList<>(requirementsByKey.values());
    }

    private Optional<ConfluenceTemplate> resolveTemplate(LoadTestType loadTestType) {
        if (loadTestType == null || loadTestType == LoadTestType.UNKNOWN) {
            return Optional.empty();
        }
        String shortName = confluenceTemplateSettingsService.resolveDefaultTemplateShortName(loadTestType);
        return confluenceTemplateSettingsService.findActiveTemplateByShortName(shortName);
    }

    private String requirementKey(String dashboardUid, int panelId) {
        return dashboardUid + "#" + panelId;
    }

    private boolean hasThreshold(Double threshold) {
        return threshold != null && !Double.isNaN(threshold) && !Double.isInfinite(threshold);
    }

    private record PanelRequirement(String dashboardUid, int panelId, String title, String query, Double threshold) {
        private String key() {
            return dashboardUid + "#" + panelId;
        }
    }

    private record RequirementResult(
            RequirementEvaluationStatus status, Double measuredValue, String unit, JsonNode details) {}
}
