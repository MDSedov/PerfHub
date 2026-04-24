package com.example.chronicler.service;

import com.example.chronicler.client.ConfluenceClient;
import com.example.chronicler.dto.confluence.ConfluenceChartsAppendRequest;
import com.example.chronicler.dto.confluence.ConfluenceChartsAppendResponse;
import com.example.chronicler.dto.confluence.ConfluenceCredentials;
import com.example.chronicler.dto.confluence.ConfluencePageDescendant;
import com.example.chronicler.dto.confluence.ConfluencePage;
import com.example.chronicler.dto.confluence.ConfluencePageTreeNode;
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
import com.example.chronicler.model.ConfluenceTemplate;
import com.example.chronicler.model.ConfluenceTemplateChart;
import com.example.chronicler.model.KubernetesConfiguration;
import com.example.chronicler.model.LoadTest;
import com.example.chronicler.model.LoadTestType;
import com.example.chronicler.repository.LoadTestRepository;
import com.example.chronicler.util.ReleaseVersionUtils;
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

import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.util.HtmlUtils;

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
            ConfigurationProvider configurationProvider) {
        this.confluenceClient = confluenceClient;
        this.grafanaService = grafanaService;
        this.templateLoader = templateLoader;
        this.loadTestRepository = loadTestRepository;
        this.kubernetesConfigurationService = kubernetesConfigurationService;
        this.confluenceTemplateSettingsService = confluenceTemplateSettingsService;
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
        String pageTitle = buildReportTitle(template, releaseValue);
        String body = buildReportBody(template.text(), releaseValue, "", "");
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

        String configurationSection = buildConfigurationSection(configurations);
        String pageTitle = buildReportTitle(template, releaseValue);
        String templateBody = normalizeStorageMarkup(template.text());
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

        String updatedBody = buildReportBody(
                template.text(),
                releaseValue,
                configurationSection,
                buildChartsSection(chartAttachments));
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

    private String buildConfigurationSection(List<KubernetesConfiguration> configurations) {
        StringBuilder bodyBuilder = new StringBuilder();
        if (configurations.isEmpty()) {
            bodyBuilder.append("<p>Конфигурация DropApp для данного теста отсутствует.</p>\n");
            return bodyBuilder.toString();
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
        return bodyBuilder.toString();
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
        return normalizeStorageMarkup(body);
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
        final int maxAttempts = 5;
        final long delayMillis = 700L;
        ConfluenceClientException lastError = null;

        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                waitForPageAvailability(credentials, pageId);
                confluenceClient.uploadAttachment(credentials, pageId, attachmentName, imageBytes);
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
}
