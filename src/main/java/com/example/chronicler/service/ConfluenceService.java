package com.example.chronicler.service;

import com.example.chronicler.client.ConfluenceClient;
import com.example.chronicler.dto.confluence.ConfluenceChartsAppendRequest;
import com.example.chronicler.dto.confluence.ConfluenceChartsAppendResponse;
import com.example.chronicler.dto.confluence.ConfluenceCredentials;
import com.example.chronicler.dto.confluence.ConfluencePageDescendant;
import com.example.chronicler.dto.confluence.ConfluencePage;
import com.example.chronicler.dto.confluence.ConfluencePageTreeNode;
import com.example.chronicler.dto.confluence.ConfluenceReleaseReportLinksUpdateRequest;
import com.example.chronicler.dto.confluence.ConfluenceReleaseReportLinksUpdateResponse;
import com.example.chronicler.dto.confluence.ConfluenceReleaseReportPageRequest;
import com.example.chronicler.dto.confluence.ConfluenceReleaseReportPageResponse;
import com.example.chronicler.dto.confluence.ConfluenceReportHierarchyResponse;
import com.example.chronicler.dto.confluence.ConfluenceReportRequest;
import com.example.chronicler.dto.confluence.ConfluenceReportResponse;
import com.example.chronicler.dto.grafana.GrafanaPanelSelection;
import com.example.chronicler.dto.grafana.GrafanaRenderRangeRequest;
import com.example.chronicler.dto.grafana.GrafanaRenderedPanel;
import com.example.chronicler.exception.ConfluenceClientException;
import com.example.chronicler.exception.ConfluenceOperationException;
import com.example.chronicler.model.ConfluenceReleaseReportLink;
import com.example.chronicler.model.ConfluenceTemplate;
import com.example.chronicler.model.ConfluenceTemplateChart;
import com.example.chronicler.model.KubernetesConfiguration;
import com.example.chronicler.model.LoadTest;
import com.example.chronicler.model.LoadTestType;
import com.example.chronicler.repository.ConfluenceReleaseReportLinkRepository;
import com.example.chronicler.repository.LoadTestRepository;
import com.example.chronicler.util.DateTimeViewUtils;
import com.example.chronicler.util.ReleaseVersionUtils;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.Objects;
import java.util.UUID;

import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.util.HtmlUtils;
import org.springframework.web.server.ResponseStatusException;

@Service
public class ConfluenceService {

    private static final int MAX_PAGE_TITLE_LENGTH = 255;
    private static final String GRAFANA_SERVICE = "grafana";
    private static final String GRAFANA_IMAGE_WIDTH_KEY = "image-width";
    private static final String GRAFANA_IMAGE_HEIGHT_KEY = "image-height";
    private static final String CONFLUENCE_SERVICE = "confluence";
    private static final String REPORTS_PARENT_PAGE_ID_KEY = "reports-parent-page-id";
    private static final String RELEASE_PLACEHOLDER = "@release";
    private static final String CONFIG_PLACEHOLDER = "@config";
    private static final String GRAPHICS_PLACEHOLDER = "@graphics";
    private static final String TEST_TYPE_PLACEHOLDER = "@test_type";
    private static final String TEST_PERIOD_PLACEHOLDER = "@test_period";
    private static final String TEST_STATUS_PLACEHOLDER = "@test_status";
    private static final String TEST_SUMMARY_PLACEHOLDER = "@test_summary";
    private static final String TEST_NUMBER_PLACEHOLDER = "@test_number";
    private static final String TEST_TITLE_PLACEHOLDER = "@test_title";
    private static final String TEST_START_PLACEHOLDER = "@test_start";
    private static final String TEST_END_PLACEHOLDER = "@test_end";
    private static final String TEST_REPORT_LINK_PLACEHOLDER = "@test_report_link";
    private static final String RELEASE_REPORTS_START_MARKER = "@perfhub_reports_start";
    private static final String RELEASE_REPORTS_END_MARKER = "@perfhub_reports_end";
    private static final String REPORT_PLACEHOLDER_TEXT = "Требуется заполнить вручную.";
    private static final String PLACEHOLDER_BLOCK_TAGS =
            "p|div|section|article|aside|blockquote|li|h1|h2|h3|h4|h5|h6";
    private static final String PLACEHOLDER_PADDING_PATTERN = "(?:\\s|&nbsp;|&#160;|<br\\s*/?>)*";
    private static final Pattern BR_TAG_PATTERN = Pattern.compile("(?i)<br\\s*/?>");
    private static final Pattern BR_CLOSING_TAG_PATTERN = Pattern.compile("(?i)</br\\s*>");
    private static final Pattern HR_TAG_PATTERN = Pattern.compile("(?i)<hr\\s*/?>");
    private static final Pattern HR_CLOSING_TAG_PATTERN = Pattern.compile("(?i)</hr\\s*>");

    private final ConfluenceClient confluenceClient;
    private final GrafanaService grafanaService;
    private final TemplateLoader templateLoader;
    private final LoadTestRepository loadTestRepository;
    private final KubernetesConfigurationService kubernetesConfigurationService;
    private final ConfluenceTemplateSettingsService confluenceTemplateSettingsService;
    private final ConfluenceReleaseReportLinkRepository releaseReportLinkRepository;
    private final ConfigurationProvider configurationProvider;

    /**
     * Создаёт сервис для работы с Confluence, кешем рендеров Grafana и загрузкой шаблонов.
     */
    public ConfluenceService(
            ConfluenceClient confluenceClient,
            GrafanaService grafanaService,
            TemplateLoader templateLoader,
            LoadTestRepository loadTestRepository,
            KubernetesConfigurationService kubernetesConfigurationService,
            ConfluenceTemplateSettingsService confluenceTemplateSettingsService,
            ConfluenceReleaseReportLinkRepository releaseReportLinkRepository,
            ConfigurationProvider configurationProvider) {
        this.confluenceClient = confluenceClient;
        this.grafanaService = grafanaService;
        this.templateLoader = templateLoader;
        this.loadTestRepository = loadTestRepository;
        this.kubernetesConfigurationService = kubernetesConfigurationService;
        this.confluenceTemplateSettingsService = confluenceTemplateSettingsService;
        this.releaseReportLinkRepository = releaseReportLinkRepository;
        this.configurationProvider = configurationProvider;
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

    public ConfluencePageTreeNode getReportParentPageTree(ConfluenceCredentials credentials) {
        Objects.requireNonNull(credentials, "Confluence credentials must not be null");
        validateCredentials(credentials);

        String rootPageId = configurationProvider.findValue(CONFLUENCE_SERVICE, REPORTS_PARENT_PAGE_ID_KEY)
                .map(StringUtils::trimWhitespace)
                .filter(StringUtils::hasText)
                .orElseThrow(() -> new ConfluenceOperationException(
                        "В настройках Confluence не заполнено поле \"Номер родительской страницы для отчетов по НТ\""));

        ConfluencePage rootPage = confluenceClient.getPage(credentials, rootPageId);
        List<ConfluencePageDescendant> descendants = confluenceClient.listPageDescendants(credentials, rootPageId);
        return buildPageTree(rootPage, descendants);
    }

    public ConfluenceReleaseReportPageResponse createReleaseReportPage(
            ConfluenceCredentials credentials,
            ConfluenceReleaseReportPageRequest request) {
        Objects.requireNonNull(credentials, "Confluence credentials must not be null");
        Objects.requireNonNull(request, "Confluence release report page request must not be null");
        validateCredentials(credentials);

        if (!StringUtils.hasText(request.parentPageId())) {
            throw new ConfluenceOperationException("parentPageId is required");
        }
        if (request.templateId() == null) {
            throw new ConfluenceOperationException("templateId is required");
        }
        if (request.testUuids() == null || request.testUuids().isEmpty()) {
            throw new ConfluenceOperationException("testUuids must not be empty");
        }

        ConfluencePage parentPage = confluenceClient.getPage(credentials, request.parentPageId());
        if (!StringUtils.hasText(parentPage.spaceKey())) {
            throw new ConfluenceOperationException("Parent page does not contain space key information");
        }

        ConfluenceTemplate template;
        try {
            template = confluenceTemplateSettingsService.getActiveTemplate(request.templateId());
        } catch (IllegalArgumentException ex) {
            throw new ConfluenceOperationException(ex.getMessage(), ex);
        }

        String releaseValue = resolveReleaseValueForTests(request.testUuids());
        String pageTitle = buildReportTitle(template, releaseValue, null);
        String templateBody = loadTemplateBody(credentials, template);
        String body = buildReportBody(templateBody, releaseValue, "", "", null);
        ConfluencePage createdPage = confluenceClient.createChildPage(
                credentials,
                request.parentPageId(),
                parentPage.spaceKey(),
                pageTitle,
                body);
        waitForPageAvailability(credentials, createdPage.id());

        return new ConfluenceReleaseReportPageResponse(
                createdPage.id(),
                createdPage.title(),
                confluenceClient.buildPageUrl(createdPage.id()));
    }

    public ConfluenceReleaseReportLinksUpdateResponse updateReleaseReportLinks(
            ConfluenceCredentials credentials,
            ConfluenceReleaseReportLinksUpdateRequest request) {
        Objects.requireNonNull(credentials, "Confluence credentials must not be null");
        Objects.requireNonNull(request, "Confluence release report links update request must not be null");
        validateCredentials(credentials);
        if (!StringUtils.hasText(request.releasePageId())) {
            throw new ConfluenceOperationException("releasePageId is required");
        }
        if (request.reports() == null || request.reports().isEmpty()) {
            throw new ConfluenceOperationException("reports must not be empty");
        }

        ConfluencePage releasePage = confluenceClient.getPage(credentials, request.releasePageId());
        List<ConfluenceReleaseReportLink> newLinks = request.reports().stream()
                .map(item -> toReleaseReportLink(releasePage.id(), item))
                .toList();
        releaseReportLinkRepository.saveAll(newLinks);

        String body = normalizeStorageMarkup(releasePage.body());
        ReleaseReportsBlock reportsBlock = extractReleaseReportsBlock(body);
        if (reportsBlock == null) {
            return new ConfluenceReleaseReportLinksUpdateResponse(
                    releasePage.id(),
                    false,
                    releaseReportLinkRepository.findByReleasePageId(releasePage.id()).size());
        }

        String itemTemplate = releaseReportLinkRepository.findBlockTemplate(releasePage.id())
                .orElseGet(() -> {
                    String extractedTemplate = reportsBlock.itemTemplate();
                    releaseReportLinkRepository.saveBlockTemplate(releasePage.id(), extractedTemplate);
                    return extractedTemplate;
                });
        List<ConfluenceReleaseReportLink> allLinks =
                releaseReportLinkRepository.findByReleasePageId(releasePage.id());
        String updatedBody = replaceReleaseReportsBlock(body, reportsBlock, itemTemplate, allLinks);
        updatePageWithRetry(credentials, releasePage, updatedBody);

        return new ConfluenceReleaseReportLinksUpdateResponse(releasePage.id(), true, allLinks.size());
    }

    public ConfluenceChartsAppendResponse appendChartsToPage(
            ConfluenceCredentials credentials,
            ConfluenceChartsAppendRequest request) {
        Objects.requireNonNull(credentials, "Confluence credentials must not be null");
        Objects.requireNonNull(request, "Confluence charts append request must not be null");
        validateCredentials(credentials);
        if (!StringUtils.hasText(request.pageId())) {
            throw new ConfluenceOperationException("pageId is required");
        }

        ConfluencePage page = confluenceClient.getPage(credentials, request.pageId());
        List<GrafanaRenderedPanel> renderedPanels = grafanaService.renderTemplatePanelsByRange(
                new GrafanaRenderRangeRequest(request.from(), request.to(), request.templateId()));
        if (renderedPanels.isEmpty()) {
            throw new ConfluenceOperationException("Не удалось подготовить графики для выгрузки");
        }

        List<String> attachmentNames = new ArrayList<>(renderedPanels.size());
        List<RenderedChartAttachment> chartAttachments = new ArrayList<>(renderedPanels.size());
        for (GrafanaRenderedPanel panel : renderedPanels) {
            byte[] imageBytes = decodePanelImage(panel);
            String attachmentName = generateAttachmentName(panel);
            confluenceClient.uploadAttachment(credentials, page.id(), attachmentName, imageBytes);
            attachmentNames.add(attachmentName);
            chartAttachments.add(new RenderedChartAttachment(panel.title(), panel.description(), attachmentName));
        }

        String existingBody = normalizeStorageMarkup(page.body());
        String appendedChartsSection = buildChartsSection(chartAttachments);
        String updatedBody = appendChartsSection(existingBody, appendedChartsSection);
        confluenceClient.updatePage(credentials, page, updatedBody);

        return new ConfluenceChartsAppendResponse(
                page.id(),
                page.title(),
                confluenceClient.buildPageUrl(page.id()),
                chartAttachments.size(),
                List.copyOf(attachmentNames));
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

        String pageTitle = buildReportTitle(template, releaseValue, loadTest);
        String templateBody = loadTemplateBody(credentials, template);
        ConfluencePage reportPage = createReportPage(
                credentials, parentPageId, spaceKey, pageTitle, templateBody, loadTest.id());
        waitForPageAvailability(credentials, reportPage.id());

        List<String> attachmentNames = new ArrayList<>();
        List<RenderedChartAttachment> chartAttachments = new ArrayList<>();
        for (int index = 0; index < templateCharts.size(); index++) {
            ConfluenceTemplateChart chart = templateCharts.get(index);
            GrafanaRenderedPanel panel = renderedPanels.get(index);
            byte[] imageBytes = decodePanelImage(panel);
            String attachmentName = generateAttachmentName(panel);
            uploadAttachmentWithRetry(credentials, reportPage.id(), attachmentName, imageBytes);
            attachmentNames.add(attachmentName);
            chartAttachments.add(new RenderedChartAttachment(chart.title(), panel.description(), attachmentName));
        }

        ConfigurationArchiveAttachment configurationArchive = uploadConfigurationArchiveIfAvailable(
                credentials,
                reportPage.id(),
                testUuid,
                attachmentNames);
        String configurationSection = buildConfigurationSection(configurations, configurationArchive);
        String updatedBody = buildReportBody(
                templateBody,
                releaseValue,
                configurationSection,
                buildChartsSection(chartAttachments),
                loadTest);
        updatePageWithRetry(credentials, reportPage, updatedBody);

        return new ConfluenceReportResponse.ReportPage(
                testUuid,
                reportPage.id(),
                reportPage.title(),
                confluenceClient.buildPageUrl(reportPage.id()),
                List.copyOf(attachmentNames));
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
     * Добавляет в тело страницы название графика и изображение панели.
     */
    private void appendPanelToBody(
            StringBuilder bodyBuilder,
            String title,
            String description,
            GrafanaImageDimensions imageDimensions,
            String attachmentName) {
        String resolvedTitle = StringUtils.hasText(title) ? title : "Панель без названия";
        bodyBuilder
                .append("<h3>")
                .append(HtmlUtils.htmlEscape(resolvedTitle))
                .append("</h3>");
        if (StringUtils.hasText(description)) {
            bodyBuilder
                    .append("<p>")
                    .append(HtmlUtils.htmlEscape(description))
                    .append("</p>");
        }

        bodyBuilder
                .append("<p><ac:image ac:width=\"")
                .append(imageDimensions.width())
                .append("\" ac:height=\"")
                .append(imageDimensions.height())
                .append("\"><ri:attachment ri:filename=\"")
                .append(HtmlUtils.htmlEscape(attachmentName))
                .append("\" /></ac:image></p>");
    }

    private String buildConfigurationSection(
            List<KubernetesConfiguration> configurations,
            ConfigurationArchiveAttachment archiveAttachment) {
        StringBuilder bodyBuilder = new StringBuilder();
        if (archiveAttachment != null) {
            bodyBuilder.append(buildConfigurationArchiveLink(archiveAttachment));
        }
        if (configurations.isEmpty()) {
            bodyBuilder.append("<p>Конфигурация стенда для данного теста отсутствует.</p>\n");
            return buildExpandBlock("Конфигурация стенда", bodyBuilder.toString());
        }
        bodyBuilder.append("<table>\n")
                .append("<tr>")
                .append("<th>Сервис</th>")
                .append("<th>Количество POD</th>")
                .append("<th>Релиз</th>")
                .append("<th>Контейнер</th>")
                .append("<th>Версия</th>")
                .append("<th>Request CPU</th>")
                .append("<th>Limit CPU</th>")
                .append("<th>Request RAM</th>")
                .append("<th>Limit RAM</th>")
                .append("</tr>\n");
        for (int index = 0; index < configurations.size(); index++) {
            KubernetesConfiguration config = configurations.get(index);
            int serviceRowSpan = resolveServiceRowSpan(configurations, index);
            bodyBuilder.append("<tr>")
                    .append(buildRowSpanCell(config.serviceName(), serviceRowSpan))
                    .append(buildRowSpanCell(config.podCount(), serviceRowSpan))
                    .append("<td>").append(escape(config.release())).append("</td>")
                    .append("<td>").append(escape(config.containerName())).append("</td>")
                    .append("<td>").append(escape(config.version())).append("</td>")
                    .append("<td>").append(escape(config.requestCpu())).append("</td>")
                    .append("<td>").append(escape(config.limitCpu())).append("</td>")
                    .append("<td>").append(escape(config.requestRam())).append("</td>")
                    .append("<td>").append(escape(config.limitRam())).append("</td>")
                    .append("</tr>\n");
        }
        bodyBuilder.append("</table>\n");
        return buildExpandBlock("Конфигурация стенда", bodyBuilder.toString());
    }

    private String buildExpandBlock(String title, String body) {
        return "<ac:structured-macro ac:name=\"expand\">\n"
                + "<ac:parameter ac:name=\"title\">" + escape(title) + "</ac:parameter>\n"
                + "<ac:rich-text-body>\n"
                + getSafeBody(body)
                + "</ac:rich-text-body>\n"
                + "</ac:structured-macro>\n";
    }

    private String buildConfigurationArchiveLink(ConfigurationArchiveAttachment archiveAttachment) {
        return "<p><ac:link><ri:attachment ri:filename=\""
                + escape(archiveAttachment.attachmentName())
                + "\" /><ac:plain-text-link-body><![CDATA[Скачать ZIP с полной конфигурацией]]>"
                + "</ac:plain-text-link-body></ac:link></p>\n";
    }

    private String buildRowSpanCell(Object value, int rowSpan) {
        if (rowSpan == 0) {
            return "";
        }
        String rowSpanAttribute = rowSpan > 1 ? " rowspan=\"" + rowSpan + "\"" : "";
        return "<td" + rowSpanAttribute + ">" + escape(value) + "</td>";
    }

    private int resolveServiceRowSpan(List<KubernetesConfiguration> configurations, int index) {
        KubernetesConfiguration current = configurations.get(index);
        if (index > 0 && sameServiceGroup(configurations.get(index - 1), current)) {
            return 0;
        }
        int rowSpan = 1;
        for (int nextIndex = index + 1; nextIndex < configurations.size(); nextIndex++) {
            if (!sameServiceGroup(current, configurations.get(nextIndex))) {
                break;
            }
            rowSpan++;
        }
        return rowSpan;
    }

    private boolean sameServiceGroup(KubernetesConfiguration left, KubernetesConfiguration right) {
        if (left == null || right == null) {
            return false;
        }
        return Objects.equals(left.serviceName(), right.serviceName())
                && Objects.equals(left.podCount(), right.podCount());
    }

    private String escape(String value) {
        return HtmlUtils.htmlEscape(value == null ? "" : value);
    }

    private String escape(Object value) {
        return escape(value != null ? value.toString() : null);
    }

    private String buildChartsSection(List<RenderedChartAttachment> chartAttachments) {
        if (chartAttachments == null || chartAttachments.isEmpty()) {
            return "";
        }
        GrafanaImageDimensions imageDimensions = resolveGrafanaImageDimensions();
        StringBuilder bodyBuilder = new StringBuilder();
        for (RenderedChartAttachment chartAttachment : chartAttachments) {
            appendPanelToBody(
                    bodyBuilder,
                    chartAttachment.title(),
                    chartAttachment.description(),
                    imageDimensions,
                    chartAttachment.attachmentName());
        }
        return bodyBuilder.toString();
    }

    private GrafanaImageDimensions resolveGrafanaImageDimensions() {
        return new GrafanaImageDimensions(
                resolvePositiveConfigInt(GRAFANA_SERVICE, GRAFANA_IMAGE_WIDTH_KEY),
                resolvePositiveConfigInt(GRAFANA_SERVICE, GRAFANA_IMAGE_HEIGHT_KEY));
    }

    private int resolvePositiveConfigInt(String service, String key) {
        String rawValue = configurationProvider.getRequiredValue(service, key);
        String normalizedValue = rawValue == null ? "" : rawValue.trim();
        try {
            int parsedValue = Integer.parseInt(normalizedValue);
            if (parsedValue <= 0) {
                throw new NumberFormatException("Value must be positive");
            }
            return parsedValue;
        } catch (NumberFormatException ex) {
            throw new ConfluenceOperationException(
                    "Configuration value for %s.%s must be a positive integer but was '%s'"
                            .formatted(service, key, rawValue),
                    ex);
        }
    }

    private String appendChartsSection(String existingBody, String chartsSection) {
        String normalizedExistingBody = normalizeStorageMarkup(getSafeBody(existingBody));
        String normalizedChartsSection = normalizeStorageMarkup(getSafeBody(chartsSection));
        if (!StringUtils.hasText(normalizedExistingBody)) {
            return normalizedChartsSection;
        }
        if (!StringUtils.hasText(normalizedChartsSection)) {
            return normalizedExistingBody;
        }
        return normalizedExistingBody + "<hr />" + normalizedChartsSection;
    }

    private String buildReportTitle(
            ConfluenceTemplate template,
            String releaseValue,
            LoadTest loadTest) {
        String titleTemplate = StringUtils.hasText(template.reportTitle())
                ? template.reportTitle()
                : template.fullName();
        String resolved = getSafeBody(titleTemplate)
                .replace(RELEASE_PLACEHOLDER, releaseValue)
                .replace(TEST_START_PLACEHOLDER, titlePlaceholder(formatTestStart(loadTest)))
                .replace(TEST_END_PLACEHOLDER, titlePlaceholder(formatTestEnd(loadTest)))
                .replace(CONFIG_PLACEHOLDER, "")
                .replace(GRAPHICS_PLACEHOLDER, "")
                .replace(TEST_TYPE_PLACEHOLDER, "")
                .replace(TEST_PERIOD_PLACEHOLDER, "")
                .replace(TEST_STATUS_PLACEHOLDER, "")
                .replace(TEST_SUMMARY_PLACEHOLDER, "")
                .replace(TEST_NUMBER_PLACEHOLDER, "")
                .replace(TEST_TITLE_PLACEHOLDER, "")
                .replace(TEST_REPORT_LINK_PLACEHOLDER, "")
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
            String chartsSection,
            LoadTest loadTest) {
        String body = getSafeBody(templateBody).replace(RELEASE_PLACEHOLDER, escape(releaseValue));
        body = replaceSectionPlaceholder(body, CONFIG_PLACEHOLDER, configurationSection);
        body = replaceSectionPlaceholder(body, GRAPHICS_PLACEHOLDER, chartsSection);
        body = replaceTestPlaceholders(body, loadTest);
        return normalizeStorageMarkup(body);
    }

    private String replaceTestPlaceholders(String body, LoadTest loadTest) {
        if (loadTest == null) {
            return body;
        }
        return getSafeBody(body)
                .replace(TEST_TYPE_PLACEHOLDER, escape(formatLoadTestType(loadTest.type())))
                .replace(TEST_START_PLACEHOLDER, escape(formatTestStart(loadTest)))
                .replace(TEST_END_PLACEHOLDER, escape(formatTestEnd(loadTest)))
                .replace(TEST_PERIOD_PLACEHOLDER, escape(formatTestPeriod(loadTest)))
                .replace(TEST_STATUS_PLACEHOLDER, escape(orPlaceholder(loadTest.reportStatus())))
                .replace(TEST_SUMMARY_PLACEHOLDER, escape(orPlaceholder(loadTest.reportSummary())));
    }

    private String loadTemplateBody(ConfluenceCredentials credentials, ConfluenceTemplate template) {
        if (template != null && StringUtils.hasText(template.templatePageId())) {
            ConfluencePage templatePage = confluenceClient.getPage(credentials, template.templatePageId());
            return normalizeStorageMarkup(templatePage.body());
        }
        return normalizeStorageMarkup(template != null ? template.text() : "");
    }

    private ConfluenceReleaseReportLink toReleaseReportLink(
            String releasePageId,
            ConfluenceReleaseReportLinksUpdateRequest.ReportLinkItem item) {
        if (item == null || item.testUuid() == null) {
            throw new ConfluenceOperationException("testUuid is required");
        }
        if (!StringUtils.hasText(item.reportPageId())) {
            throw new ConfluenceOperationException("reportPageId is required");
        }
        LoadTest loadTest = resolveLoadTest(item.testUuid());
        String testTitle = StringUtils.hasText(item.testTitle())
                ? item.testTitle().trim()
                : defaultTestTitle(loadTest.type());
        return new ConfluenceReleaseReportLink(
                null,
                releasePageId,
                item.testUuid(),
                item.reportPageId().trim(),
                StringUtils.hasText(item.reportPageTitle()) ? item.reportPageTitle().trim() : testTitle,
                StringUtils.hasText(item.reportPageUrl()) ? item.reportPageUrl().trim() : confluenceClient.buildPageUrl(item.reportPageId()),
                StringUtils.hasText(item.testNumber()) ? item.testNumber().trim() : "",
                testTitle,
                formatLoadTestType(loadTest.type()),
                formatTestPeriod(loadTest),
                orPlaceholder(loadTest.reportStatus()),
                orPlaceholder(loadTest.reportSummary()),
                null);
    }

    private ReleaseReportsBlock extractReleaseReportsBlock(String body) {
        String safeBody = getSafeBody(body);
        int startMarkerIndex = safeBody.indexOf(RELEASE_REPORTS_START_MARKER);
        int endMarkerIndex = safeBody.indexOf(RELEASE_REPORTS_END_MARKER);
        if (startMarkerIndex < 0 || endMarkerIndex < 0 || endMarkerIndex <= startMarkerIndex) {
            return null;
        }
        int replacementStart = findReplacementStartAfterMarker(safeBody, startMarkerIndex, RELEASE_REPORTS_START_MARKER);
        int replacementEnd = findReplacementEndBeforeMarker(safeBody, endMarkerIndex);
        return new ReleaseReportsBlock(replacementStart, replacementEnd, safeBody.substring(replacementStart, replacementEnd));
    }

    private int findReplacementStartAfterMarker(String body, int markerIndex, String marker) {
        int cursor = markerIndex + marker.length();
        int nextNonWhitespace = cursor;
        while (nextNonWhitespace < body.length() && Character.isWhitespace(body.charAt(nextNonWhitespace))) {
            nextNonWhitespace++;
        }
        String tail = body.substring(nextNonWhitespace).toLowerCase();
        String[] closingTags = {"</p>", "</div>", "</section>", "</article>", "</li>", "</h1>", "</h2>", "</h3>", "</h4>", "</h5>", "</h6>"};
        for (String closingTag : closingTags) {
            if (tail.startsWith(closingTag)) {
                return nextNonWhitespace + closingTag.length();
            }
        }
        return cursor;
    }

    private int findReplacementEndBeforeMarker(String body, int markerIndex) {
        int previousTagStart = body.lastIndexOf('<', markerIndex);
        if (previousTagStart >= 0) {
            int previousTagEnd = body.indexOf('>', previousTagStart);
            if (previousTagEnd >= 0 && previousTagEnd < markerIndex) {
                String tag = body.substring(previousTagStart, previousTagEnd + 1).toLowerCase();
                String betweenTagAndMarker = body.substring(previousTagEnd + 1, markerIndex);
                if (tag.matches("<\\s*(p|div|section|article|li|h1|h2|h3|h4|h5|h6)[^>]*>")
                        && betweenTagAndMarker.trim().isEmpty()) {
                    return previousTagStart;
                }
            }
            String tag = body.substring(previousTagStart, markerIndex).toLowerCase();
            if (tag.matches("<\\s*(p|div|section|article|li|h1|h2|h3|h4|h5|h6)[^>]*>\\s*")) {
                return previousTagStart;
            }
        }
        return markerIndex;
    }

    private String replaceReleaseReportsBlock(
            String body,
            ReleaseReportsBlock reportsBlock,
            String itemTemplate,
            List<ConfluenceReleaseReportLink> links) {
        String renderedLinks = renderReleaseReportLinks(itemTemplate, links);
        return body.substring(0, reportsBlock.replacementStart())
                + renderedLinks
                + body.substring(reportsBlock.replacementEnd());
    }

    private String renderReleaseReportLinks(String itemTemplate, List<ConfluenceReleaseReportLink> links) {
        String template = StringUtils.hasText(itemTemplate)
                ? itemTemplate
                : "<h3>@test_number @test_title</h3><p>@test_status</p><p>@test_summary</p><p>@test_report_link</p>";
        StringBuilder bodyBuilder = new StringBuilder();
        for (int index = 0; index < links.size(); index++) {
            ConfluenceReleaseReportLink link = links.get(index);
            String rendered = template
                    .replace(TEST_NUMBER_PLACEHOLDER, escape(orDefault(link.testNumber(), String.valueOf(index + 1))))
                    .replace(TEST_TITLE_PLACEHOLDER, escape(orPlaceholder(link.testTitle())))
                    .replace(TEST_TYPE_PLACEHOLDER, escape(orPlaceholder(link.testType())))
                    .replace(TEST_START_PLACEHOLDER, escape(formatLinkedTestStart(link)))
                    .replace(TEST_END_PLACEHOLDER, escape(formatLinkedTestEnd(link)))
                    .replace(TEST_PERIOD_PLACEHOLDER, escape(orPlaceholder(link.testPeriod())))
                    .replace(TEST_STATUS_PLACEHOLDER, escape(orPlaceholder(link.testStatus())))
                    .replace(TEST_SUMMARY_PLACEHOLDER, escape(orPlaceholder(link.testSummary())))
                    .replace(TEST_REPORT_LINK_PLACEHOLDER, buildReportPageLink(link));
            bodyBuilder.append(rendered);
        }
        return bodyBuilder.toString();
    }

    private String buildReportPageLink(ConfluenceReleaseReportLink link) {
        String url = StringUtils.hasText(link.reportPageUrl())
                ? link.reportPageUrl()
                : confluenceClient.buildPageUrl(link.reportPageId());
        String title = StringUtils.hasText(link.reportPageTitle()) ? link.reportPageTitle() : "Отчет по тесту";
        return "<ac:link><ri:url ri:value=\""
                + escape(url)
                + "\" /><ac:plain-text-link-body><![CDATA["
                + escapeCdata(title)
                + "]]></ac:plain-text-link-body></ac:link>";
    }

    private String formatTestPeriod(LoadTest loadTest) {
        if (loadTest == null) {
            return REPORT_PLACEHOLDER_TEXT;
        }
        String start = formatInstant(loadTest.start());
        String end = formatInstant(loadTest.end());
        if (!StringUtils.hasText(start) && !StringUtils.hasText(end)) {
            return REPORT_PLACEHOLDER_TEXT;
        }
        if (!StringUtils.hasText(start)) {
            return end;
        }
        if (!StringUtils.hasText(end)) {
            return start;
        }
        return start + " - " + end;
    }

    private String formatTestStart(LoadTest loadTest) {
        if (loadTest == null) {
            return REPORT_PLACEHOLDER_TEXT;
        }
        String value = formatInstant(loadTest.start());
        return StringUtils.hasText(value) ? value : REPORT_PLACEHOLDER_TEXT;
    }

    private String formatTestEnd(LoadTest loadTest) {
        if (loadTest == null) {
            return REPORT_PLACEHOLDER_TEXT;
        }
        String value = formatInstant(loadTest.end());
        return StringUtils.hasText(value) ? value : REPORT_PLACEHOLDER_TEXT;
    }

    private String formatLinkedTestStart(ConfluenceReleaseReportLink link) {
        if (link == null || link.testUuid() == null) {
            return REPORT_PLACEHOLDER_TEXT;
        }
        return loadTestRepository.findById(link.testUuid())
                .map(this::formatTestStart)
                .orElse(REPORT_PLACEHOLDER_TEXT);
    }

    private String formatLinkedTestEnd(ConfluenceReleaseReportLink link) {
        if (link == null || link.testUuid() == null) {
            return REPORT_PLACEHOLDER_TEXT;
        }
        return loadTestRepository.findById(link.testUuid())
                .map(this::formatTestEnd)
                .orElse(REPORT_PLACEHOLDER_TEXT);
    }

    private String titlePlaceholder(String value) {
        return StringUtils.hasText(value) && !REPORT_PLACEHOLDER_TEXT.equals(value) ? value : "";
    }

    private String formatInstant(Instant instant) {
        return DateTimeViewUtils.formatInstant(instant);
    }

    private String formatLoadTestType(LoadTestType type) {
        if (type == null) {
            return "UNKNOWN";
        }
        return switch (type) {
            case MAX -> "Максимум";
            case STABILITY -> "Стабильность";
            case SOAK -> "Надежность";
            case PSU -> "PSU";
            case DT -> "DT";
            case UNKNOWN -> "UNKNOWN";
        };
    }

    private String defaultTestTitle(LoadTestType type) {
        if (type == null) {
            return "Отчет по нагрузочному тесту";
        }
        return switch (type) {
            case MAX -> "Определение максимальной производительности";
            case STABILITY -> "Подтверждение максимальной производительности";
            case SOAK -> "Подтверждение надежности";
            case PSU -> "Проверка PSU";
            case DT -> "Проверка DT";
            case UNKNOWN -> "Отчет по нагрузочному тесту";
        };
    }

    private String orPlaceholder(String value) {
        return orDefault(value, REPORT_PLACEHOLDER_TEXT);
    }

    private String orDefault(String value, String fallback) {
        String normalized = value == null ? "" : value.trim();
        return StringUtils.hasText(normalized) ? normalized : fallback;
    }

    private String escapeCdata(String value) {
        return (value == null ? "" : value).replace("]]>", "]]]]><![CDATA[>");
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

    private ConfluencePage createReportPage(
            ConfluenceCredentials credentials,
            String parentPageId,
            String spaceKey,
            String baseTitle,
            String templateBody,
            UUID testUuid) {
        List<String> titleCandidates = buildPageTitleCandidates(baseTitle, testUuid);
        ConfluenceClientException lastDuplicateError = null;
        for (String titleCandidate : titleCandidates) {
            try {
                return confluenceClient.createChildPage(
                        credentials, parentPageId, spaceKey, titleCandidate, templateBody);
            } catch (ConfluenceClientException ex) {
                if (!isDuplicateTitleError(ex) || titleCandidate.equals(titleCandidates.get(titleCandidates.size() - 1))) {
                    throw ex;
                }
                lastDuplicateError = ex;
            }
        }
        if (lastDuplicateError != null) {
            throw lastDuplicateError;
        }
        throw new ConfluenceOperationException("Не удалось создать страницу отчета в Confluence");
    }

    private ConfluencePageTreeNode buildPageTree(ConfluencePage rootPage, List<ConfluencePageDescendant> descendants) {
        PageTreeAccumulator rootNode = new PageTreeAccumulator(
                rootPage.id(),
                StringUtils.hasText(rootPage.title()) ? rootPage.title() : rootPage.id(),
                confluenceClient.buildPageUrl(rootPage.id()));
        Map<String, PageTreeAccumulator> nodesById = new LinkedHashMap<>();
        nodesById.put(rootNode.id(), rootNode);

        for (ConfluencePageDescendant descendant : descendants) {
            if (!StringUtils.hasText(descendant.id()) || descendant.id().equals(rootNode.id())) {
                continue;
            }
            nodesById.put(
                    descendant.id(),
                    new PageTreeAccumulator(
                            descendant.id(),
                            StringUtils.hasText(descendant.title()) ? descendant.title() : descendant.id(),
                            confluenceClient.buildPageUrl(descendant.id())));
        }

        for (ConfluencePageDescendant descendant : descendants) {
            PageTreeAccumulator childNode = nodesById.get(descendant.id());
            if (childNode == null || childNode.id().equals(rootNode.id())) {
                continue;
            }
            PageTreeAccumulator parentNode = resolveParentNode(rootNode, descendant, nodesById);
            parentNode.children().add(childNode);
        }

        return rootNode.toTreeNode();
    }

    private PageTreeAccumulator resolveParentNode(
            PageTreeAccumulator rootNode,
            ConfluencePageDescendant descendant,
            Map<String, PageTreeAccumulator> nodesById) {
        List<String> ancestorIds = descendant.ancestorIds();
        if (ancestorIds == null || ancestorIds.isEmpty()) {
            return rootNode;
        }
        for (int index = ancestorIds.size() - 1; index >= 0; index--) {
            String ancestorId = ancestorIds.get(index);
            if (!StringUtils.hasText(ancestorId) || ancestorId.equals(descendant.id())) {
                continue;
            }
            PageTreeAccumulator parentNode = nodesById.get(ancestorId);
            if (parentNode != null) {
                return parentNode;
            }
        }
        return rootNode;
    }

    private void waitForPageAvailability(ConfluenceCredentials credentials, String pageId) {
        final int maxAttempts = 6;
        final long delayMillis = 700L;
        ConfluenceClientException lastError = null;

        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                confluenceClient.getPage(credentials, pageId);
                return;
            } catch (ConfluenceClientException ex) {
                lastError = ex;
                if (attempt == maxAttempts) {
                    throw ex;
                }
                try {
                    TimeUnit.MILLISECONDS.sleep(delayMillis);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new ConfluenceOperationException(
                            "Ожидание доступности страницы Confluence было прервано",
                            interrupted);
                }
            }
        }

        if (lastError != null) {
            throw lastError;
        }
    }

    private void uploadAttachmentWithRetry(
            ConfluenceCredentials credentials,
            String pageId,
            String attachmentName,
            byte[] imageBytes) {
        uploadAttachmentWithRetry(
                credentials,
                pageId,
                () -> confluenceClient.uploadAttachment(credentials, pageId, attachmentName, imageBytes));
    }

    private void uploadAttachmentWithRetry(
            ConfluenceCredentials credentials,
            String pageId,
            String attachmentName,
            byte[] bytes,
            MediaType mediaType) {
        uploadAttachmentWithRetry(
                credentials,
                pageId,
                () -> confluenceClient.uploadAttachment(credentials, pageId, attachmentName, bytes, mediaType));
    }

    private void uploadAttachmentWithRetry(
            ConfluenceCredentials credentials,
            String pageId,
            AttachmentUpload upload) {
        final int maxAttempts = 5;
        final long delayMillis = 700L;
        ConfluenceClientException lastError = null;

        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                waitForPageAvailability(credentials, pageId);
                upload.upload();
                return;
            } catch (ConfluenceClientException ex) {
                lastError = ex;
                if (attempt == maxAttempts) {
                    throw ex;
                }
                sleepBeforeRetry(delayMillis);
            }
        }

        if (lastError != null) {
            throw lastError;
        }
        throw new ConfluenceOperationException("Не удалось загрузить вложение в Confluence");
    }

    private ConfigurationArchiveAttachment uploadConfigurationArchiveIfAvailable(
            ConfluenceCredentials credentials,
            String pageId,
            UUID testUuid,
            List<String> attachmentNames) {
        DropAppConfigurationSnapshotService.SnapshotArchive archive;
        try {
            archive = kubernetesConfigurationService.createConfigurationArchive(testUuid);
        } catch (ResponseStatusException ex) {
            if (ex.getStatusCode().value() == 404) {
                return null;
            }
            throw ex;
        }
        if (archive == null || archive.content() == null || archive.content().length == 0
                || !StringUtils.hasText(archive.fileName())) {
            return null;
        }
        uploadAttachmentWithRetry(
                credentials,
                pageId,
                archive.fileName(),
                archive.content(),
                resolveArchiveMediaType(archive.contentType()));
        attachmentNames.add(archive.fileName());
        return new ConfigurationArchiveAttachment(archive.fileName());
    }

    private MediaType resolveArchiveMediaType(String contentType) {
        if (!StringUtils.hasText(contentType)) {
            return MediaType.APPLICATION_OCTET_STREAM;
        }
        try {
            return MediaType.parseMediaType(contentType);
        } catch (IllegalArgumentException ex) {
            return MediaType.APPLICATION_OCTET_STREAM;
        }
    }

    private void updatePageWithRetry(
            ConfluenceCredentials credentials,
            ConfluencePage page,
            String updatedBody) {
        final int maxAttempts = 5;
        final long delayMillis = 700L;
        ConfluenceClientException lastError = null;

        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                waitForPageAvailability(credentials, page.id());
                confluenceClient.updatePage(credentials, page, updatedBody);
                return;
            } catch (ConfluenceClientException ex) {
                lastError = ex;
                if (attempt == maxAttempts) {
                    throw ex;
                }
                sleepBeforeRetry(delayMillis);
            }
        }

        if (lastError != null) {
            throw lastError;
        }
        throw new ConfluenceOperationException("Не удалось обновить страницу отчета в Confluence");
    }

    private void sleepBeforeRetry(long delayMillis) {
        try {
            TimeUnit.MILLISECONDS.sleep(delayMillis);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new ConfluenceOperationException(
                    "Ожидание повторной операции Confluence было прервано",
                    interrupted);
        }
    }

    private List<String> buildPageTitleCandidates(String baseTitle, UUID testUuid) {
        String safeBaseTitle = StringUtils.hasText(baseTitle) ? baseTitle.trim() : "Confluence Report";
        String shortTestId = testUuid.toString().substring(0, 8);
        String uuidSuffix = " [" + shortTestId + "]";
        String timestampSuffix = " [" + shortTestId + "-" + System.currentTimeMillis() + "]";
        List<String> candidates = new ArrayList<>();
        candidates.add(trimPageTitle(safeBaseTitle));
        candidates.add(trimPageTitle(safeBaseTitle, uuidSuffix));
        candidates.add(trimPageTitle(safeBaseTitle, timestampSuffix));
        return candidates.stream().distinct().toList();
    }

    private String trimPageTitle(String baseTitle) {
        return trimPageTitle(baseTitle, "");
    }

    private String trimPageTitle(String baseTitle, String suffix) {
        String safeSuffix = suffix == null ? "" : suffix;
        int maxBaseLength = Math.max(1, MAX_PAGE_TITLE_LENGTH - safeSuffix.length());
        String trimmedBase = baseTitle.length() <= maxBaseLength
                ? baseTitle
                : baseTitle.substring(0, maxBaseLength).trim();
        return trimmedBase + safeSuffix;
    }

    private boolean isDuplicateTitleError(Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            if (containsDuplicateTitleMessage(current.getMessage())) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private boolean containsDuplicateTitleMessage(String message) {
        if (!StringUtils.hasText(message)) {
            return false;
        }
        String normalized = message.toLowerCase();
        return normalized.contains("title")
                && (normalized.contains("already exists")
                        || normalized.contains("must be unique")
                        || normalized.contains("conflict"));
    }

    private String normalizeStorageMarkup(String markup) {
        String normalized = getSafeBody(markup);
        normalized = BR_CLOSING_TAG_PATTERN.matcher(normalized).replaceAll("");
        normalized = BR_TAG_PATTERN.matcher(normalized).replaceAll("<br />");
        normalized = HR_CLOSING_TAG_PATTERN.matcher(normalized).replaceAll("");
        normalized = HR_TAG_PATTERN.matcher(normalized).replaceAll("<hr />");
        return normalized;
    }

    private String resolveReleaseValue(List<KubernetesConfiguration> configurations) {
        List<String> releases = (configurations == null ? List.<KubernetesConfiguration>of() : configurations).stream()
                .map(KubernetesConfiguration::release)
                .toList();
        String release = ReleaseVersionUtils.findMaxRelease(releases);
        return StringUtils.hasText(release) ? release : "—";
    }

    private String resolveReleaseValueForTests(List<UUID> testUuids) {
        List<KubernetesConfiguration> allConfigurations = (testUuids == null ? List.<UUID>of() : testUuids).stream()
                .filter(Objects::nonNull)
                .map(kubernetesConfigurationService::getConfigurations)
                .flatMap(List::stream)
                .toList();
        return resolveReleaseValue(allConfigurations);
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

    private static final class PageTreeAccumulator {
        private final String id;
        private final String title;
        private final String pageUrl;
        private final List<PageTreeAccumulator> children = new ArrayList<>();

        private PageTreeAccumulator(String id, String title, String pageUrl) {
            this.id = id;
            this.title = title;
            this.pageUrl = pageUrl;
        }

        private String id() {
            return id;
        }

        private List<PageTreeAccumulator> children() {
            return children;
        }

        private ConfluencePageTreeNode toTreeNode() {
            return new ConfluencePageTreeNode(
                    id,
                    title,
                    pageUrl,
                    children.stream().map(PageTreeAccumulator::toTreeNode).toList());
        }
    }

    private record ResolvedReportItem(LoadTest loadTest, ConfluenceTemplate template) {}

    private record GrafanaImageDimensions(int width, int height) {}

    private record RenderedChartAttachment(String title, String description, String attachmentName) {}

    private record ConfigurationArchiveAttachment(String attachmentName) {}

    private record ReleaseReportsBlock(int replacementStart, int replacementEnd, String itemTemplate) {}

    @FunctionalInterface
    private interface AttachmentUpload {
        void upload();
    }
}
