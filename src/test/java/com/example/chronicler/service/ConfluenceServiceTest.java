package com.example.chronicler.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.chronicler.client.ConfluenceClient;
import com.example.chronicler.dto.confluence.ConfluenceChartsAppendRequest;
import com.example.chronicler.dto.confluence.ConfluenceChartsAppendResponse;
import com.example.chronicler.dto.confluence.ConfluenceCredentials;
import com.example.chronicler.dto.confluence.ConfluencePage;
import com.example.chronicler.dto.confluence.ConfluencePageSummary;
import com.example.chronicler.dto.confluence.ConfluencePageTreeNode;
import com.example.chronicler.dto.confluence.ConfluenceReleaseReportPageRequest;
import com.example.chronicler.dto.confluence.ConfluenceReleaseReportPageResponse;
import com.example.chronicler.dto.confluence.ConfluenceReleaseReportLinksUpdateRequest;
import com.example.chronicler.dto.confluence.ConfluenceReleaseReportLinksUpdateResponse;
import com.example.chronicler.dto.confluence.ConfluenceReportHierarchyResponse;
import com.example.chronicler.dto.confluence.ConfluenceReportRequest;
import com.example.chronicler.dto.confluence.ConfluenceReportResponse;
import com.example.chronicler.dto.dropapp.DropAppConfigurationSnapshotDetailsDto;
import com.example.chronicler.dto.dropapp.JiraReleaseDistributionDto;
import com.example.chronicler.dto.grafana.GrafanaRenderRangeRequest;
import com.example.chronicler.dto.grafana.GrafanaRenderedPanel;
import com.example.chronicler.exception.ConfluenceClientException;
import com.example.chronicler.exception.ConfluenceOperationException;
import com.example.chronicler.model.ConfluenceReleaseReportLink;
import com.example.chronicler.model.ConfluenceTemplate;
import com.example.chronicler.model.ConfluenceTemplateChart;
import com.example.chronicler.model.ConfluenceTemplateType;
import com.example.chronicler.model.KubernetesConfiguration;
import com.example.chronicler.model.LoadTest;
import com.example.chronicler.model.LoadTestType;
import com.example.chronicler.repository.ConfluenceReleaseReportLinkRepository;
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
import org.springframework.http.MediaType;
import org.springframework.web.client.HttpClientErrorException;

@ExtendWith(MockitoExtension.class)
class ConfluenceServiceTest {

    private static final byte[] PNG_SIGNATURE =
            new byte[] {(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A};

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
    private ConfluenceReleaseReportLinkRepository releaseReportLinkRepository;

    @Mock
    private ConfigurationProvider configurationProvider;

    @Mock
    private ConfluenceRequestThrottler confluenceRequestThrottler;

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
                releaseReportLinkRepository,
                configurationProvider,
                confluenceRequestThrottler);
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
        LoadTest firstLoadTest = new LoadTest(firstTestUuid, firstStart, firstEnd, LoadTestType.MAX, null);
        LoadTest secondLoadTest = new LoadTest(secondTestUuid, secondStart, secondEnd, LoadTestType.SOAK, null);
        when(loadTestRepository.findById(firstTestUuid)).thenReturn(Optional.of(firstLoadTest));
        when(loadTestRepository.findById(secondTestUuid)).thenReturn(Optional.of(secondLoadTest));

        byte[] firstImage = pngBytes(1);
        byte[] secondImage = pngBytes(2);
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
        when(grafanaService.renderTemplatePanelsForTest(firstLoadTest, firstTemplate))
                .thenReturn(List.of(firstPanel));
        when(grafanaService.renderTemplatePanelsForTest(secondLoadTest, secondTemplate))
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
        assertThat(firstReport.attachmentNames().get(0)).isEqualTo("grafana-panel-7.png");
        assertThat(secondReport.testUuid()).isEqualTo(secondTestUuid);
        assertThat(secondReport.pageId()).isEqualTo("789");
        assertThat(secondReport.pageTitle()).isEqualTo(secondTitle);
        assertThat(secondReport.pageUrl()).isEqualTo("https://confluence.example/pages/viewpage.action?pageId=789");
        assertThat(secondReport.attachmentNames()).hasSize(1);
        assertThat(secondReport.attachmentNames().get(0)).isEqualTo("grafana-panel-9.png");

        verify(grafanaService).renderTemplatePanelsForTest(firstLoadTest, firstTemplate);
        verify(grafanaService).renderTemplatePanelsForTest(secondLoadTest, secondTemplate);

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
        LoadTest loadTest = new LoadTest(testUuid, start, end, LoadTestType.MAX, null);
        when(loadTestRepository.findById(testUuid)).thenReturn(Optional.of(loadTest));

        ConfluenceTemplate template = new ConfluenceTemplate(
                301,
                "max",
                "Шаблон MAX",
                "Отчет @release @jira_release_task @test_start_date @test_start_time @test_end_date @test_end_time @config @graphics",
                "<p>@release</p><p>@test_start</p><p>@test_start_date</p><p>@test_start_time</p>"
                        + "<p>@test_end</p><p>@test_end_date</p><p>@test_end_time</p>"
                        + "<p>@jira_release_task</p><p>@jira_release_link</p>"
                        + "<p>@jira_release_distr_table</p><p>@jira_release_tasks_required_lt</p>"
                        + "<p>@config</p><p>@graphics</p>",
                List.of(new ConfluenceTemplateChart(11L, 301, "CPU", "uid-1", 7, 0)));
        when(confluenceTemplateSettingsService.getActiveTemplate(301)).thenReturn(template);
        when(configurationProvider.findValue("jira", "project-name")).thenReturn(Optional.of("EPROFILE"));

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
        when(kubernetesConfigurationService.getConfigurationDetails(testUuid))
                .thenReturn(new DropAppConfigurationSnapshotDetailsDto(
                        55L,
                        "2024-02-01 05:30:00",
                        "24.1.7",
                        "EPROFILE-4151",
                        "https://jira.example/browse/EPROFILE-4151",
                        null,
                        List.of("prod"),
                        List.of("pods"),
                        List.of(),
                        List.of(new JiraReleaseDistributionDto(
                                "profile",
                                "https://dpm.example/service/profile",
                                "sp-02/017/00_5.profile",
                                "https://nexus.example/repository/sp-02/017/00_5.profile")),
                        List.of()));

        byte[] image = pngBytes(7);
        GrafanaRenderedPanel panel = new GrafanaRenderedPanel(
                7, "CPU", "", Base64.getEncoder().encodeToString(image));
        when(grafanaService.renderTemplatePanelsForTest(loadTest, template))
                .thenReturn(List.of(panel));

        ConfluencePage parentPage = new ConfluencePage("123", "page", "Parent", 4, "", "SPACE");
        ConfluencePage createdPage = new ConfluencePage(
                "555",
                "page",
                "Отчет 24.1.7 EPROFILE-4151 2024-02-01 03:00:00 2024-02-01 05:00:00",
                1,
                template.text(),
                "SPACE");
        when(confluenceClient.getPage(credentials, "123")).thenReturn(parentPage);
        when(confluenceClient.createChildPage(
                        credentials,
                        "123",
                        "SPACE",
                        "Отчет 24.1.7 EPROFILE-4151 2024-02-01 03:00:00 2024-02-01 05:00:00",
                        template.text()))
                .thenReturn(createdPage);
        when(confluenceClient.uploadAttachment(eq(credentials), eq("555"), anyString(), eq(image)))
                .thenReturn("grafana-panel-7.png");
        byte[] archive = new byte[] {4, 5, 6};
        when(kubernetesConfigurationService.createConfigurationArchive(testUuid))
                .thenReturn(new DropAppConfigurationSnapshotService.SnapshotArchive(
                        "dropapp-configuration-55.zip",
                        archive,
                        "application/zip"));
        when(confluenceClient.uploadAttachment(
                        eq(credentials),
                        eq("555"),
                        eq("dropapp-configuration-55.zip"),
                        eq(archive),
                        eq(MediaType.parseMediaType("application/zip"))))
                .thenReturn("dropapp-configuration-55.zip");

        ConfluenceReportResponse response = confluenceService.createReports(credentials, request);

        ArgumentCaptor<String> bodyCaptor = ArgumentCaptor.forClass(String.class);
        verify(confluenceClient).updatePage(eq(credentials), eq(createdPage), bodyCaptor.capture());
        String body = bodyCaptor.getValue();
        assertThat(body).contains("<p>24.1.7</p>");
        assertThat(body).contains("<p>2024-02-01 03:00:00</p>");
        assertThat(body).contains("<p>2024-02-01</p>");
        assertThat(body).contains("<p>03:00:00</p>");
        assertThat(body).contains("<p>2024-02-01 05:00:00</p>");
        assertThat(body).contains("<p>05:00:00</p>");
        assertThat(body).contains("<p>EPROFILE-4151</p>");
        assertThat(body).contains("<a href=\"https://jira.example/browse/EPROFILE-4151\">EPROFILE-4151</a>");
        assertThat(body).contains("<th>Сервис в DPM</th>");
        assertThat(body).contains("<th>Дистрибутив в Nexus</th>");
        assertThat(body).contains("<a href=\"https://dpm.example/service/profile\">profile</a>");
        assertThat(body).contains("<a href=\"https://nexus.example/repository/sp-02/017/00_5.profile\">sp-02/017/00_5.profile</a>");
        assertThat(body).contains("<ac:structured-macro ac:name=\"jira\">");
        assertThat(body).contains("<ac:parameter ac:name=\"columns\">key,summary,type,created,updated,assignee,priority,components,status,resolution</ac:parameter>");
        assertThat(body).contains("project = &quot;EPROFILE&quot; AND issuetype in (Story, Bug)");
        assertThat(body).contains("affectedVersion = 24.1.7 OR fixVersion = 24.1.7 OR labels = Id24.1.7");
        assertThat(body).contains("&quot;Требуется НТ&quot; in (Да)");
        assertThat(body).contains("<ac:parameter ac:name=\"maximumIssues\">20</ac:parameter>");
        assertThat(body).contains("<ac:structured-macro ac:name=\"expand\">");
        assertThat(body).contains("<ac:parameter ac:name=\"title\">Конфигурация стенда</ac:parameter>");
        assertThat(body).contains("ri:attachment ri:filename=\"dropapp-configuration-55.zip\"");
        assertThat(body).contains("Скачать ZIP с полной конфигурацией");
        assertThat(body).contains("<table>");
        assertThat(body).contains("<td>24.1.7</td>");
        assertThat(body).contains("<h3>CPU</h3>");
        assertThat(body).doesNotContain("<h2>Конфигурация стенда</h2>");
        assertThat(body).doesNotContain("<p><h2>");
        assertThat(body).doesNotContain("<p><h3>");
        assertThat(body).doesNotContain("@release");
        assertThat(body).doesNotContain("@test_start");
        assertThat(body).doesNotContain("@test_start_date");
        assertThat(body).doesNotContain("@test_start_time");
        assertThat(body).doesNotContain("@test_end");
        assertThat(body).doesNotContain("@test_end_date");
        assertThat(body).doesNotContain("@test_end_time");
        assertThat(body).doesNotContain("@jira_release_task");
        assertThat(body).doesNotContain("@jira_release_link");
        assertThat(body).doesNotContain("@jira_release_distr_table");
        assertThat(body).doesNotContain("@jira_release_tasks_required_lt");
        assertThat(body).doesNotContain("@config");
        assertThat(body).doesNotContain("@graphics");
        assertThat(response.reports().get(0).attachmentNames())
                .contains("dropapp-configuration-55.zip");
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
    void getReportParentPageTreeBuildsFreshTreeFromCurrentChildPages() {
        ConfluenceCredentials credentials = new ConfluenceCredentials("user", "token");
        when(configurationProvider.findValue("confluence", "reports-parent-page-id")).thenReturn(Optional.of(" root-1 "));
        when(confluenceClient.getPage(credentials, "root-1"))
                .thenReturn(new ConfluencePage("root-1", "page", "Отчеты", 3, "", "SPACE"));
        when(confluenceClient.listChildPages(credentials, "root-1"))
                .thenReturn(List.of(
                        new ConfluencePageSummary("release-1", "Релиз 1"),
                        new ConfluencePageSummary("release-2", "Релиз 2")));
        when(confluenceClient.listChildPages(credentials, "release-1"))
                .thenReturn(List.of(new ConfluencePageSummary("test-1", "Тест 1")));
        when(confluenceClient.listChildPages(credentials, "test-1")).thenReturn(List.of());
        when(confluenceClient.listChildPages(credentials, "release-2")).thenReturn(List.of());

        ConfluencePageTreeNode response = confluenceService.getReportParentPageTree(credentials);

        assertThat(response.id()).isEqualTo("root-1");
        assertThat(response.children()).extracting(ConfluencePageTreeNode::id)
                .containsExactly("release-1", "release-2");
        assertThat(response.children().get(0).children()).extracting(ConfluencePageTreeNode::id)
                .containsExactly("test-1");
        verify(confluenceClient, never()).listPageDescendants(credentials, "root-1");
        verify(confluenceRequestThrottler, times(5)).awaitReportParentPagesRequestSlot();
    }

    @Test
    void appendChartsToPageUploadsChartsAndAppendsThemToExistingBody() {
        ConfluenceCredentials credentials = new ConfluenceCredentials("user", "token");
        ConfluenceChartsAppendRequest request = new ConfluenceChartsAppendRequest(
                "654",
                "2024-03-01T00:00:00Z",
                "2024-03-01T01:00:00Z",
                900);

        byte[] firstImage = pngBytes(12);
        byte[] secondImage = pngBytes(13);
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
        assertThat(response.attachmentNames()).containsExactly("first.png", "second.png");

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
        assertThat(updatedBody).contains("ri:attachment ri:filename=\"first.png\"");
        assertThat(updatedBody).contains("ri:attachment ri:filename=\"second.png\"");
        assertThat(updatedBody).contains("ac:width=\"1200\"");
        assertThat(updatedBody).contains("ac:height=\"630\"");
    }

    @Test
    void createReleaseReportPageHidesTechnicalReportMarkers() {
        ConfluenceCredentials credentials = new ConfluenceCredentials("user", "token");
        UUID testUuid = UUID.fromString("aaaaaaaa-1111-2222-3333-bbbbbbbbbbbb");
        ConfluenceReleaseReportPageRequest request =
                new ConfluenceReleaseReportPageRequest("parent-1", null, List.of(testUuid));
        ConfluencePage parentPage = new ConfluencePage("parent-1", "page", "Релизы", 1, "<p>Parent</p>", "SPACE");
        ConfluencePage createdPage =
                new ConfluencePage("release-1", "page", "Отчет по релизу 24.1.7", 1, "", "SPACE");
        ConfluenceTemplate template = new ConfluenceTemplate(
                42,
                "release_template",
                "Отчет по релизу",
                "Отчет по релизу @release",
                ConfluenceTemplateType.RELEASE,
                null,
                "<h2>7. Результаты тестирования</h2>"
                        + "<p>@perfhub_reports_start</p>"
                        + "<h3>@test_number @test_title</h3>"
                        + "<p>@test_status</p>"
                        + "<p>@perfhub_reports_end</p>",
                List.of());

        when(confluenceClient.getPage(credentials, "parent-1")).thenReturn(parentPage);
        when(confluenceClient.getPage(credentials, "release-1")).thenReturn(createdPage);
        when(confluenceTemplateSettingsService.resolveReleaseTemplateShortName()).thenReturn("release_template");
        when(confluenceTemplateSettingsService.findActiveTemplateByShortName("release_template"))
                .thenReturn(Optional.of(template));
        when(kubernetesConfigurationService.getConfigurations(testUuid))
                .thenReturn(List.of(new KubernetesConfiguration(
                        1L,
                        testUuid,
                        "prod",
                        "profile",
                        "profile-1",
                        "profile",
                        "1.0.0",
                        "24.1.7",
                        "100m",
                        "200m",
                        "256Mi",
                        "512Mi",
                        Instant.parse("2024-05-01T11:05:00Z"))));
        when(confluenceClient.createChildPage(
                        eq(credentials),
                        eq("parent-1"),
                        eq("SPACE"),
                        eq("Отчет по релизу 24.1.7"),
                        anyString()))
                .thenReturn(createdPage);

        ConfluenceReleaseReportPageResponse response = confluenceService.createReleaseReportPage(credentials, request);

        assertThat(response.pageId()).isEqualTo("release-1");
        ArgumentCaptor<String> bodyCaptor = ArgumentCaptor.forClass(String.class);
        verify(confluenceClient).createChildPage(
                eq(credentials),
                eq("parent-1"),
                eq("SPACE"),
                eq("Отчет по релизу 24.1.7"),
                bodyCaptor.capture());
        String body = bodyCaptor.getValue();
        assertThat(body).contains("<p style=\"display: none;\">@perfhub_reports_start</p>");
        assertThat(body).contains("<p style=\"display: none;\">@perfhub_reports_end</p>");
        assertThat(body).doesNotContain("<p>@perfhub_reports_start</p>");
        assertThat(body).doesNotContain("<p>@perfhub_reports_end</p>");
    }

    @Test
    void createReleaseReportPageReplacesRequiredLtPlaceholderWithJiraMacro() {
        ConfluenceCredentials credentials = new ConfluenceCredentials("user", "token");
        UUID testUuid = UUID.fromString("bbbbbbbb-1111-2222-3333-cccccccccccc");
        ConfluenceReleaseReportPageRequest request =
                new ConfluenceReleaseReportPageRequest("parent-1", null, List.of(testUuid));
        ConfluencePage parentPage = new ConfluencePage("parent-1", "page", "Релизы", 1, "<p>Parent</p>", "SPACE");
        ConfluencePage createdPage =
                new ConfluencePage("release-lt", "page", "Отчет по релизу 2.17.0", 1, "", "SPACE");
        ConfluenceTemplate template = new ConfluenceTemplate(
                43,
                "release_template",
                "Отчет по релизу",
                "Отчет по релизу @release @jira_release_tasks_required_lt",
                ConfluenceTemplateType.RELEASE,
                null,
                "<h2>3.2 Пользовательские истории и дефекты релиза с отметкой \"Требуется НТ = Да\"</h2>"
                        + "<p>@jira_release_tasks_required_lt</p>",
                List.of());

        when(confluenceClient.getPage(credentials, "parent-1")).thenReturn(parentPage);
        when(confluenceClient.getPage(credentials, "release-lt")).thenReturn(createdPage);
        when(confluenceTemplateSettingsService.resolveReleaseTemplateShortName()).thenReturn("release_template");
        when(confluenceTemplateSettingsService.findActiveTemplateByShortName("release_template"))
                .thenReturn(Optional.of(template));
        when(configurationProvider.findValue("jira", "project-name")).thenReturn(Optional.of("EPROFILE"));
        when(kubernetesConfigurationService.getConfigurations(testUuid))
                .thenReturn(List.of(new KubernetesConfiguration(
                        1L,
                        testUuid,
                        "prod",
                        "profile",
                        "profile-1",
                        "profile",
                        "1.0.0",
                        "2.17.0",
                        "100m",
                        "200m",
                        "256Mi",
                        "512Mi",
                        Instant.parse("2024-05-01T11:05:00Z"))));
        when(confluenceClient.createChildPage(
                        eq(credentials),
                        eq("parent-1"),
                        eq("SPACE"),
                        eq("Отчет по релизу 2.17.0"),
                        anyString()))
                .thenReturn(createdPage);

        confluenceService.createReleaseReportPage(credentials, request);

        ArgumentCaptor<String> bodyCaptor = ArgumentCaptor.forClass(String.class);
        verify(confluenceClient).createChildPage(
                eq(credentials),
                eq("parent-1"),
                eq("SPACE"),
                eq("Отчет по релизу 2.17.0"),
                bodyCaptor.capture());
        String body = bodyCaptor.getValue();
        assertThat(body).contains("<ac:structured-macro ac:name=\"jira\">");
        assertThat(body).contains("<ac:parameter ac:name=\"columns\">key,summary,type,created,updated,assignee,priority,components,status,resolution</ac:parameter>");
        assertThat(body).contains("project = &quot;EPROFILE&quot; AND issuetype in (Story, Bug)");
        assertThat(body).contains("affectedVersion = 2.17.0 OR fixVersion = 2.17.0 OR labels = Id2.17");
        assertThat(body).contains("&quot;Требуется НТ&quot; in (Да) ORDER BY priority, project, affectedVersion");
        assertThat(body).contains("<ac:parameter ac:name=\"maximumIssues\">20</ac:parameter>");
        assertThat(body).doesNotContain("@jira_release_tasks_required_lt");
    }

    @Test
    void updateReleaseReportLinksExpandsTemplateBlockAndUpdatesPage() {
        ConfluenceCredentials credentials = new ConfluenceCredentials("user", "token");
        UUID testUuid = UUID.fromString("aaaaaaaa-1111-2222-3333-bbbbbbbbbbbb");
        ConfluenceReleaseReportLinksUpdateRequest request = new ConfluenceReleaseReportLinksUpdateRequest(
                "release-1",
                List.of(new ConfluenceReleaseReportLinksUpdateRequest.ReportLinkItem(
                        testUuid,
                        "7.1",
                        "Определение максимальной производительности Profile @release",
                        "test-page-1",
                        "Отчет по тесту",
                        "https://confluence.example/pages/viewpage.action?pageId=test-page-1")));

        LoadTest loadTest = new LoadTest(
                testUuid,
                Instant.parse("2024-05-01T10:00:00Z"),
                Instant.parse("2024-05-01T11:00:00Z"),
                LoadTestType.MAX,
                null,
                "Тест прошел успешно.",
                "Максимальная производительность составила 600 запросов в секунду.");
        when(loadTestRepository.findById(testUuid)).thenReturn(Optional.of(loadTest));
        when(kubernetesConfigurationService.getConfigurations(testUuid))
                .thenReturn(List.of(new KubernetesConfiguration(
                        1L,
                        testUuid,
                        "prod",
                        "profile",
                        "profile-1",
                        "profile",
                        "1.0.0",
                        "24.1.7",
                        "100m",
                        "200m",
                        "256Mi",
                        "512Mi",
                        Instant.parse("2024-05-01T11:05:00Z"))));

        ConfluencePage releasePage = new ConfluencePage(
                "release-1",
                "page",
                "Отчет по релизу",
                3,
                "<h2>7. Результаты тестирования</h2>"
                        + "<p>@perfhub_reports_start</p>"
                        + "<h3>@test_number @test_title</h3>"
                        + "<p>@test_start_date @test_start_time - @test_end_date @test_end_time</p>"
                        + "<p>@test_status</p>"
                        + "<p>@test_summary</p>"
                        + "<p>Подробные результаты: @test_report_link</p>"
                        + "<p>@perfhub_reports_end</p>",
                "SPACE");
        when(confluenceClient.getPage(credentials, "release-1")).thenReturn(releasePage);
        when(releaseReportLinkRepository.findBlockTemplate("release-1")).thenReturn(Optional.empty());
        when(releaseReportLinkRepository.findByReleasePageId("release-1"))
                .thenReturn(List.of(new ConfluenceReleaseReportLink(
                        1L,
                        "release-1",
                        testUuid,
                        "test-page-1",
                        "Отчет по тесту",
                        "https://confluence.example/pages/viewpage.action?pageId=test-page-1",
                        "7.1",
                        "Определение максимальной производительности Profile @release",
                        "Максимум",
                        "2024-05-01 13:00:00 - 2024-05-01 14:00:00",
                        "Тест прошел успешно.",
                        "Максимальная производительность составила 600 запросов в секунду.",
                        Instant.parse("2024-05-01T11:05:00Z"))));

        ConfluenceReleaseReportLinksUpdateResponse response =
                confluenceService.updateReleaseReportLinks(credentials, request);

        assertThat(response.updated()).isTrue();
        assertThat(response.reportsCount()).isEqualTo(1);
        verify(releaseReportLinkRepository).saveBlockTemplate(
                eq("release-1"),
                eq("<h3>@test_number @test_title</h3>"
                        + "<p>@test_start_date @test_start_time - @test_end_date @test_end_time</p>"
                        + "<p>@test_status</p>"
                        + "<p>@test_summary</p>"
                        + "<p>Подробные результаты: @test_report_link</p>"));

        ArgumentCaptor<String> bodyCaptor = ArgumentCaptor.forClass(String.class);
        verify(confluenceClient).updatePage(eq(credentials), eq(releasePage), bodyCaptor.capture());
        String updatedBody = bodyCaptor.getValue();
        assertThat(updatedBody).contains("<p style=\"display: none;\">@perfhub_reports_start</p>");
        assertThat(updatedBody).contains("<p style=\"display: none;\">@perfhub_reports_end</p>");
        assertThat(updatedBody).doesNotContain("<p>@perfhub_reports_start</p>");
        assertThat(updatedBody).doesNotContain("<p>@perfhub_reports_end</p>");
        assertThat(updatedBody).contains("<h3>7.1 Определение максимальной производительности Profile 24.1.7</h3>");
        assertThat(updatedBody).contains("<p>2024-05-01 13:00:00 - 2024-05-01 14:00:00</p>");
        assertThat(updatedBody).contains("<p>Тест прошел успешно.</p>");
        assertThat(updatedBody).contains("<p>Максимальная производительность составила 600 запросов в секунду.</p>");
        assertThat(updatedBody).contains("<ri:page ri:content-title=\"Отчет по тесту\" ri:space-key=\"SPACE\" />");
        assertThat(updatedBody).doesNotContain("@release");
        assertThat(updatedBody).doesNotContain("ri:value=\"https://confluence.example/pages/viewpage.action?pageId=test-page-1\"");
        assertThat(updatedBody).doesNotContain("@test_number");
        assertThat(updatedBody).doesNotContain("@test_start_date");
        assertThat(updatedBody).doesNotContain("@test_start_time");
        assertThat(updatedBody).doesNotContain("@test_end_date");
        assertThat(updatedBody).doesNotContain("@test_end_time");
        assertThat(updatedBody).doesNotContain("@test_report_link");
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

    private static byte[] pngBytes(int marker) {
        byte[] bytes = new byte[PNG_SIGNATURE.length + 1];
        System.arraycopy(PNG_SIGNATURE, 0, bytes, 0, PNG_SIGNATURE.length);
        bytes[PNG_SIGNATURE.length] = (byte) marker;
        return bytes;
    }
}
