package com.example.chronicler.service;

import com.example.chronicler.client.ConfluenceClient;
import com.example.chronicler.dto.confluence.ConfluenceCredentials;
import com.example.chronicler.dto.confluence.ConfluencePage;
import com.example.chronicler.dto.confluence.ConfluenceReportHierarchyResponse;
import com.example.chronicler.dto.confluence.ConfluenceReportRequest;
import com.example.chronicler.dto.confluence.ConfluenceReportResponse;
import com.example.chronicler.dto.grafana.GrafanaRenderRequest;
import com.example.chronicler.dto.grafana.GrafanaRenderResponse;
import com.example.chronicler.dto.grafana.GrafanaRenderedDashboard;
import com.example.chronicler.dto.grafana.GrafanaRenderedPanel;
import com.example.chronicler.exception.ConfluenceOperationException;
import com.example.chronicler.model.LoadTest;
import com.example.chronicler.model.LoadTestType;
import com.example.chronicler.repository.LoadTestRepository;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.util.HtmlUtils;

@Service
public class ConfluenceService {

    private static final ZoneId MOSCOW_ZONE = ZoneId.of("Europe/Moscow");
    private static final DateTimeFormatter PAGE_TIME_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(MOSCOW_ZONE);
    private final ConfluenceClient confluenceClient;
    private final GrafanaService grafanaService;
    private final TemplateLoader templateLoader;
    private final LoadTestRepository loadTestRepository;

    /**
     * Создаёт сервис для работы с Confluence, кешем рендеров Grafana и загрузкой шаблонов.
     */
    public ConfluenceService(
            ConfluenceClient confluenceClient,
            GrafanaService grafanaService,
            TemplateLoader templateLoader,
            LoadTestRepository loadTestRepository) {
        this.confluenceClient = confluenceClient;
        this.grafanaService = grafanaService;
        this.templateLoader = templateLoader;
        this.loadTestRepository = loadTestRepository;
    }

    private static final String OVERVIEW_TEMPLATE_NAME = "overall-report";
    private static final String OVERVIEW_PAGE_TITLE = "Automated Test Report";
    private static final List<SubReportTemplate> SUB_REPORT_TEMPLATES = List.of(
            new SubReportTemplate("load-report", "Load Test Summary"),
            new SubReportTemplate("stress-report", "Stress Test Summary"),
            new SubReportTemplate("soak", "Soak Test Summary"));

    /**
     * Создаёт страницы отчёта по каждому тесту и наполняет их шаблоном и графиками Grafana.
     */
    public ConfluenceReportResponse createReports(
            ConfluenceCredentials credentials, ConfluenceReportRequest request) {
        Objects.requireNonNull(credentials, "Confluence credentials must not be null");
        Objects.requireNonNull(request, "Confluence report request must not be null");

        validateCredentials(credentials);
        if (!StringUtils.hasText(request.parentPageId())) {
            throw new ConfluenceOperationException("parentPageId is required");
        }
        if (request.tests() == null || request.tests().isEmpty()) {
            throw new ConfluenceOperationException("tests must not be empty");
        }

        ConfluencePage parentPage = confluenceClient.getPage(credentials, request.parentPageId());
        if (!StringUtils.hasText(parentPage.spaceKey())) {
            throw new ConfluenceOperationException("Parent page does not contain space key information");
        }

        List<ConfluenceReportResponse.ReportPage> createdPages = new ArrayList<>();
        for (UUID testUuid : request.tests()) {
            if (testUuid == null) {
                throw new ConfluenceOperationException("tests must not contain null elements");
            }
            ConfluenceReportResponse.ReportPage reportPage =
                    createSingleReport(credentials, parentPage.id(), parentPage.spaceKey(), testUuid);
            createdPages.add(reportPage);
        }

        return new ConfluenceReportResponse(List.copyOf(createdPages));
    }

    /**
     * Создаёт иерархию страниц отчёта в Confluence, используя шаблоны для обзорной и дочерних страниц.
     */
    public ConfluenceReportHierarchyResponse createReportHierarchy(
            ConfluenceCredentials credentials, String parentPageId) {
        Objects.requireNonNull(credentials, "Confluence credentials must not be null");
        validateCredentials(credentials);
        if (!StringUtils.hasText(parentPageId)) {
            throw new ConfluenceOperationException("parentPageId is required");
        }

        ConfluencePage parentPage = confluenceClient.getPage(credentials, parentPageId);
        if (!StringUtils.hasText(parentPage.spaceKey())) {
            throw new ConfluenceOperationException(
                    "Parent page does not contain space key information");
        }

        String overviewBody = templateLoader.loadTemplate(OVERVIEW_TEMPLATE_NAME);
        ConfluencePage overviewPage = confluenceClient.createChildPage(
                credentials, parentPageId, parentPage.spaceKey(), OVERVIEW_PAGE_TITLE, overviewBody);

        List<String> childPageIds = new ArrayList<>();
        for (SubReportTemplate template : SUB_REPORT_TEMPLATES) {
            String body = templateLoader.loadTemplate(template.templateName());
            ConfluencePage childPage = confluenceClient.createChildPage(
                    credentials, overviewPage.id(), parentPage.spaceKey(), template.pageTitle(), body);
            childPageIds.add(childPage.id());
        }

        return new ConfluenceReportHierarchyResponse(overviewPage.id(), List.copyOf(childPageIds));
    }

    private ConfluenceReportResponse.ReportPage createSingleReport(
            ConfluenceCredentials credentials,
            String parentPageId,
            String spaceKey,
            UUID testUuid) {
        if (testUuid == null) {
            throw new ConfluenceOperationException("testUuid is required");
        }

        TestContext testContext = resolveTestContext(testUuid);
        LoadTest loadTest = testContext.loadTest();
        ConfluenceReportRequest.TestType testType = testContext.reportType();

        GrafanaRenderResponse renderResponse =
                grafanaService.renderPanels(new GrafanaRenderRequest(testUuid));
        List<GrafanaRenderedPanel> panels = findAllPanels(renderResponse);
        if (panels.isEmpty()) {
            throw new ConfluenceOperationException("Grafana render does not contain any panels");
        }

        String templateBody = templateLoader.loadTemplate(testType.templateName());
        ConfluencePage reportPage = confluenceClient.createChildPage(
                credentials, parentPageId, spaceKey, buildPageTitle(loadTest, testType), templateBody);

        StringBuilder updatedBody = new StringBuilder(getSafeBody(templateBody));
        if (updatedBody.length() > 0) {
            updatedBody.append("\n");
        }

        List<String> attachmentNames = new ArrayList<>();
        if (!panels.isEmpty()) {
            updatedBody.append("<h2>Данные мониторинга</h2>\n");
        }
        for (GrafanaRenderedPanel panel : panels) {
            byte[] imageBytes = decodePanelImage(panel);
            String attachmentName = generateAttachmentName(panel);
            confluenceClient.uploadAttachment(credentials, reportPage.id(), attachmentName, imageBytes);
            attachmentNames.add(attachmentName);
            appendPanelToBody(updatedBody, panel, attachmentName);
        }

        confluenceClient.updatePage(credentials, reportPage, updatedBody.toString());

        return new ConfluenceReportResponse.ReportPage(
                testUuid, reportPage.id(), List.copyOf(attachmentNames));
    }

    private TestContext resolveTestContext(UUID testUuid) {
        LoadTest loadTest = loadTestRepository.findById(testUuid)
                .orElseThrow(() -> new ConfluenceOperationException("Test with UUID " + testUuid + " not found"));
        LoadTestType loadTestType = loadTest.type();
        if (loadTestType == null || loadTestType == LoadTestType.UNKNOWN) {
            throw new ConfluenceOperationException("Test type for test " + testUuid + " is not defined");
        }
        ConfluenceReportRequest.TestType reportType = switch (loadTestType) {
            case MAX -> ConfluenceReportRequest.TestType.MAX;
            case CONFIRM -> ConfluenceReportRequest.TestType.CONFIRM;
            case SOAK -> ConfluenceReportRequest.TestType.SOAK;
            case UNKNOWN -> throw new ConfluenceOperationException("Test type for test " + testUuid + " is not defined");
        };
        return new TestContext(loadTest, reportType);
    }

    private String buildPageTitle(LoadTest loadTest, ConfluenceReportRequest.TestType testType) {
        String startTime = formatInstant(loadTest.start());
        if (StringUtils.hasText(startTime)) {
            return testType.displayName() + " — " + startTime;
        }
        return testType.displayName() + " — " + loadTest.id();
    }

    private String formatInstant(Instant instant) {
        if (instant == null) {
            return null;
        }
        return PAGE_TIME_FORMATTER.format(instant);
    }

    /**
     * Извлекает все панели из ответа Grafana, отбрасывая пустые значения.
     */
    private List<GrafanaRenderedPanel> findAllPanels(GrafanaRenderResponse response) {
        List<GrafanaRenderedPanel> panels = new ArrayList<>();
        if (response.dashboards() == null) {
            return panels;
        }
        for (GrafanaRenderedDashboard dashboard : response.dashboards()) {
            if (dashboard == null || dashboard.panels() == null) {
                continue;
            }
            for (GrafanaRenderedPanel panel : dashboard.panels()) {
                if (panel != null) {
                    panels.add(panel);
                }
            }
        }
        return panels;
    }

    /**
     * Декодирует изображение панели из Base64 и валидирует наличие данных.
     */
    private byte[] decodePanelImage(GrafanaRenderedPanel panel) {
        if (!StringUtils.hasText(panel.imageBase64())) {
            throw new ConfluenceOperationException("Cached Grafana panel image is empty");
        }
        try {
            return Base64.getDecoder().decode(panel.imageBase64());
        } catch (IllegalArgumentException ex) {
            throw new ConfluenceOperationException("Cached Grafana panel image could not be decoded", ex);
        }
    }

    /**
     * Формирует уникальное имя вложения для изображения панели.
     */
    private String generateAttachmentName(GrafanaRenderedPanel panel) {
        return "grafana-panel-" + panel.panelId() + "-" + UUID.randomUUID() + ".png";
    }

    /**
     * Добавляет в тело страницы блок с заголовком, описанием и изображением панели.
     */
    private void appendPanelToBody(StringBuilder bodyBuilder, GrafanaRenderedPanel panel, String attachmentName) {
        String title = StringUtils.hasText(panel.title()) ? panel.title() : "Панель без названия";
        bodyBuilder
                .append("<h3>")
                .append(HtmlUtils.htmlEscape(title))
                .append("</h3>");

        if (StringUtils.hasText(panel.description())) {
            bodyBuilder
                    .append("<p>")
                    .append(HtmlUtils.htmlEscape(panel.description()))
                    .append("</p>");
        }

        bodyBuilder
                .append("<p><ac:image><ri:attachment ri:filename=\"")
                .append(HtmlUtils.htmlEscape(attachmentName))
                .append("\" /></ac:image></p>");
    }

    /**
     * Возвращает существующее тело страницы или пустую строку, если тело отсутствует.
     */
    private String getSafeBody(String existingBody) {
        return existingBody != null ? existingBody : "";
    }

    /**
     * Проверяет корректность переданных учётных данных Confluence.
     */
    private void validateCredentials(ConfluenceCredentials credentials) {
        if (!StringUtils.hasText(credentials.accessToken())) {
            throw new ConfluenceOperationException("Confluence access token header must not be blank");
        }
    }

    private record SubReportTemplate(String templateName, String pageTitle) {}

    private record TestContext(LoadTest loadTest, ConfluenceReportRequest.TestType reportType) {}
}
