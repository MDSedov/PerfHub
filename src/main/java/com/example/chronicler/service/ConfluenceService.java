package com.example.chronicler.service;

import com.example.chronicler.client.ConfluenceClient;
import com.example.chronicler.dto.confluence.ConfluenceCredentials;
import com.example.chronicler.dto.confluence.ConfluencePage;
import com.example.chronicler.dto.confluence.ConfluenceReportHierarchyResponse;
import com.example.chronicler.dto.confluence.ConfluenceReportRequest;
import com.example.chronicler.dto.confluence.ConfluenceReportResponse;
import com.example.chronicler.dto.grafana.GrafanaPanelSelection;
import com.example.chronicler.dto.grafana.GrafanaRenderedPanel;
import com.example.chronicler.exception.ConfluenceOperationException;
import com.example.chronicler.model.ConfluenceTemplate;
import com.example.chronicler.model.ConfluenceTemplateChart;
import com.example.chronicler.model.KubernetesConfiguration;
import com.example.chronicler.model.LoadTest;
import com.example.chronicler.model.LoadTestType;
import com.example.chronicler.repository.LoadTestRepository;
import com.example.chronicler.util.ReleaseVersionUtils;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.Objects;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.util.HtmlUtils;

@Service
public class ConfluenceService {

    private static final String RELEASE_PLACEHOLDER = "@release";
    private static final String CONFIG_PLACEHOLDER = "@config";
    private static final String GRAPHICS_PLACEHOLDER = "@graphics";
    private static final String PLACEHOLDER_BLOCK_TAGS =
            "p|div|section|article|aside|blockquote|li|h1|h2|h3|h4|h5|h6";
    private static final String PLACEHOLDER_PADDING_PATTERN = "(?:\\s|&nbsp;|&#160;|<br\\s*/?>)*";

    private final ConfluenceClient confluenceClient;
    private final GrafanaService grafanaService;
    private final TemplateLoader templateLoader;
    private final LoadTestRepository loadTestRepository;
    private final KubernetesConfigurationService kubernetesConfigurationService;
    private final ConfluenceTemplateSettingsService confluenceTemplateSettingsService;

    /**
     * Создаёт сервис для работы с Confluence, кешем рендеров Grafana и загрузкой шаблонов.
     */
    public ConfluenceService(
            ConfluenceClient confluenceClient,
            GrafanaService grafanaService,
            TemplateLoader templateLoader,
            LoadTestRepository loadTestRepository,
            KubernetesConfigurationService kubernetesConfigurationService,
            ConfluenceTemplateSettingsService confluenceTemplateSettingsService) {
        this.confluenceClient = confluenceClient;
        this.grafanaService = grafanaService;
        this.templateLoader = templateLoader;
        this.loadTestRepository = loadTestRepository;
        this.kubernetesConfigurationService = kubernetesConfigurationService;
        this.confluenceTemplateSettingsService = confluenceTemplateSettingsService;
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
        List<ResolvedReportItem> reportItems = resolveReportItems(request);

        ConfluencePage parentPage = confluenceClient.getPage(credentials, request.parentPageId());
        if (!StringUtils.hasText(parentPage.spaceKey())) {
            throw new ConfluenceOperationException("Parent page does not contain space key information");
        }

        List<ConfluenceReportResponse.ReportPage> createdPages = new ArrayList<>();
        for (ResolvedReportItem reportItem : reportItems) {
            ConfluenceReportResponse.ReportPage reportPage = createSingleReport(
                    credentials,
                    parentPage.id(),
                    parentPage.spaceKey(),
                    reportItem.loadTest(),
                    reportItem.template());
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
            LoadTest loadTest,
            ConfluenceTemplate template) {
        UUID testUuid = loadTest.id();
        List<ConfluenceTemplateChart> templateCharts = template.charts() == null ? List.of() : template.charts();
        List<KubernetesConfiguration> configurations = kubernetesConfigurationService.getConfigurations(testUuid);
        String releaseValue = resolveReleaseValue(configurations);
        List<GrafanaRenderedPanel> renderedPanels = grafanaService.renderSelectedPanels(
                loadTest.start(),
                loadTest.end(),
                templateCharts.stream()
                        .map(chart -> new GrafanaPanelSelection(chart.dashboardUid(), chart.panelId()))
                        .toList());
        if (renderedPanels.size() != templateCharts.size()) {
            throw new ConfluenceOperationException("Не удалось подготовить все графики для выбранного шаблона");
        }

        String configurationSection = buildConfigurationSection(configurations);
        String pageTitle = buildReportTitle(template, releaseValue);
        String templateBody = template.text();
        ConfluencePage reportPage = confluenceClient.createChildPage(
                credentials, parentPageId, spaceKey, pageTitle, templateBody);

        List<String> attachmentNames = new ArrayList<>();
        List<RenderedChartAttachment> chartAttachments = new ArrayList<>();
        for (int index = 0; index < templateCharts.size(); index++) {
            ConfluenceTemplateChart chart = templateCharts.get(index);
            GrafanaRenderedPanel panel = renderedPanels.get(index);
            byte[] imageBytes = decodePanelImage(panel);
            String attachmentName = generateAttachmentName(panel);
            confluenceClient.uploadAttachment(credentials, reportPage.id(), attachmentName, imageBytes);
            attachmentNames.add(attachmentName);
            chartAttachments.add(new RenderedChartAttachment(chart.title(), attachmentName));
        }

        String updatedBody = buildReportBody(
                template.text(),
                releaseValue,
                configurationSection,
                buildChartsSection(chartAttachments));
        confluenceClient.updatePage(credentials, reportPage, updatedBody);

        return new ConfluenceReportResponse.ReportPage(
                testUuid, reportPage.id(), List.copyOf(attachmentNames));
    }

    private List<ResolvedReportItem> resolveReportItems(ConfluenceReportRequest request) {
        if (request.items() != null && !request.items().isEmpty()) {
            return request.items().stream()
                    .map(this::resolveExplicitReportItem)
                    .toList();
        }
        if (request.tests() == null || request.tests().isEmpty()) {
            throw new ConfluenceOperationException("tests must not be empty");
        }
        return request.tests().stream()
                .map(this::resolveLegacyReportItem)
                .toList();
    }

    private ResolvedReportItem resolveExplicitReportItem(ConfluenceReportRequest.ReportItem reportItem) {
        if (reportItem == null || reportItem.testUuid() == null) {
            throw new ConfluenceOperationException("testUuid is required");
        }
        if (reportItem.templateId() == null) {
            throw new ConfluenceOperationException("templateId is required");
        }
        LoadTest loadTest = resolveLoadTest(reportItem.testUuid());
        ConfluenceTemplate template;
        try {
            template = confluenceTemplateSettingsService.getActiveTemplate(reportItem.templateId());
        } catch (IllegalArgumentException ex) {
            throw new ConfluenceOperationException(ex.getMessage(), ex);
        }
        return new ResolvedReportItem(loadTest, template);
    }

    private ResolvedReportItem resolveLegacyReportItem(UUID testUuid) {
        if (testUuid == null) {
            throw new ConfluenceOperationException("tests must not contain null elements");
        }
        LoadTest loadTest = resolveLoadTest(testUuid);
        return new ResolvedReportItem(loadTest, resolveDefaultTemplate(loadTest));
    }

    private LoadTest resolveLoadTest(UUID testUuid) {
        LoadTest loadTest = loadTestRepository.findById(testUuid)
                .orElseThrow(() -> new ConfluenceOperationException("Test with UUID " + testUuid + " not found"));
        return loadTest;
    }

    private ConfluenceTemplate resolveDefaultTemplate(LoadTest loadTest) {
        LoadTestType loadTestType = loadTest.type();
        if (loadTestType == null || loadTestType == LoadTestType.UNKNOWN) {
            throw new ConfluenceOperationException("Test type for test " + loadTest.id() + " is not defined");
        }
        try {
            String shortName = confluenceTemplateSettingsService.resolveDefaultTemplateShortName(loadTestType);
            return confluenceTemplateSettingsService.findActiveTemplateByShortName(shortName)
                    .orElseThrow(() -> new ConfluenceOperationException(
                            "Confluence report template is not configured for test type " + loadTestType.value()));
        } catch (IllegalArgumentException ex) {
            throw new ConfluenceOperationException(ex.getMessage(), ex);
        }
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
    private void appendPanelToBody(StringBuilder bodyBuilder, String title, String attachmentName) {
        String resolvedTitle = StringUtils.hasText(title) ? title : "Панель без названия";
        bodyBuilder
                .append("<h3>")
                .append(HtmlUtils.htmlEscape(resolvedTitle))
                .append("</h3>");

        bodyBuilder
                .append("<p><ac:image><ri:attachment ri:filename=\"")
                .append(HtmlUtils.htmlEscape(attachmentName))
                .append("\" /></ac:image></p>");
    }

    private String buildConfigurationSection(List<KubernetesConfiguration> configurations) {
        StringBuilder bodyBuilder = new StringBuilder();
        bodyBuilder.append("<h2>Конфигурация DropApp</h2>\n");
        if (configurations.isEmpty()) {
            bodyBuilder.append("<p>Конфигурация DropApp для данного теста отсутствует.</p>\n");
            return bodyBuilder.toString();
        }
        bodyBuilder.append("<table>\n")
                .append("<tr>")
                .append("<th>POD</th>")
                .append("<th>Контейнер</th>")
                .append("<th>Версия</th>")
                .append("<th>Релиз</th>")
                .append("<th>Request CPU</th>")
                .append("<th>Limit CPU</th>")
                .append("<th>Request RAM</th>")
                .append("<th>Limit RAM</th>")
                .append("</tr>\n");
        for (KubernetesConfiguration config : configurations) {
            bodyBuilder.append("<tr>")
                    .append("<td>").append(escape(config.podName())).append("</td>")
                    .append("<td>").append(escape(config.containerName())).append("</td>")
                    .append("<td>").append(escape(config.version())).append("</td>")
                    .append("<td>").append(escape(config.release())).append("</td>")
                    .append("<td>").append(escape(config.requestCpu())).append("</td>")
                    .append("<td>").append(escape(config.limitCpu())).append("</td>")
                    .append("<td>").append(escape(config.requestRam())).append("</td>")
                    .append("<td>").append(escape(config.limitRam())).append("</td>")
                    .append("</tr>\n");
        }
        bodyBuilder.append("</table>\n");
        return bodyBuilder.toString();
    }

    private String escape(String value) {
        return HtmlUtils.htmlEscape(value == null ? "" : value);
    }

    private String buildChartsSection(List<RenderedChartAttachment> chartAttachments) {
        if (chartAttachments == null || chartAttachments.isEmpty()) {
            return "";
        }
        StringBuilder bodyBuilder = new StringBuilder();
        for (RenderedChartAttachment chartAttachment : chartAttachments) {
            appendPanelToBody(bodyBuilder, chartAttachment.title(), chartAttachment.attachmentName());
        }
        return bodyBuilder.toString();
    }

    private String buildReportTitle(
            ConfluenceTemplate template,
            String releaseValue) {
        String titleTemplate = StringUtils.hasText(template.reportTitle())
                ? template.reportTitle()
                : template.fullName();
        String resolved = getSafeBody(titleTemplate)
                .replace(RELEASE_PLACEHOLDER, releaseValue)
                .replace(CONFIG_PLACEHOLDER, "")
                .replace(GRAPHICS_PLACEHOLDER, "")
                .replace('\n', ' ')
                .replace('\r', ' ')
                .trim()
                .replaceAll("\\s{2,}", " ");
        return StringUtils.hasText(resolved) ? resolved : template.fullName();
    }

    private String buildReportBody(
            String templateBody,
            String releaseValue,
            String configurationSection,
            String chartsSection) {
        String body = getSafeBody(templateBody).replace(RELEASE_PLACEHOLDER, escape(releaseValue));
        body = replaceSectionPlaceholder(body, CONFIG_PLACEHOLDER, configurationSection);
        body = replaceSectionPlaceholder(body, GRAPHICS_PLACEHOLDER, chartsSection);
        return body;
    }

    private String replaceSectionPlaceholder(String body, String placeholder, String section) {
        String safeBody = getSafeBody(body);
        String safeSection = getSafeBody(section);
        Pattern wrappedPlaceholderPattern = Pattern.compile(
                "(?is)<(" + PLACEHOLDER_BLOCK_TAGS + ")[^>]*>"
                        + PLACEHOLDER_PADDING_PATTERN
                        + Pattern.quote(placeholder)
                        + PLACEHOLDER_PADDING_PATTERN
                        + "</\\1>");
        String replacedBody = wrappedPlaceholderPattern.matcher(safeBody)
                .replaceAll(Matcher.quoteReplacement(safeSection));
        return replacedBody.replace(placeholder, safeSection);
    }

    private String resolveReleaseValue(List<KubernetesConfiguration> configurations) {
        List<String> releases = (configurations == null ? List.<KubernetesConfiguration>of() : configurations).stream()
                .map(KubernetesConfiguration::release)
                .toList();
        String release = ReleaseVersionUtils.findMaxRelease(releases);
        return StringUtils.hasText(release) ? release : "—";
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

    private record ResolvedReportItem(LoadTest loadTest, ConfluenceTemplate template) {}

    private record RenderedChartAttachment(String title, String attachmentName) {}
}
