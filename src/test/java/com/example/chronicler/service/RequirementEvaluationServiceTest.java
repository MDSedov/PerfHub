package com.example.chronicler.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.chronicler.client.VictoriaMetricsClient;
import com.example.chronicler.dto.RequirementEvaluationView;
import com.example.chronicler.dto.tests.RequirementComplianceView;
import com.example.chronicler.model.ConfluenceTemplate;
import com.example.chronicler.model.ConfluenceTemplateChart;
import com.example.chronicler.model.LoadTest;
import com.example.chronicler.model.LoadTestType;
import com.example.chronicler.model.RequirementEvaluationStatus;
import com.example.chronicler.model.TestRequirementEvaluation;
import com.example.chronicler.repository.GrafanaDashboardPanelRepository;
import com.example.chronicler.repository.LoadTestRepository;
import com.example.chronicler.repository.TestRequirementEvaluationRepository;
import com.example.chronicler.repository.model.GrafanaDashboardPanelRecord;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class RequirementEvaluationServiceTest {

    @Mock
    private TestRequirementEvaluationRepository evaluationRepository;

    @Mock
    private LoadTestRepository loadTestRepository;

    @Mock
    private GrafanaDashboardPanelRepository grafanaDashboardPanelRepository;

    @Mock
    private VictoriaMetricsClient victoriaMetricsClient;

    @Mock
    private GrafanaVariableService grafanaVariableService;

    @Mock
    private ConfluenceTemplateSettingsService confluenceTemplateSettingsService;

    private RequirementEvaluationService service;

    @BeforeEach
    void setUp() {
        service = new RequirementEvaluationService(
                evaluationRepository,
                loadTestRepository,
                grafanaDashboardPanelRepository,
                victoriaMetricsClient,
                grafanaVariableService,
                confluenceTemplateSettingsService,
                new ObjectMapper());
    }

    @Test
    void evaluateUsesOnlyChartsFromTemplateForTestType() {
        UUID testId = UUID.randomUUID();
        LoadTest loadTest = new LoadTest(
                testId,
                Instant.parse("2026-04-13T10:00:00Z"),
                Instant.parse("2026-04-13T10:30:00Z"),
                LoadTestType.MAX,
                null);
        ConfluenceTemplate template = new ConfluenceTemplate(
                1,
                "max",
                "Max template",
                "Max report",
                "<p>Body</p>",
                List.of(
                        new ConfluenceTemplateChart(1L, 1, "CPU chart", "dash-a", 1, 0),
                        new ConfluenceTemplateChart(2L, 1, "Ignored chart", "dash-a", 2, 1),
                        new ConfluenceTemplateChart(3L, 1, "Latency chart", "dash-b", 3, 2)));

        when(loadTestRepository.findById(testId)).thenReturn(Optional.of(loadTest));
        when(confluenceTemplateSettingsService.findActiveTemplateByShortName("max"))
                .thenReturn(Optional.of(template));
        when(grafanaDashboardPanelRepository.findByUidAndPanelId("dash-a", 1))
                .thenReturn(Optional.of(new GrafanaDashboardPanelRecord(
                        "dash-a",
                        "slug-a",
                        "Dashboard A",
                        1,
                        "CPU",
                        "cpu_query",
                        70.0,
                        Boolean.FALSE)));
        when(grafanaDashboardPanelRepository.findByUidAndPanelId("dash-a", 2))
                .thenReturn(Optional.of(new GrafanaDashboardPanelRecord(
                        "dash-a",
                        "slug-a",
                        "Dashboard A",
                        2,
                        "Ignored",
                        "ignored_query",
                        null,
                        Boolean.FALSE)));
        when(grafanaDashboardPanelRepository.findByUidAndPanelId("dash-b", 3))
                .thenReturn(Optional.of(new GrafanaDashboardPanelRecord(
                        "dash-b",
                        "slug-b",
                        "Dashboard B",
                        3,
                        "Latency",
                        "latency_query",
                        10.0,
                        Boolean.FALSE)));
        when(grafanaVariableService.applyVariables("cpu_query", "dash-a")).thenReturn("cpu_query");
        when(grafanaVariableService.applyVariables("ignored_query", "dash-a")).thenReturn("ignored_query");
        when(grafanaVariableService.applyVariables("latency_query", "dash-b")).thenReturn("latency_query");
        when(victoriaMetricsClient.queryRange(eq("cpu_query"), any(Instant.class), any(Instant.class)))
                .thenReturn(metricResponse(65.0));
        when(victoriaMetricsClient.queryRange(eq("latency_query"), any(Instant.class), any(Instant.class)))
                .thenReturn(metricResponse(12.0));

        List<RequirementEvaluationView> result = service.evaluate(testId);

        assertThat(result).hasSize(2);
        assertThat(result)
                .extracting(RequirementEvaluationView::title)
                .containsExactly("CPU chart", "Latency chart");
        assertThat(result)
                .extracting(RequirementEvaluationView::status)
                .containsExactly(RequirementEvaluationStatus.PASS, RequirementEvaluationStatus.FAIL);

        verify(evaluationRepository).deleteByTest(testId);
        verify(evaluationRepository).upsert(
                eq(testId),
                eq("dash-a"),
                eq(1),
                eq(RequirementEvaluationStatus.PASS),
                eq(65.0),
                eq(null),
                any(),
                any(Instant.class));
        verify(evaluationRepository).upsert(
                eq(testId),
                eq("dash-b"),
                eq(3),
                eq(RequirementEvaluationStatus.FAIL),
                eq(12.0),
                eq(null),
                any(),
                any(Instant.class));
        verify(victoriaMetricsClient, never()).queryRange(eq("ignored_query"), any(Instant.class), any(Instant.class));
    }

    @Test
    void buildComplianceViewIgnoresIrrelevantStoredEvaluations() {
        UUID testId = UUID.randomUUID();
        LoadTest loadTest = new LoadTest(
                testId,
                Instant.parse("2026-04-13T10:00:00Z"),
                Instant.parse("2026-04-13T10:30:00Z"),
                LoadTestType.MAX,
                null);
        ConfluenceTemplate template = new ConfluenceTemplate(
                1,
                "max",
                "Max template",
                "Max report",
                "<p>Body</p>",
                List.of(new ConfluenceTemplateChart(1L, 1, "CPU chart", "dash-a", 1, 0)));

        when(confluenceTemplateSettingsService.findActiveTemplateByShortName("max"))
                .thenReturn(Optional.of(template));
        when(grafanaDashboardPanelRepository.findByUidAndPanelId("dash-a", 1))
                .thenReturn(Optional.of(new GrafanaDashboardPanelRecord(
                        "dash-a",
                        "slug-a",
                        "Dashboard A",
                        1,
                        "CPU",
                        "cpu_query",
                        70.0,
                        Boolean.FALSE)));
        when(grafanaVariableService.applyVariables("cpu_query", "dash-a")).thenReturn("cpu_query");
        when(evaluationRepository.findByTest(testId)).thenReturn(List.of(
                new TestRequirementEvaluation(
                        1L,
                        testId,
                        "dash-a",
                        1,
                        RequirementEvaluationStatus.PASS,
                        65.0,
                        null,
                        JsonNodeFactory.instance.objectNode(),
                        Instant.parse("2026-04-13T12:00:00Z")),
                new TestRequirementEvaluation(
                        2L,
                        testId,
                        "legacy-dash",
                        99,
                        RequirementEvaluationStatus.FAIL,
                        999.0,
                        null,
                        JsonNodeFactory.instance.objectNode(),
                        Instant.parse("2026-04-13T11:00:00Z"))));

        RequirementComplianceView result = service.buildComplianceView(loadTest);

        assertThat(result.evaluated()).isTrue();
        assertThat(result.compliant()).isTrue();
        assertThat(result.evaluatedAt()).isEqualTo("2026-04-13 15:00:00");
    }

    private String metricResponse(double... values) {
        StringBuilder builder = new StringBuilder("""
                {
                  "status": "success",
                  "data": {
                    "resultType": "matrix",
                    "result": [
                      {
                        "metric": {},
                        "values": [
                """);
        for (int index = 0; index < values.length; index++) {
            if (index > 0) {
                builder.append(',');
            }
            builder.append("[1710000000,\"").append(values[index]).append("\"]");
        }
        builder.append("""
                        ]
                      }
                    ]
                  }
                }
                """);
        return builder.toString();
    }
}
