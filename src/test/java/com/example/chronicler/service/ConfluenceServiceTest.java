package com.example.chronicler.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.chronicler.client.ConfluenceClient;
import com.example.chronicler.dto.confluence.ConfluenceChartsAppendRequest;
import com.example.chronicler.dto.confluence.ConfluenceChartsAppendResponse;
import com.example.chronicler.dto.confluence.ConfluenceCredentials;
import com.example.chronicler.dto.confluence.ConfluencePage;
import com.example.chronicler.dto.confluence.ConfluenceReportHierarchyResponse;
import com.example.chronicler.dto.confluence.ConfluenceReportRequest;
import com.example.chronicler.dto.confluence.ConfluenceReportResponse;
import com.example.chronicler.dto.grafana.GrafanaPanelSelection;
import com.example.chronicler.dto.grafana.GrafanaRenderRangeRequest;
import com.example.chronicler.dto.grafana.GrafanaRenderedPanel;
import com.example.chronicler.exception.ConfluenceClientException;
import com.example.chronicler.exception.ConfluenceOperationException;
import com.example.chronicler.model.ConfluenceTemplate;
import com.example.chronicler.model.ConfluenceTemplateChart;
import com.example.chronicler.model.KubernetesConfiguration;
import com.example.chronicler.model.LoadTest;
import com.example.chronicler.model.LoadTestType;
import com.example.chronicler.repository.LoadTestRepository;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;

@ExtendWith(MockitoExtension.class)
class ConfluenceServiceTest {

    @Mock
    private ConfluenceClient confluenceClient;

    @Mock
    private GrafanaService grafanaService;

    @Mock
    private TemplateLoader templateLoader;

    @Mock
    private LoadTestRepository loadTestRepository;

    @Mock
    private KubernetesConfigurationService kubernetesConfigurationService;

    @Mock
    private ConfluenceTemplateSettingsService confluenceTemplateSettingsService;

    @Mock
    private ConfigurationProvider configurationProvider;

    private ConfluenceService confluenceService;

    @BeforeEach
    void setUp() {
        confluenceService = new ConfluenceService(
                confluenceClient,
                grafanaService,
                templateLoader,
                loadTestRepository,
                kubernetesConfigurationService,
                confluenceTemplateSettingsService,
                configurationProvider);
        lenient().when(kubernetesConfigurationService.getConfigurations(any(UUID.class))).thenReturn(List.of());
        lenient().when(confluenceClient.buildPageUrl(anyString()))
                .thenAnswer(invocation -> "https://confluence.example/pages/viewpage.action?pageId="
                        + invocation.getArgument(0, String.class));
        lenient().when(configurationProvider.getRequiredValue("grafana", "image-width")).thenReturn("1200");
        lenient().when(configurationProvider.getRequiredValue("grafana", "image-height")).thenReturn("630");
    }

    @Test
    void createReportsCreatesPagesAndUploadsPanels() {
        ConfluenceCredentials credentials = new ConfluenceCredentials("user", "token");
        UUID firstTestUuid = UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee");
        UUID secondTestUuid = UUID.fromString("ffffffff-1111-2222-3333-444444444444");
        ConfluenceReportRequest request = new ConfluenceReportRequest(
                "123",
                null,
                List.of(
                        new ConfluenceReportRequest.ReportItem(firstTestUuid, 101),
                        new ConfluenceReportRequest.ReportItem(secondTestUuid, 202)));

        Instant firstStart = Instant.parse("2024-01-01T00:00:00Z");
        Instant firstEnd = Instant.parse("2024-01-01T01:00:00Z");
        Instant secondStart = Instant.parse("2024-01-02T00:00:00Z");
        Instant secondEnd = Instant.parse("2024-01-02T05:00:00Z");
        when(loadTestRepository.findById(firstTestUuid))
                .thenReturn(Optional.of(new LoadTest(firstTestUuid, firstStart, firstEnd, LoadTestType.MAX, null)));
        when(loadTestRepository.findById(secondTestUuid))
                .thenReturn(Optional.of(new LoadTest(secondTestUuid, secondStart, secondEnd, LoadTestType.SOAK, null)));

        byte[] firstImage = new byte[] {1, 2, 3};
        byte[] secondImage = new byte[] {4, 5, 6};
        GrafanaRenderedPanel firstPanel = new GrafanaRenderedPanel(
                7, "First panel", "", Base64.getEncoder().encodeToString(firstImage));
        GrafanaRenderedPanel secondPanel = new GrafanaRenderedPanel(
                9, "Second panel", "", Base64.getEncoder().encodeToString(secondImage));

        ConfluenceTemplate firstTemplate = new ConfluenceTemplate(
                101,
                "max",
                "Пользовательский MAX отчет",
                "Пользовательский MAX отчет",
                "<p>Max template</p>",
                List.of(new ConfluenceTemplateChart(1L, 101, "CPU", "uid-1", 7, 0)));
        ConfluenceTemplate secondTemplate = new ConfluenceTemplate(
                202,
                "soak",
                "Пользовательский SOAK отчет",
                "Пользовательский SOAK отчет",
                "<p>Soak template</p>",
                List.of(new ConfluenceTemplateChart(2L, 202, "Latency", "uid-2", 9, 0)));
        when(confluenceTemplateSettingsService.getActiveTemplate(101)).thenReturn(firstTemplate);
        when(confluenceTemplateSettingsService.getActiveTemplate(202)).thenReturn(secondTemplate);
        when(grafanaService.renderSelectedPanels(
                        firstStart,
                        firstEnd,
                        List.of(new GrafanaPanelSelection("uid-1", 7))))
                .thenReturn(List.of(firstPanel));
        when(grafanaService.renderSelectedPanels(
                        secondStart,
                        secondEnd,
                        List.of(new GrafanaPanelSelection("uid-2", 9))))
                .thenReturn(List.of(secondPanel));

        ConfluencePage parentPage = new ConfluencePage("123", "page", "Parent", 4, "", "SPACE");
        when(confluenceClient.getPage(credentials, "123")).thenReturn(parentPage);

        String firstTitle = firstTemplate.fullName();
        String secondTitle = secondTemplate.fullName();
        ConfluencePage firstCreatedPage =
                new ConfluencePage("456", "page", firstTitle, 1, "<p>Max template</p>", "SPACE");
        ConfluencePage secondCreatedPage =
                new ConfluencePage("789", "page", secondTitle, 1, "<p>Soak template</p>", "SPACE");
        when(confluenceClient.createChildPage(credentials, "123", "SPACE", firstTitle, "<p>Max template</p>"))
                .thenReturn(firstCreatedPage);
        when(confluenceClient.createChildPage(credentials, "123", "SPACE", secondTitle, "<p>Soak template</p>"))
                .thenReturn(secondCreatedPage);

        when(confluenceClient.uploadAttachment(eq(credentials), eq("456"), anyString(), eq(firstImage)))
                .thenReturn("grafana-panel-7.png");
        when(confluenceClient.uploadAttachment(eq(credentials), eq("789"), anyString(), eq(secondImage)))
                .thenReturn("grafana-panel-9.png");

        ConfluenceReportResponse response = confluenceService.createReports(credentials, request);

        assertThat(response.reports()).hasSize(2);
        ConfluenceReportResponse.ReportPage firstReport = response.reports().get(0);
        ConfluenceReportResponse.ReportPage secondReport = response.reports().get(1);
        assertThat(firstReport.testUuid()).isEqualTo(firstTestUuid);
        assertThat(firstReport.pageId()).isEqualTo("456");
        assertThat(firstReport.pageTitle()).isEqualTo(firstTitle);
        assertThat(firstReport.pageUrl()).isEqualTo("https://confluence.example/pages/viewpage.action?pageId=456");
        assertThat(firstReport.attachmentNames()).hasSize(1);
        assertThat(firstReport.attachmentNames().get(0)).contains("grafana-panel-7-");
        assertThat(secondReport.testUuid()).isEqualTo(secondTestUuid);
        assertThat(secondReport.pageId()).isEqualTo("789");
        assertThat(secondReport.pageTitle()).isEqualTo(secondTitle);
        assertThat(secondReport.pageUrl()).isEqualTo("https://confluence.example/pages/viewpage.action?pageId=789");
        assertThat(secondReport.attachmentNames()).hasSize(1);
        assertThat(secondReport.attachmentNames().get(0)).contains("grafana-panel-9-");

        verify(grafanaService).renderSelectedPanels(
                firstStart,
                firstEnd,
                List.of(new GrafanaPanelSelection("uid-1", 7)));
        verify(grafanaService).renderSelectedPanels(
                secondStart,
                secondEnd,
                List.of(new GrafanaPanelSelection("uid-2", 9)));

        verify(confluenceClient).createChildPage(credentials, "123", "SPACE", firstTitle, "<p>Max template</p>");
        verify(confluenceClient).createChildPage(credentials, "123", "SPACE", secondTitle, "<p>Soak template</p>");
        verify(confluenceClient).uploadAttachment(eq(credentials), eq("456"), anyString(), eq(firstImage));
        verify(confluenceClient).uploadAttachment(eq(credentials), eq("789"), anyString(), eq(secondImage));

        ArgumentCaptor<String> bodyCaptor = ArgumentCaptor.forClass(String.class);
        verify(confluenceClient).updatePage(eq(credentials), eq(firstCreatedPage), bodyCaptor.capture());
        String firstBody = bodyCaptor.getValue();
        assertThat(firstBody).contains("<p>Max template</p>");
        assertThat(firstBody).doesNotContain("<h3>CPU</h3>");

        verify(confluenceClient).updatePage(eq(credentials), eq(secondCreatedPage), bodyCaptor.capture());
        String secondBody = bodyCaptor.getValue();
        assertThat(secondBody).contains("<p>Soak template</p>");
        assertThat(secondBody).doesNotContain("<h3>Latency</h3>");
    }

    @Test
    void createReportsThrowsWhenTemplateMissing() {
        ConfluenceCredentials credentials = new ConfluenceCredentials("user", "token");
        UUID testUuid = UUID.fromString("ffffffff-1111-2222-3333-444444444444");
        ConfluenceReportRequest request = new ConfluenceReportRequest(
                "123",
                null,
                List.of(new ConfluenceReportRequest.ReportItem(testUuid, 777)));

        Instant start = Instant.parse("2024-01-03T00:00:00Z");
        Instant end = Instant.parse("2024-01-03T05:00:00Z");
        when(loadTestRepository.findById(testUuid))
                .thenReturn(Optional.of(new LoadTest(testUuid, start, end, LoadTestType.SOAK, null)));
        when(confluenceTemplateSettingsService.getActiveTemplate(777))
                .thenThrow(new IllegalArgumentException("Шаблон отчета не найден"));

        assertThatThrownBy(() -> confluenceService.createReports(credentials, request))
                .isInstanceOf(ConfluenceOperationException.class)
                .hasMessage("Шаблон отчета не найден");
    }

    @Test
    void createReportsThrowsWhenParentHasNoSpace() {
        ConfluenceCredentials credentials = new ConfluenceCredentials("user", "token");
        UUID testUuid = UUID.fromString("aaaaaaaa-0000-bbbb-cccc-dddddddddddd");
        ConfluenceReportRequest request = new ConfluenceReportRequest(
                "parent",
                null,
                List.of(new ConfluenceReportRequest.ReportItem(testUuid, 101)));

        Instant start = Instant.parse("2024-01-04T00:00:00Z");
        Instant end = Instant.parse("2024-01-04T01:00:00Z");
        when(loadTestRepository.findById(testUuid))
                .thenReturn(Optional.of(new LoadTest(testUuid, start, end, LoadTestType.MAX, null)));
        when(confluenceTemplateSettingsService.getActiveTemplate(101))
                .thenReturn(new ConfluenceTemplate(101, "max", "Max Report", "Max Report", "<p>Body</p>", List.of()));

        ConfluencePage parentPage = new ConfluencePage("parent", "page", "Parent", 2, "", null);
        when(confluenceClient.getPage(credentials, "parent")).thenReturn(parentPage);

        assertThatThrownBy(() -> confluenceService.createReports(credentials, request))
                .isInstanceOf(ConfluenceOperationException.class)
                .hasMessage("Parent page does not contain space key information");
    }

    @Test
    void createReportsThrowsWhenTemplateIsNotConfiguredForType() {
        ConfluenceCredentials credentials = new ConfluenceCredentials("user", "token");
        UUID testUuid = UUID.fromString("11111111-2222-3333-4444-555555555555");
        ConfluenceReportRequest request = new ConfluenceReportRequest("123", List.of(testUuid), null);

        Instant start = Instant.parse("2024-01-05T00:00:00Z");
        Instant end = Instant.parse("2024-01-05T01:00:00Z");
        when(loadTestRepository.findById(testUuid))
                .thenReturn(Optional.of(new LoadTest(testUuid, start, end, LoadTestType.PSU, null)));
        when(confluenceTemplateSettingsService.resolveDefaultTemplateShortName(LoadTestType.PSU))
                .thenThrow(new IllegalArgumentException(
                        "Confluence report template is not configured for test type psu"));

        assertThatThrownBy(() -> confluenceService.createReports(credentials, request))
                .isInstanceOf(ConfluenceOperationException.class)
                .hasMessage("Confluence report template is not configured for test type psu");
    }

    @Test
    void createReportsReplacesTemplateKeywordsInTitleAndBody() {
        ConfluenceCredentials credentials = new ConfluenceCredentials("user", "token");
        UUID testUuid = UUID.fromString("99999999-2222-3333-4444-555555555555");
        ConfluenceReportRequest request = new ConfluenceReportRequest(
                "123",
                null,
                List.of(new ConfluenceReportRequest.ReportItem(testUuid, 301)));

        Instant start = Instant.parse("2024-02-01T00:00:00Z");
        Instant end = Instant.parse("2024-02-01T02:00:00Z");
        when(loadTestRepository.findById(testUuid))
                .thenReturn(Optional.of(new LoadTest(testUuid, start, end, LoadTestType.MAX, null)));

        ConfluenceTemplate template = new ConfluenceTemplate(
                301,
                "max",
                "Шаблон MAX",
                "Отчет @release @config @graphics",
                "<p>@release</p><p>@config</p><p>@graphics</p>",
                List.of(new ConfluenceTemplateChart(11L, 301, "CPU", "uid-1", 7, 0)));
        when(confluenceTemplateSettingsService.getActiveTemplate(301)).thenReturn(template);

        when(kubernetesConfigurationService.getConfigurations(testUuid))
                .thenReturn(List.of(new KubernetesConfiguration(
                        1L,
                        testUuid,
                        "prod",
                        "dropapp",
                        "pod-1",
                        "container-1",
                        "1.0.0",
                        "24.1.7",
                        "100m",
                        "200m",
                        "256Mi",
                        "512Mi",
                        Instant.parse("2024-02-01T02:30:00Z"))));

        byte[] image = new byte[] {1, 9, 7};
        GrafanaRenderedPanel panel = new GrafanaRenderedPanel(
                7, "CPU", "", Base64.getEncoder().encodeToString(image));
        when(grafanaService.renderSelectedPanels(
                        start,
                        end,
                        List.of(new GrafanaPanelSelection("uid-1", 7))))
                .thenReturn(List.of(panel));

        ConfluencePage parentPage = new ConfluencePage("123", "page", "Parent", 4, "", "SPACE");
        ConfluencePage createdPage = new ConfluencePage("555", "page", "Отчет 24.1.7", 1, template.text(), "SPACE");
        when(confluenceClient.getPage(credentials, "123")).thenReturn(parentPage);
        when(confluenceClient.createChildPage(
                        credentials,
                        "123",
                        "SPACE",
                        "Отчет 24.1.7",
                        template.text()))
                .thenReturn(createdPage);
        when(confluenceClient.uploadAttachment(eq(credentials), eq("555"), anyString(), eq(image)))
                .thenReturn("grafana-panel-7.png");

        confluenceService.createReports(credentials, request);

        ArgumentCaptor<String> bodyCaptor = ArgumentCaptor.forClass(String.class);
        verify(confluenceClient).updatePage(eq(credentials), eq(createdPage), bodyCaptor.capture());
        String body = bodyCaptor.getValue();
        assertThat(body).contains("<p>24.1.7</p>");
        assertThat(body).contains("<table>");
        assertThat(body).contains("<td>24.1.7</td>");
        assertThat(body).contains("<h3>CPU</h3>");
        assertThat(body).doesNotContain("<h2>Конфигурация DropApp</h2>");
        assertThat(body).doesNotContain("<p><h2>");
        assertThat(body).doesNotContain("<p><h3>");
        assertThat(body).doesNotContain("@release");
        assertThat(body).doesNotContain("@config");
        assertThat(body).doesNotContain("@graphics");
    }

    @Test
    void createReportsNormalizesHtmlLineBreaksForConfluenceStorageFormat() {
        ConfluenceCredentials credentials = new ConfluenceCredentials("user", "token");
        UUID testUuid = UUID.fromString("88888888-2222-3333-4444-555555555555");
        ConfluenceReportRequest request = new ConfluenceReportRequest(
                "123",
                null,
                List.of(new ConfluenceReportRequest.ReportItem(testUuid, 302)));

        Instant start = Instant.parse("2024-02-01T00:00:00Z");
        Instant end = Instant.parse("2024-02-01T02:00:00Z");
        when(loadTestRepository.findById(testUuid))
                .thenReturn(Optional.of(new LoadTest(testUuid, start, end, LoadTestType.MAX, null)));
        when(kubernetesConfigurationService.getConfigurations(testUuid))
                .thenReturn(List.of(new KubernetesConfiguration(
                        1L,
                        testUuid,
                        "prod",
                        "dropapp",
                        "pod-1",
                        "container-1",
                        "1.0.0",
                        "24.1.7",
                        "100m",
                        "200m",
                        "256Mi",
                        "512Mi",
                        Instant.parse("2024-02-01T02:30:00Z"))));

        ConfluenceTemplate template = new ConfluenceTemplate(
                302,
                "max",
                "Шаблон MAX",
                "Отчет @release",
                "<p>Первая строка<br>Вторая строка</p><p>@release</p>",
                List.of());
        when(confluenceTemplateSettingsService.getActiveTemplate(302)).thenReturn(template);

        String normalizedTemplateBody = "<p>Первая строка<br />Вторая строка</p><p>@release</p>";
        ConfluencePage parentPage = new ConfluencePage("123", "page", "Parent", 4, "", "SPACE");
        ConfluencePage createdPage = new ConfluencePage("556", "page", "Отчет 24.1.7", 1, normalizedTemplateBody, "SPACE");
        when(confluenceClient.getPage(credentials, "123")).thenReturn(parentPage);
        when(confluenceClient.createChildPage(
                        credentials,
                        "123",
                        "SPACE",
                        "Отчет 24.1.7",
                        normalizedTemplateBody))
                .thenReturn(createdPage);

        confluenceService.createReports(credentials, request);

        verify(confluenceClient).createChildPage(
                credentials, "123", "SPACE", "Отчет 24.1.7", normalizedTemplateBody);
        ArgumentCaptor<String> bodyCaptor = ArgumentCaptor.forClass(String.class);
        verify(confluenceClient).updatePage(eq(credentials), eq(createdPage), bodyCaptor.capture());
        assertThat(bodyCaptor.getValue()).contains("<p>Первая строка<br />Вторая строка</p>");
        assertThat(bodyCaptor.getValue()).doesNotContain("<br>");
    }

    @Test
    void createReportsRetriesWithUniqueTitleWhenConfluenceRejectsDuplicatePageTitle() {
        ConfluenceCredentials credentials = new ConfluenceCredentials("user", "token");
        UUID testUuid = UUID.fromString("99999999-2222-3333-4444-555555555555");
        ConfluenceReportRequest request = new ConfluenceReportRequest(
                "123",
                null,
                List.of(new ConfluenceReportRequest.ReportItem(testUuid, 401)));

        Instant start = Instant.parse("2024-02-02T00:00:00Z");
        Instant end = Instant.parse("2024-02-02T02:00:00Z");
        when(loadTestRepository.findById(testUuid))
                .thenReturn(Optional.of(new LoadTest(testUuid, start, end, LoadTestType.MAX, null)));
        when(kubernetesConfigurationService.getConfigurations(testUuid))
                .thenReturn(List.of(new KubernetesConfiguration(
                        1L,
                        testUuid,
                        "prod",
                        "dropapp",
                        "pod-1",
                        "container-1",
                        "1.0.0",
                        "24.1.7",
                        "100m",
                        "200m",
                        "256Mi",
                        "512Mi",
                        Instant.parse("2024-02-02T02:30:00Z"))));

        ConfluenceTemplate template = new ConfluenceTemplate(
                401,
                "max",
                "Шаблон MAX",
                "Отчет @release",
                "<p>@release</p>",
                List.of());
        when(confluenceTemplateSettingsService.getActiveTemplate(401)).thenReturn(template);

        ConfluencePage parentPage = new ConfluencePage("123", "page", "Parent", 4, "", "SPACE");
        ConfluencePage createdPage = new ConfluencePage("777", "page", "Отчет 24.1.7 [99999999]", 1, template.text(), "SPACE");
        when(confluenceClient.getPage(credentials, "123")).thenReturn(parentPage);
        when(confluenceClient.createChildPage(
                        credentials,
                        "123",
                        "SPACE",
                        "Отчет 24.1.7",
                        template.text()))
                .thenThrow(duplicateTitleException("123"));
        when(confluenceClient.createChildPage(
                        credentials,
                        "123",
                        "SPACE",
                        "Отчет 24.1.7 [99999999]",
                        template.text()))
                .thenReturn(createdPage);

        ConfluenceReportResponse response = confluenceService.createReports(credentials, request);

        assertThat(response.reports()).hasSize(1);
        assertThat(response.reports().get(0).pageId()).isEqualTo("777");
        verify(confluenceClient).createChildPage(credentials, "123", "SPACE", "Отчет 24.1.7", template.text());
        verify(confluenceClient).createChildPage(
                credentials, "123", "SPACE", "Отчет 24.1.7 [99999999]", template.text());
        verify(confluenceClient).updatePage(eq(credentials), eq(createdPage), anyString());
    }

    @Test
    void createReportHierarchyCreatesParentAndChildrenUsingTemplates() {
        ConfluenceCredentials credentials = new ConfluenceCredentials("user", "token");
        ConfluencePage parentPage = new ConfluencePage("321", "page", "Parent", 3, "", "SPACE");
        when(confluenceClient.getPage(credentials, "321")).thenReturn(parentPage);

        when(templateLoader.loadTemplate("overall-report")).thenReturn("<p>Overview</p>");
        when(templateLoader.loadTemplate("load-report")).thenReturn("<p>Load</p>");
        when(templateLoader.loadTemplate("stress-report")).thenReturn("<p>Stress</p>");
        when(templateLoader.loadTemplate("soak")).thenReturn("<p>Soak</p>");

        ConfluencePage overviewPage = new ConfluencePage("111", "page", "Automated Test Report", 1, "", "SPACE");
        ConfluencePage loadPage = new ConfluencePage("222", "page", "Load", 1, "", "SPACE");
        ConfluencePage stressPage = new ConfluencePage("333", "page", "Stress", 1, "", "SPACE");
        ConfluencePage soakPage = new ConfluencePage("444", "page", "Soak", 1, "", "SPACE");

        when(confluenceClient.createChildPage(credentials, "321", "SPACE", "Automated Test Report", "<p>Overview</p>"))
                .thenReturn(overviewPage);
        when(confluenceClient.createChildPage(credentials, "111", "SPACE", "Load Test Summary", "<p>Load</p>"))
                .thenReturn(loadPage);
        when(confluenceClient.createChildPage(credentials, "111", "SPACE", "Stress Test Summary", "<p>Stress</p>"))
                .thenReturn(stressPage);
        when(confluenceClient.createChildPage(credentials, "111", "SPACE", "Soak Test Summary", "<p>Soak</p>"))
                .thenReturn(soakPage);

        ConfluenceReportHierarchyResponse response =
                confluenceService.createReportHierarchy(credentials, "321");

        assertThat(response.reportPageId()).isEqualTo("111");
        assertThat(response.childPageIds()).containsExactly("222", "333", "444");

        InOrder inOrder = org.mockito.Mockito.inOrder(confluenceClient);
        inOrder.verify(confluenceClient).getPage(credentials, "321");
        inOrder.verify(confluenceClient)
                .createChildPage(credentials, "321", "SPACE", "Automated Test Report", "<p>Overview</p>");
        inOrder.verify(confluenceClient)
                .createChildPage(credentials, "111", "SPACE", "Load Test Summary", "<p>Load</p>");
        inOrder.verify(confluenceClient)
                .createChildPage(credentials, "111", "SPACE", "Stress Test Summary", "<p>Stress</p>");
        inOrder.verify(confluenceClient)
                .createChildPage(credentials, "111", "SPACE", "Soak Test Summary", "<p>Soak</p>");
    }

    @Test
    void createReportHierarchyThrowsWhenParentHasNoSpace() {
        ConfluenceCredentials credentials = new ConfluenceCredentials("user", "token");
        ConfluencePage parentPage = new ConfluencePage("321", "page", "Parent", 3, "", null);
        when(confluenceClient.getPage(credentials, "321")).thenReturn(parentPage);

        assertThatThrownBy(() -> confluenceService.createReportHierarchy(credentials, "321"))
                .isInstanceOf(ConfluenceOperationException.class)
                .hasMessage("Parent page does not contain space key information");
    }

    @Test
    void appendChartsToPageUploadsChartsAndAppendsThemToExistingBody() {
        ConfluenceCredentials credentials = new ConfluenceCredentials("user", "token");
        ConfluenceChartsAppendRequest request = new ConfluenceChartsAppendRequest(
                "654",
                "2024-03-01T00:00:00Z",
                "2024-03-01T01:00:00Z",
                900);

        byte[] firstImage = new byte[] {4, 2};
        byte[] secondImage = new byte[] {9, 1};
        GrafanaRenderedPanel firstPanel = new GrafanaRenderedPanel(
                12,
                "CPU",
                "Описание CPU",
                Base64.getEncoder().encodeToString(firstImage));
        GrafanaRenderedPanel secondPanel = new GrafanaRenderedPanel(
                13,
                "Latency",
                "Описание latency",
                Base64.getEncoder().encodeToString(secondImage));

        ConfluencePage existingPage = new ConfluencePage("654", "page", "Target", 5, "<p>Existing body</p>", "SPACE");
        when(confluenceClient.getPage(credentials, "654")).thenReturn(existingPage);
        when(grafanaService.renderTemplatePanelsByRange(
                        new GrafanaRenderRangeRequest(request.from(), request.to(), request.templateId())))
                .thenReturn(List.of(firstPanel, secondPanel));
        when(confluenceClient.uploadAttachment(eq(credentials), eq("654"), anyString(), eq(firstImage)))
                .thenReturn("first.png");
        when(confluenceClient.uploadAttachment(eq(credentials), eq("654"), anyString(), eq(secondImage)))
                .thenReturn("second.png");

        ConfluenceChartsAppendResponse response = confluenceService.appendChartsToPage(credentials, request);

        assertThat(response.pageId()).isEqualTo("654");
        assertThat(response.pageTitle()).isEqualTo("Target");
        assertThat(response.pageUrl()).isEqualTo("https://confluence.example/pages/viewpage.action?pageId=654");
        assertThat(response.appendedChartsCount()).isEqualTo(2);
        assertThat(response.attachmentNames()).hasSize(2);

        verify(grafanaService).renderTemplatePanelsByRange(
                new GrafanaRenderRangeRequest(request.from(), request.to(), request.templateId()));
        verify(confluenceClient).uploadAttachment(eq(credentials), eq("654"), anyString(), eq(firstImage));
        verify(confluenceClient).uploadAttachment(eq(credentials), eq("654"), anyString(), eq(secondImage));

        ArgumentCaptor<String> bodyCaptor = ArgumentCaptor.forClass(String.class);
        verify(confluenceClient).updatePage(eq(credentials), eq(existingPage), bodyCaptor.capture());
        String updatedBody = bodyCaptor.getValue();
        assertThat(updatedBody).contains("<p>Existing body</p>");
        assertThat(updatedBody).contains("<hr />");
        assertThat(updatedBody).contains("<h3>CPU</h3>");
        assertThat(updatedBody).contains("<p>Описание CPU</p>");
        assertThat(updatedBody).contains("<h3>Latency</h3>");
        assertThat(updatedBody).contains("<p>Описание latency</p>");
        assertThat(updatedBody).contains("ri:filename=");
        assertThat(updatedBody).contains("ac:width=\"1200\"");
        assertThat(updatedBody).contains("ac:height=\"630\"");
    }

    private ConfluenceClientException duplicateTitleException(String parentPageId) {
        HttpClientErrorException cause = HttpClientErrorException.create(
                HttpStatus.BAD_REQUEST,
                "Bad Request",
                HttpHeaders.EMPTY,
                "{\"message\":\"A page with this title already exists\"}".getBytes(StandardCharsets.UTF_8),
                StandardCharsets.UTF_8);
        return new ConfluenceClientException(
                "Failed to create Confluence child page under "
                        + parentPageId
                        + ": HTTP 400 Bad Request: A page with this title already exists",
                cause);
    }
}
