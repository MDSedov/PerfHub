package perfhub.service;

import perfhub.client.ConfluenceClient;
import perfhub.dto.confluence.ConfluenceChartsAppendRequest;
import perfhub.dto.confluence.ConfluenceChartsAppendResponse;
import perfhub.dto.confluence.ConfluenceCredentials;
import perfhub.dto.confluence.ConfluencePage;
import perfhub.dto.confluence.ConfluencePageSummary;
import perfhub.dto.confluence.ConfluencePageTreeNode;
import perfhub.dto.confluence.ConfluenceReleaseReportLinksUpdateRequest;
import perfhub.dto.confluence.ConfluenceReleaseReportLinksUpdateResponse;
import perfhub.dto.confluence.ConfluenceReleaseReportPageRequest;
import perfhub.dto.confluence.ConfluenceReleaseReportPageResponse;
import perfhub.dto.confluence.ConfluenceReportHierarchyResponse;
import perfhub.dto.confluence.ConfluenceReportRequest;
import perfhub.dto.confluence.ConfluenceReportResponse;
import perfhub.dto.dropapp.DropAppConfigurationSnapshotDetailsDto;
import perfhub.dto.dropapp.JiraReleaseDistributionDto;
import perfhub.dto.grafana.GrafanaRenderRangeRequest;
import perfhub.dto.grafana.GrafanaRenderedPanel;
import perfhub.exception.ConfluenceClientException;
import perfhub.exception.ConfluenceOperationException;
import perfhub.model.ConfluenceReleaseReportLink;
import perfhub.model.ConfluenceTemplate;
import perfhub.model.ConfluenceTemplateChart;
import perfhub.model.ConfluenceTemplateType;
import perfhub.model.KubernetesConfiguration;
import perfhub.model.LoadTest;
import perfhub.model.LoadTestType;
import perfhub.repository.ConfluenceReleaseReportLinkRepository;
import perfhub.repository.LoadTestRepository;
import perfhub.util.DateTimeViewUtils;
import perfhub.util.ReleaseVersionUtils;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
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
    private static final String JIRA_SERVICE = "jira";
    private static final String JIRA_PROJECT_NAME_KEY = "project-name";
    private static final String RELEASE_PLACEHOLDER = "@release";
    private static final String JIRA_RELEASE_TASK_PLACEHOLDER = "@jira_release_task";
    private static final String JIRA_RELEASE_LINK_PLACEHOLDER = "@jira_release_link";
    private static final String JIRA_RELEASE_DISTRIBUTION_TABLE_PLACEHOLDER = "@jira_release_distr_table";
    private static final String JIRA_RELEASE_REQUIRED_LT_TABLE_PLACEHOLDER = "@jira_release_tasks_required_lt";
    private static final String CONFIG_PLACEHOLDER = "@config";
    private static final String GRAPHICS_PLACEHOLDER = "@graphics";
    private static final String TEST_TYPE_PLACEHOLDER = "@test_type";
    private static final String TEST_PERIOD_PLACEHOLDER = "@test_period";
    private static final String TEST_STATUS_PLACEHOLDER = "@test_status";
    private static final String TEST_SUMMARY_PLACEHOLDER = "@test_summary";
    private static final String TEST_NUMBER_PLACEHOLDER = "@test_number";
    private static final String TEST_TITLE_PLACEHOLDER = "@test_title";
    private static final String TEST_TITLE_SHORT_PLACEHOLDER = "@test_title_short";
    private static final String TEST_START_PLACEHOLDER = "@test_start";
    private static final String TEST_START_DATE_PLACEHOLDER = "@test_start_date";
    private static final String TEST_START_TIME_PLACEHOLDER = "@test_start_time";
    private static final String TEST_END_PLACEHOLDER = "@test_end";
    private static final String TEST_END_DATE_PLACEHOLDER = "@test_end_date";
    private static final String TEST_END_TIME_PLACEHOLDER = "@test_end_time";
    private static final String TEST_REPORT_LINK_PLACEHOLDER = "@test_report_link";
    private static final String RELEASE_REPORTS_START_MARKER = "@perfhub_reports_start";
    private static final String RELEASE_REPORTS_END_MARKER = "@perfhub_reports_end";
    private static final String REPORT_PLACEHOLDER_TEXT = "Требуется заполнить вручную.";
    private static final String JIRA_REQUIRED_LT_FIELD = "Требуется НТ";
    private static final String JIRA_REQUIRED_LT_VALUE = "Да";
    private static final String JIRA_REQUIRED_LT_COLUMNS =
            "key,summary,type,created,updated,assignee,priority,components,status,resolution";
    private static final int JIRA_REQUIRED_LT_MAX_ISSUES = 20;
    private static final String PLACEHOLDER_BLOCK_TAGS =
            "p|div|section|article|aside|blockquote|li|h1|h2|h3|h4|h5|h6";
    private static final String PLACEHOLDER_PADDING_PATTERN = "(?:\\s|&nbsp;|&#160;|<br\\s*/?>)*";
    private static final Pattern BR_TAG_PATTERN = Pattern.compile("(?i)<br\\s*/?>");
    private static final Pattern BR_CLOSING_TAG_PATTERN = Pattern.compile("(?i)</br\\s*>");
    private static final Pattern HR_TAG_PATTERN = Pattern.compile("(?i)<hr\\s*/?>");
    private static final Pattern HR_CLOSING_TAG_PATTERN = Pattern.compile("(?i)</hr\\s*>");
    private static final Pattern STYLE_ATTRIBUTE_PATTERN = Pattern.compile("(?i)(\\sstyle\\s*=\\s*)([\"'])(.*?)\\2");
    private static final Pattern DISPLAY_NONE_STYLE_PATTERN = Pattern.compile("(?i)(^|;)\\s*display\\s*:\\s*none\\b");
    private static final Pattern PAGE_ID_QUERY_PATTERN = Pattern.compile("[?&]pageId=([^&#]+)");
    private static final Pattern PAGE_ID_PATH_PATTERN = Pattern.compile("/pages/(?:viewpage\\.action/)?([^/?#]+)");
    private static final byte[] PNG_SIGNATURE =
            new byte[] {(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A};

    private final ConfluenceClient confluenceClient;
    private final GrafanaService grafanaService;
    private final TemplateLoader templateLoader;
    private final LoadTestRepository loadTestRepository;
    private final KubernetesConfigurationService kubernetesConfigurationService;
    private final ConfluenceTemplateSettingsService confluenceTemplateSettingsService;
    private final ConfluenceReleaseReportLinkRepository releaseReportLinkRepository;
    private final ConfigurationProvider configurationProvider;
    private final ConfluenceRequestThrottler confluenceRequestThrottler;

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
            ConfigurationProvider configurationProvider,
            ConfluenceRequestThrottler confluenceRequestThrottler) {
        this.confluenceClient = confluenceClient;
        this.grafanaService = grafanaService;
        this.templateLoader = templateLoader;
        this.loadTestRepository = loadTestRepository;
        this.kubernetesConfigurationService = kubernetesConfigurationService;
        this.confluenceTemplateSettingsService = confluenceTemplateSettingsService;
        this.releaseReportLinkRepository = releaseReportLinkRepository;
        this.configurationProvider = configurationProvider;
        this.confluenceRequestThrottler = confluenceRequestThrottler;
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
        String parentPageId = normalizeConfluencePageReference(request.parentPageId());
        List<ResolvedReportItem> reportItems = resolveReportItems(request);

        ConfluencePage parentPage = confluenceClient.getPage(credentials, parentPageId);
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
        parentPageId = normalizeConfluencePageReference(parentPageId);

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
                .map(this::normalizeConfluencePageReference)
                .orElseThrow(() -> new ConfluenceOperationException(
                        "В настройках Confluence не заполнено поле \"Номер родительской страницы для отчетов по НТ\""));

        confluenceRequestThrottler.awaitReportParentPagesRequestSlot();
        ConfluencePage rootPage = confluenceClient.getPage(credentials, rootPageId);
        return buildCurrentPageTree(credentials, rootPage.id(), rootPage.title(), new LinkedHashSet<>());
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
        String parentPageId = normalizeConfluencePageReference(request.parentPageId());
        if (request.testUuids() == null || request.testUuids().isEmpty()) {
            throw new ConfluenceOperationException("testUuids must not be empty");
        }

        ConfluencePage parentPage = confluenceClient.getPage(credentials, parentPageId);
        if (!StringUtils.hasText(parentPage.spaceKey())) {
            throw new ConfluenceOperationException("Parent page does not contain space key information");
        }

        ConfluenceTemplate template = resolveReleaseReportTemplate(request.templateId());

        String releaseValue = resolveReleaseValueForTests(request.testUuids());
        JiraReleasePlaceholderValues jiraReleaseValues = resolveJiraReleaseValuesForTests(request.testUuids());
        String pageTitle = buildReportTitle(template, releaseValue, null, jiraReleaseValues);
        String templateBody = loadTemplateBody(credentials, template);
        String body = buildReportBody(templateBody, releaseValue, "", "", null, jiraReleaseValues);
        ConfluencePage createdPage = confluenceClient.createChildPage(
                credentials,
                parentPageId,
                parentPage.spaceKey(),
                pageTitle,
                body);
        waitForPageAvailability(credentials, createdPage.id());

        return new ConfluenceReleaseReportPageResponse(
                createdPage.id(),
                createdPage.title(),
                confluenceClient.buildPageUrl(createdPage.id()));
    }

    private ConfluenceTemplate resolveReleaseReportTemplate(Integer templateId) {
        try {
            ConfluenceTemplate template = templateId == null
                    ? confluenceTemplateSettingsService.findActiveTemplateByShortName(
                                    confluenceTemplateSettingsService.resolveReleaseTemplateShortName())
                            .orElseThrow(() -> new IllegalArgumentException(
                                    "Шаблон отчета по релизу не настроен"))
                    : confluenceTemplateSettingsService.getActiveTemplate(templateId);
            if (template.templateType() != ConfluenceTemplateType.RELEASE) {
                throw new IllegalArgumentException("Выбранный шаблон не является шаблоном отчета по релизу");
            }
            return template;
        } catch (IllegalArgumentException ex) {
            throw new ConfluenceOperationException(ex.getMessage(), ex);
        }
    }

    private String normalizeConfluencePageReference(String rawPageId) {
        String pageId = StringUtils.trimWhitespace(rawPageId);
        if (!StringUtils.hasText(pageId)) {
            return "";
        }
        Matcher queryMatcher = PAGE_ID_QUERY_PATTERN.matcher(pageId);
        if (queryMatcher.find()) {
            return queryMatcher.group(1);
        }
        Matcher pathMatcher = PAGE_ID_PATH_PATTERN.matcher(pageId);
        if (pathMatcher.find()) {
            return pathMatcher.group(1);
        }
        return pageId;
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
        String updatedBody = replaceReleaseReportsBlock(
                body, reportsBlock, itemTemplate, allLinks, releasePage.spaceKey());
        updatedBody = hideReleaseReportMarkers(updatedBody);
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
            String uploadedAttachmentName =
                    confluenceClient.uploadAttachment(credentials, page.id(), attachmentName, imageBytes);
            attachmentNames.add(uploadedAttachmentName);
            chartAttachments.add(new RenderedChartAttachment(
                    panel.title(),
                    panel.description(),
                    uploadedAttachmentName));
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
        List<GrafanaRenderedPanel> renderedPanels = grafanaService.renderTemplatePanelsForTest(loadTest, template);
        if (renderedPanels.size() != templateCharts.size()) {
            throw new ConfluenceOperationException("Не удалось подготовить все графики для выбранного шаблона");
        }

        JiraReleasePlaceholderValues jiraReleaseValues = resolveJiraReleaseValues(testUuid);
        String pageTitle = buildReportTitle(template, releaseValue, loadTest, jiraReleaseValues);
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
            String uploadedAttachmentName =
                    uploadAttachmentWithRetry(credentials, reportPage.id(), attachmentName, imageBytes);
            attachmentNames.add(uploadedAttachmentName);
            chartAttachments.add(new RenderedChartAttachment(
                    chart.title(),
                    panel.description(),
                    uploadedAttachmentName));
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
                loadTest,
                jiraReleaseValues);
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
        validateTestReportTemplate(loadTest, template);
        return new ResolvedReportItem(loadTest, template);
    }

    private ResolvedReportItem resolveLegacyReportItem(UUID testUuid) {
        if (testUuid == null) {
            throw new ConfluenceOperationException("tests must not contain null elements");
        }
        LoadTest loadTest = resolveLoadTest(testUuid);
        ConfluenceTemplate template = resolveDefaultTemplate(loadTest);
        validateTestReportTemplate(loadTest, template);
        return new ResolvedReportItem(loadTest, template);
    }

    private LoadTest resolveLoadTest(UUID testUuid) {
        LoadTest loadTest = loadTestRepository.findById(testUuid)
                .orElseThrow(() -> new ConfluenceOperationException("Test with UUID " + testUuid + " not found"));
        return loadTest;
    }

    private ConfluenceTemplate resolveDefaultTemplate(LoadTest loadTest) {
        LoadTestType loadTestType = loadTest.type();
        String loadTestTypeCode = loadTest.effectiveTypeCode();
        if (!StringUtils.hasText(loadTestTypeCode) || LoadTestType.UNKNOWN.value().equalsIgnoreCase(loadTestTypeCode)) {
            throw new ConfluenceOperationException("Test type for test " + loadTest.id() + " is not defined");
        }
        try {
            String shortName = loadTestType == null || loadTestType == LoadTestType.UNKNOWN
                    ? confluenceTemplateSettingsService.resolveDefaultTemplateShortName(loadTestTypeCode)
                    : confluenceTemplateSettingsService.resolveDefaultTemplateShortName(loadTestType);
            return confluenceTemplateSettingsService.findActiveTemplateByShortName(shortName)
                    .orElseThrow(() -> new ConfluenceOperationException(
                            "Confluence report template is not configured for test type " + loadTestTypeCode));
        } catch (IllegalArgumentException ex) {
            throw new ConfluenceOperationException(ex.getMessage(), ex);
        }
    }

    private void validateTestReportTemplate(LoadTest loadTest, ConfluenceTemplate template) {
        if (template.templateType() != ConfluenceTemplateType.TEST) {
            throw new ConfluenceOperationException("Выбранный шаблон не является шаблоном отчета по тесту");
        }
        if (!StringUtils.hasText(template.templatePageId())) {
            throw new ConfluenceOperationException(
                    "У шаблона отчета %s не указан базовый шаблон в Confluence"
                            .formatted(formatLoadTestType(loadTest)));
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
            byte[] imageBytes = Base64.getDecoder().decode(panel.imageBase64());
            if (!isPng(imageBytes)) {
                throw new ConfluenceOperationException("Cached Grafana panel image is not a PNG");
            }
            return imageBytes;
        } catch (IllegalArgumentException ex) {
            throw new ConfluenceOperationException("Cached Grafana panel image could not be decoded", ex);
        }
    }

    private boolean isPng(byte[] imageBytes) {
        if (imageBytes == null || imageBytes.length < PNG_SIGNATURE.length) {
            return false;
        }
        for (int index = 0; index < PNG_SIGNATURE.length; index++) {
            if (imageBytes[index] != PNG_SIGNATURE[index]) {
                return false;
            }
        }
        return true;
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
            LoadTest loadTest,
            JiraReleasePlaceholderValues jiraReleaseValues) {
        JiraReleasePlaceholderValues safeJiraReleaseValues = safeJiraReleaseValues(jiraReleaseValues);
        String titleTemplate = StringUtils.hasText(template.reportTitle())
                ? template.reportTitle()
                : template.fullName();
        String resolved = getSafeBody(titleTemplate)
                .replace(RELEASE_PLACEHOLDER, releaseValue)
                .replace(JIRA_RELEASE_REQUIRED_LT_TABLE_PLACEHOLDER, "")
                .replace(JIRA_RELEASE_TASK_PLACEHOLDER, titlePlaceholder(safeJiraReleaseValues.task()))
                .replace(JIRA_RELEASE_LINK_PLACEHOLDER, "")
                .replace(JIRA_RELEASE_DISTRIBUTION_TABLE_PLACEHOLDER, "")
                .replace(TEST_START_DATE_PLACEHOLDER, titlePlaceholder(formatTestStartDate(loadTest)))
                .replace(TEST_START_TIME_PLACEHOLDER, titlePlaceholder(formatTestStartTime(loadTest)))
                .replace(TEST_END_DATE_PLACEHOLDER, titlePlaceholder(formatTestEndDate(loadTest)))
                .replace(TEST_END_TIME_PLACEHOLDER, titlePlaceholder(formatTestEndTime(loadTest)))
                .replace(TEST_START_PLACEHOLDER, titlePlaceholder(formatTestStart(loadTest)))
                .replace(TEST_END_PLACEHOLDER, titlePlaceholder(formatTestEnd(loadTest)))
                .replace(CONFIG_PLACEHOLDER, "")
                .replace(GRAPHICS_PLACEHOLDER, "")
                .replace(TEST_TYPE_PLACEHOLDER, "")
                .replace(TEST_PERIOD_PLACEHOLDER, "")
                .replace(TEST_STATUS_PLACEHOLDER, "")
                .replace(TEST_SUMMARY_PLACEHOLDER, "")
                .replace(TEST_NUMBER_PLACEHOLDER, "")
                .replace(TEST_TITLE_SHORT_PLACEHOLDER, "")
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
            LoadTest loadTest,
            JiraReleasePlaceholderValues jiraReleaseValues) {
        JiraReleasePlaceholderValues safeJiraReleaseValues = safeJiraReleaseValues(jiraReleaseValues);
        String body = getSafeBody(templateBody).replace(RELEASE_PLACEHOLDER, escape(releaseValue));
        if (body.contains(JIRA_RELEASE_REQUIRED_LT_TABLE_PLACEHOLDER)) {
            body = replaceSectionPlaceholder(
                    body,
                    JIRA_RELEASE_REQUIRED_LT_TABLE_PLACEHOLDER,
                    buildJiraRequiredLtTable(releaseValue));
        }
        body = body.replace(JIRA_RELEASE_TASK_PLACEHOLDER, escape(orPlaceholder(safeJiraReleaseValues.task())));
        body = body.replace(JIRA_RELEASE_LINK_PLACEHOLDER, buildJiraReleaseLink(safeJiraReleaseValues));
        body = replaceSectionPlaceholder(
                body,
                JIRA_RELEASE_DISTRIBUTION_TABLE_PLACEHOLDER,
                buildJiraReleaseDistributionTable(safeJiraReleaseValues));
        body = replaceSectionPlaceholder(body, CONFIG_PLACEHOLDER, configurationSection);
        body = replaceSectionPlaceholder(body, GRAPHICS_PLACEHOLDER, chartsSection);
        body = replaceTestPlaceholders(body, loadTest);
        body = hideReleaseReportMarkers(body);
        return normalizeStorageMarkup(body);
    }

    private String replaceTestPlaceholders(String body, LoadTest loadTest) {
        if (loadTest == null) {
            return body;
        }
        return getSafeBody(body)
                .replace(TEST_TYPE_PLACEHOLDER, escape(formatLoadTestType(loadTest)))
                .replace(TEST_START_DATE_PLACEHOLDER, escape(formatTestStartDate(loadTest)))
                .replace(TEST_START_TIME_PLACEHOLDER, escape(formatTestStartTime(loadTest)))
                .replace(TEST_END_DATE_PLACEHOLDER, escape(formatTestEndDate(loadTest)))
                .replace(TEST_END_TIME_PLACEHOLDER, escape(formatTestEndTime(loadTest)))
                .replace(TEST_START_PLACEHOLDER, escape(formatTestStart(loadTest)))
                .replace(TEST_END_PLACEHOLDER, escape(formatTestEnd(loadTest)))
                .replace(TEST_PERIOD_PLACEHOLDER, escape(formatTestPeriod(loadTest)))
                .replace(TEST_STATUS_PLACEHOLDER, escape(orPlaceholder(loadTest.reportStatus())))
                .replace(TEST_SUMMARY_PLACEHOLDER, escape(orPlaceholder(loadTest.reportSummary())));
    }

    private JiraReleasePlaceholderValues resolveJiraReleaseValues(UUID testUuid) {
        if (testUuid == null) {
            return emptyJiraReleaseValues();
        }
        DropAppConfigurationSnapshotDetailsDto details = kubernetesConfigurationService.getConfigurationDetails(testUuid);
        if (details == null) {
            return emptyJiraReleaseValues();
        }
        return new JiraReleasePlaceholderValues(
                normalizeOptionalText(details.jiraReleaseIssueKey()),
                normalizeOptionalText(details.jiraReleaseIssueUrl()),
                details.jiraReleaseDistributions() != null ? details.jiraReleaseDistributions() : List.of());
    }

    private JiraReleasePlaceholderValues resolveJiraReleaseValuesForTests(List<UUID> testUuids) {
        if (testUuids == null || testUuids.isEmpty()) {
            return emptyJiraReleaseValues();
        }
        return testUuids.stream()
                .filter(Objects::nonNull)
                .map(this::resolveJiraReleaseValues)
                .filter(this::hasJiraReleaseValues)
                .findFirst()
                .orElseGet(this::emptyJiraReleaseValues);
    }

    private JiraReleasePlaceholderValues resolveLinkedJiraReleaseValues(ConfluenceReleaseReportLink link) {
        if (link == null) {
            return emptyJiraReleaseValues();
        }
        return resolveJiraReleaseValues(link.testUuid());
    }

    private JiraReleasePlaceholderValues safeJiraReleaseValues(JiraReleasePlaceholderValues values) {
        if (values == null) {
            return emptyJiraReleaseValues();
        }
        return new JiraReleasePlaceholderValues(
                values.task(),
                values.url(),
                values.distributions() != null ? values.distributions() : List.of());
    }

    private JiraReleasePlaceholderValues emptyJiraReleaseValues() {
        return new JiraReleasePlaceholderValues(null, null, List.of());
    }

    private boolean hasJiraReleaseValues(JiraReleasePlaceholderValues values) {
        return values != null
                && (StringUtils.hasText(values.task())
                || StringUtils.hasText(values.url())
                || (values.distributions() != null && !values.distributions().isEmpty()));
    }

    private String buildJiraReleaseLink(JiraReleasePlaceholderValues values) {
        JiraReleasePlaceholderValues safeValues = safeJiraReleaseValues(values);
        String task = orPlaceholder(safeValues.task());
        return buildExternalLink(safeValues.url(), task);
    }

    private String buildJiraReleaseDistributionTable(JiraReleasePlaceholderValues values) {
        JiraReleasePlaceholderValues safeValues = safeJiraReleaseValues(values);
        List<JiraReleaseDistributionDto> distributions = safeValues.distributions();
        if (distributions == null || distributions.isEmpty()) {
            return "<p>" + escape(REPORT_PLACEHOLDER_TEXT) + "</p>";
        }
        StringBuilder bodyBuilder = new StringBuilder();
        bodyBuilder.append("<table>\n")
                .append("<tr>")
                .append("<th>Сервис в DPM</th>")
                .append("<th>Дистрибутив в Nexus</th>")
                .append("</tr>\n");
        for (JiraReleaseDistributionDto distribution : distributions) {
            bodyBuilder.append("<tr>")
                    .append("<td>").append(buildServiceLink(distribution)).append("</td>")
                    .append("<td>").append(buildDistributionLink(distribution)).append("</td>")
                    .append("</tr>\n");
        }
        bodyBuilder.append("</table>\n");
        return bodyBuilder.toString();
    }

    private String buildJiraRequiredLtTable(String releaseValue) {
        String jql = buildJiraRequiredLtJql(releaseValue);
        if (!StringUtils.hasText(jql)) {
            return "<p>" + escape(REPORT_PLACEHOLDER_TEXT) + "</p>";
        }
        return "<ac:structured-macro ac:name=\"jira\">\n"
                + "<ac:parameter ac:name=\"columns\">" + escape(JIRA_REQUIRED_LT_COLUMNS) + "</ac:parameter>\n"
                + "<ac:parameter ac:name=\"jqlQuery\">" + escape(jql) + "</ac:parameter>\n"
                + "<ac:parameter ac:name=\"maximumIssues\">" + JIRA_REQUIRED_LT_MAX_ISSUES + "</ac:parameter>\n"
                + "</ac:structured-macro>\n";
    }

    private String buildJiraRequiredLtJql(String releaseValue) {
        String release = normalizeOptionalText(releaseValue);
        String projectName = configurationProvider.findValue(JIRA_SERVICE, JIRA_PROJECT_NAME_KEY)
                .map(StringUtils::trimWhitespace)
                .filter(StringUtils::hasText)
                .orElse(null);
        if (!StringUtils.hasText(release) || !StringUtils.hasText(projectName) || "—".equals(release)) {
            return null;
        }
        String releaseLabel = buildJiraLoadTestReleaseLabel(release);
        return ("project = \"%s\" AND issuetype in (Story, Bug) AND (affectedVersion = %s OR fixVersion = %s OR labels = %s) "
                + "AND \"%s\" in (%s) ORDER BY priority, project, affectedVersion")
                .formatted(
                        escapeJql(projectName),
                        formatJqlValue(release),
                        formatJqlValue(release),
                        formatJqlValue(releaseLabel),
                        escapeJql(JIRA_REQUIRED_LT_FIELD),
                        formatJqlValue(JIRA_REQUIRED_LT_VALUE));
    }

    private String buildJiraLoadTestReleaseLabel(String release) {
        String normalizedRelease = normalizeOptionalText(release);
        if (!StringUtils.hasText(normalizedRelease)) {
            return "Id";
        }
        String[] parts = normalizedRelease.split("\\.");
        if (parts.length >= 3 && trailingVersionPartsAreZero(parts, 2)
                && StringUtils.hasText(parts[0]) && StringUtils.hasText(parts[1])) {
            return "Id" + parts[0] + "." + parts[1];
        }
        return "Id" + normalizedRelease;
    }

    private boolean trailingVersionPartsAreZero(String[] parts, int fromIndex) {
        for (int index = fromIndex; index < parts.length; index++) {
            if (!parts[index].matches("0+")) {
                return false;
            }
        }
        return true;
    }

    private String formatJqlValue(String value) {
        String normalizedValue = normalizeOptionalText(value);
        if (!StringUtils.hasText(normalizedValue)) {
            return "\"\"";
        }
        if (normalizedValue.matches("[\\p{L}\\p{N}._-]+")) {
            return escapeJql(normalizedValue);
        }
        return "\"" + escapeJql(normalizedValue) + "\"";
    }

    private String escapeJql(String value) {
        return (value == null ? "" : value)
                .replace("\\", "\\\\")
                .replace("\"", "\\\"");
    }

    private String buildServiceLink(JiraReleaseDistributionDto distribution) {
        String serviceName = orPlaceholder(distribution.serviceName());
        return buildExternalLink(distribution.serviceUrl(), serviceName);
    }

    private String buildDistributionLink(JiraReleaseDistributionDto distribution) {
        String distributionName = orPlaceholder(distribution.distributionName());
        return buildExternalLink(distribution.distributionUrl(), distributionName);
    }

    private String buildExternalLink(String url, String text) {
        String linkText = orPlaceholder(text);
        if (!StringUtils.hasText(url)) {
            return escape(linkText);
        }
        return "<a href=\"" + escape(url) + "\">" + escape(linkText) + "</a>";
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
                : defaultTestTitle(loadTest);
        String testTitleShort = StringUtils.hasText(item.testTitleShort())
                ? item.testTitleShort().trim()
                : testTitle;
        return new ConfluenceReleaseReportLink(
                null,
                releasePageId,
                item.testUuid(),
                item.reportPageId().trim(),
                StringUtils.hasText(item.reportPageTitle()) ? item.reportPageTitle().trim() : testTitle,
                StringUtils.hasText(item.reportPageUrl()) ? item.reportPageUrl().trim() : confluenceClient.buildPageUrl(item.reportPageId()),
                StringUtils.hasText(item.testNumber()) ? item.testNumber().trim() : "",
                testTitle,
                testTitleShort,
                formatLoadTestType(loadTest),
                formatTestPeriod(loadTest),
                orPlaceholder(loadTest.reportStatus()),
                orPlaceholder(loadTest.reportSummary()),
                null);
    }

    private ReleaseReportsBlock extractReleaseReportsBlock(String body) {
        String safeBody = getSafeBody(body);
        MarkerBlock startMarkerBlock = findMarkerBlock(safeBody, RELEASE_REPORTS_START_MARKER);
        MarkerBlock endMarkerBlock = findMarkerBlock(safeBody, RELEASE_REPORTS_END_MARKER);
        if (startMarkerBlock != null
                && endMarkerBlock != null
                && endMarkerBlock.start() >= startMarkerBlock.end()) {
            return new ReleaseReportsBlock(
                    startMarkerBlock.end(),
                    endMarkerBlock.start(),
                    safeBody.substring(startMarkerBlock.end(), endMarkerBlock.start()));
        }

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
            List<ConfluenceReleaseReportLink> links,
            String spaceKey) {
        String renderedLinks = renderReleaseReportLinks(itemTemplate, links, spaceKey);
        return body.substring(0, reportsBlock.replacementStart())
                + renderedLinks
                + body.substring(reportsBlock.replacementEnd());
    }

    private String hideReleaseReportMarkers(String body) {
        String hiddenBody = hideReleaseReportMarker(body, RELEASE_REPORTS_START_MARKER);
        return hideReleaseReportMarker(hiddenBody, RELEASE_REPORTS_END_MARKER);
    }

    private String hideReleaseReportMarker(String body, String marker) {
        String safeBody = getSafeBody(body);
        StringBuilder hiddenBody = new StringBuilder();
        int appendFrom = 0;
        int searchFrom = 0;
        int markerIndex;
        while ((markerIndex = safeBody.indexOf(marker, searchFrom)) >= 0) {
            MarkerBlock markerBlock = findMarkerBlock(safeBody, marker, markerIndex);
            if (markerBlock == null || markerBlock.start() < appendFrom) {
                searchFrom = markerIndex + marker.length();
                continue;
            }
            hiddenBody.append(safeBody, appendFrom, markerBlock.start());
            hiddenBody.append(hiddenMarkerBlock(markerBlock, marker));
            appendFrom = markerBlock.end();
            searchFrom = markerBlock.end();
        }
        hiddenBody.append(safeBody.substring(appendFrom));
        return hiddenBody.toString();
    }

    private MarkerBlock findMarkerBlock(String body, String marker) {
        String safeBody = getSafeBody(body);
        int searchFrom = 0;
        int markerIndex;
        while ((markerIndex = safeBody.indexOf(marker, searchFrom)) >= 0) {
            MarkerBlock markerBlock = findMarkerBlock(safeBody, marker, markerIndex);
            if (markerBlock != null) {
                return markerBlock;
            }
            searchFrom = markerIndex + marker.length();
        }
        return null;
    }

    private MarkerBlock findMarkerBlock(String body, String marker, int markerIndex) {
        List<MarkerBlockCandidate> candidates = new ArrayList<>();
        Matcher openTagMatcher = markerBlockOpenPattern().matcher(body);
        while (openTagMatcher.find() && openTagMatcher.end() <= markerIndex) {
            candidates.add(new MarkerBlockCandidate(
                    openTagMatcher.start(),
                    openTagMatcher.end(),
                    openTagMatcher.group(1),
                    openTagMatcher.group(2)));
        }

        for (int index = candidates.size() - 1; index >= 0; index--) {
            MarkerBlockCandidate candidate = candidates.get(index);
            Pattern closePattern = Pattern.compile("(?is)</\\s*" + Pattern.quote(candidate.tagName()) + "\\s*>");
            Matcher closeTagMatcher = closePattern.matcher(body);
            closeTagMatcher.region(markerIndex + marker.length(), body.length());
            if (!closeTagMatcher.find()) {
                continue;
            }
            String content = body.substring(candidate.openEnd(), closeTagMatcher.start());
            if (content.contains(marker) && marker.equals(normalizeMarkerBlockContent(content))) {
                return new MarkerBlock(
                        candidate.start(),
                        closeTagMatcher.end(),
                        candidate.tagName(),
                        candidate.attributes());
            }
        }
        return null;
    }

    private Pattern markerBlockOpenPattern() {
        return Pattern.compile("(?is)<(" + PLACEHOLDER_BLOCK_TAGS + ")([^>]*)>");
    }

    private String hiddenMarkerBlock(MarkerBlock markerBlock, String marker) {
        return "<"
                + markerBlock.tagName()
                + withHiddenMarkerStyle(markerBlock.attributes())
                + ">"
                + marker
                + "</"
                + markerBlock.tagName()
                + ">";
    }

    private String normalizeMarkerBlockContent(String content) {
        String text = BR_TAG_PATTERN.matcher(getSafeBody(content)).replaceAll(" ");
        text = text.replace("&nbsp;", " ").replace("&#160;", " ");
        text = text.replaceAll("(?is)<[^>]+>", "");
        return HtmlUtils.htmlUnescape(text).trim().replaceAll("\\s+", " ");
    }

    private String withHiddenMarkerStyle(String attributes) {
        String safeAttributes = attributes == null ? "" : attributes;
        Matcher styleMatcher = STYLE_ATTRIBUTE_PATTERN.matcher(safeAttributes);
        if (!styleMatcher.find()) {
            return safeAttributes + " style=\"display: none;\"";
        }

        String styleValue = styleMatcher.group(3);
        if (DISPLAY_NONE_STYLE_PATTERN.matcher(styleValue).find()) {
            return safeAttributes;
        }

        String separator = StringUtils.hasText(styleValue) && !styleValue.trim().endsWith(";") ? "; " : "";
        String replacement = styleMatcher.group(1)
                + styleMatcher.group(2)
                + styleValue
                + separator
                + "display: none;"
                + styleMatcher.group(2);
        return styleMatcher.replaceFirst(Matcher.quoteReplacement(replacement));
    }

    private String renderReleaseReportLinks(
            String itemTemplate,
            List<ConfluenceReleaseReportLink> links,
            String spaceKey) {
        String template = StringUtils.hasText(itemTemplate)
                ? itemTemplate
                : "<h3>@test_number @test_title</h3><p>@test_status</p><p>@test_summary</p><p>@test_report_link</p>";
        StringBuilder bodyBuilder = new StringBuilder();
        for (int index = 0; index < links.size(); index++) {
            ConfluenceReleaseReportLink link = links.get(index);
            ReleaseReportPlaceholderValues values = buildReleaseReportPlaceholderValues(link, index);
            String rendered = template;
            if (rendered.contains(JIRA_RELEASE_REQUIRED_LT_TABLE_PLACEHOLDER)) {
                rendered = replaceSectionPlaceholder(
                        rendered,
                        JIRA_RELEASE_REQUIRED_LT_TABLE_PLACEHOLDER,
                        buildJiraRequiredLtTable(values.release()));
            }
            rendered = rendered
                    .replace(TEST_NUMBER_PLACEHOLDER, escape(expandReleaseReportText(values.testNumber(), values)))
                    .replace(TEST_TITLE_SHORT_PLACEHOLDER, escape(values.testTitleShort()))
                    .replace(TEST_TITLE_PLACEHOLDER, escape(values.testTitle()))
                    .replace(JIRA_RELEASE_TASK_PLACEHOLDER, escape(expandReleaseReportText(values.jiraReleaseTask(), values)))
                    .replace(JIRA_RELEASE_LINK_PLACEHOLDER, buildJiraReleaseLink(
                            new JiraReleasePlaceholderValues(
                                    values.jiraReleaseTask(),
                                    values.jiraReleaseUrl(),
                                    values.jiraReleaseDistributions())))
                    .replace(TEST_TYPE_PLACEHOLDER, escape(expandReleaseReportText(values.testType(), values)))
                    .replace(TEST_START_DATE_PLACEHOLDER, escape(expandReleaseReportText(values.testStartDate(), values)))
                    .replace(TEST_START_TIME_PLACEHOLDER, escape(expandReleaseReportText(values.testStartTime(), values)))
                    .replace(TEST_END_DATE_PLACEHOLDER, escape(expandReleaseReportText(values.testEndDate(), values)))
                    .replace(TEST_END_TIME_PLACEHOLDER, escape(expandReleaseReportText(values.testEndTime(), values)))
                    .replace(TEST_START_PLACEHOLDER, escape(expandReleaseReportText(values.testStart(), values)))
                    .replace(TEST_END_PLACEHOLDER, escape(expandReleaseReportText(values.testEnd(), values)))
                    .replace(TEST_PERIOD_PLACEHOLDER, escape(expandReleaseReportText(values.testPeriod(), values)))
                    .replace(TEST_STATUS_PLACEHOLDER, escape(expandReleaseReportText(values.testStatus(), values)))
                    .replace(TEST_SUMMARY_PLACEHOLDER, escape(expandReleaseReportText(values.testSummary(), values)))
                    .replace(TEST_REPORT_LINK_PLACEHOLDER, buildReportPageLink(link, spaceKey));
            rendered = replaceSectionPlaceholder(
                    rendered,
                    JIRA_RELEASE_DISTRIBUTION_TABLE_PLACEHOLDER,
                    buildJiraReleaseDistributionTable(new JiraReleasePlaceholderValues(
                            values.jiraReleaseTask(),
                            values.jiraReleaseUrl(),
                            values.jiraReleaseDistributions())));
            bodyBuilder.append(rendered);
        }
        return bodyBuilder.toString();
    }

    private ReleaseReportPlaceholderValues buildReleaseReportPlaceholderValues(
            ConfluenceReleaseReportLink link,
            int index) {
        String release = resolveLinkedRelease(link);
        JiraReleasePlaceholderValues jiraReleaseValues = resolveLinkedJiraReleaseValues(link);
        LoadTest loadTest = findLinkedLoadTest(link).orElse(null);
        String testNumber = orDefault(link.testNumber(), String.valueOf(index + 1));
        String testType = orPlaceholder(link.testType());
        String testStart = formatTestStart(loadTest);
        String testStartDate = formatTestStartDate(loadTest);
        String testStartTime = formatTestStartTime(loadTest);
        String testEnd = formatTestEnd(loadTest);
        String testEndDate = formatTestEndDate(loadTest);
        String testEndTime = formatTestEndTime(loadTest);
        String testPeriod = orPlaceholder(link.testPeriod());
        String testStatus = orPlaceholder(link.testStatus());
        String testSummary = orPlaceholder(link.testSummary());
        ReleaseReportPlaceholderValues rawValues = new ReleaseReportPlaceholderValues(
                release,
                orPlaceholder(jiraReleaseValues.task()),
                jiraReleaseValues.url(),
                jiraReleaseValues.distributions(),
                testNumber,
                "",
                "",
                testType,
                testStart,
                testStartDate,
                testStartTime,
                testEnd,
                testEndDate,
                testEndTime,
                testPeriod,
                testStatus,
                testSummary);
        String testTitle = expandReleaseReportText(orPlaceholder(link.testTitle()), rawValues);
        ReleaseReportPlaceholderValues valuesWithTitle = new ReleaseReportPlaceholderValues(
                release,
                orPlaceholder(jiraReleaseValues.task()),
                jiraReleaseValues.url(),
                jiraReleaseValues.distributions(),
                testNumber,
                testTitle,
                "",
                testType,
                testStart,
                testStartDate,
                testStartTime,
                testEnd,
                testEndDate,
                testEndTime,
                testPeriod,
                testStatus,
                testSummary);
        String testTitleShort = expandReleaseReportText(orDefault(link.testTitleShort(), testTitle), valuesWithTitle);
        return new ReleaseReportPlaceholderValues(
                release,
                orPlaceholder(jiraReleaseValues.task()),
                jiraReleaseValues.url(),
                jiraReleaseValues.distributions(),
                testNumber,
                testTitle,
                testTitleShort,
                testType,
                testStart,
                testStartDate,
                testStartTime,
                testEnd,
                testEndDate,
                testEndTime,
                testPeriod,
                testStatus,
                testSummary);
    }

    private String expandReleaseReportText(String value, ReleaseReportPlaceholderValues values) {
        String safeValue = orPlaceholder(value);
        return safeValue
                .replace(RELEASE_PLACEHOLDER, values.release())
                .replace(JIRA_RELEASE_REQUIRED_LT_TABLE_PLACEHOLDER, "")
                .replace(JIRA_RELEASE_TASK_PLACEHOLDER, values.jiraReleaseTask())
                .replace(JIRA_RELEASE_LINK_PLACEHOLDER, values.jiraReleaseTask())
                .replace(JIRA_RELEASE_DISTRIBUTION_TABLE_PLACEHOLDER, "")
                .replace(TEST_NUMBER_PLACEHOLDER, values.testNumber())
                .replace(TEST_TITLE_SHORT_PLACEHOLDER, values.testTitleShort())
                .replace(TEST_TITLE_PLACEHOLDER, values.testTitle())
                .replace(TEST_TYPE_PLACEHOLDER, values.testType())
                .replace(TEST_START_DATE_PLACEHOLDER, values.testStartDate())
                .replace(TEST_START_TIME_PLACEHOLDER, values.testStartTime())
                .replace(TEST_END_DATE_PLACEHOLDER, values.testEndDate())
                .replace(TEST_END_TIME_PLACEHOLDER, values.testEndTime())
                .replace(TEST_START_PLACEHOLDER, values.testStart())
                .replace(TEST_END_PLACEHOLDER, values.testEnd())
                .replace(TEST_PERIOD_PLACEHOLDER, values.testPeriod())
                .replace(TEST_STATUS_PLACEHOLDER, values.testStatus())
                .replace(TEST_SUMMARY_PLACEHOLDER, values.testSummary())
                .replace(CONFIG_PLACEHOLDER, "")
                .replace(GRAPHICS_PLACEHOLDER, "")
                .replace(TEST_REPORT_LINK_PLACEHOLDER, "");
    }

    private String resolveLinkedRelease(ConfluenceReleaseReportLink link) {
        if (link == null || link.testUuid() == null) {
            return REPORT_PLACEHOLDER_TEXT;
        }
        return resolveReleaseValue(kubernetesConfigurationService.getConfigurations(link.testUuid()));
    }

    private String buildReportPageLink(ConfluenceReleaseReportLink link, String spaceKey) {
        if (!StringUtils.hasText(link.reportPageTitle())) {
            String url = StringUtils.hasText(link.reportPageUrl())
                    ? link.reportPageUrl()
                    : confluenceClient.buildPageUrl(link.reportPageId());
            return buildExternalLink(url, "Отчет по тесту");
        }
        String title = link.reportPageTitle().trim();
        String spaceKeyAttribute = StringUtils.hasText(spaceKey)
                ? " ri:space-key=\"" + escape(spaceKey) + "\""
                : "";
        return "<ac:link><ri:page ri:content-title=\""
                + escape(title)
                + "\""
                + spaceKeyAttribute
                + " /><ac:plain-text-link-body><![CDATA["
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

    private String formatTestStartDate(LoadTest loadTest) {
        if (loadTest == null) {
            return REPORT_PLACEHOLDER_TEXT;
        }
        String value = formatInstantDate(loadTest.start());
        return StringUtils.hasText(value) ? value : REPORT_PLACEHOLDER_TEXT;
    }

    private String formatTestStartTime(LoadTest loadTest) {
        if (loadTest == null) {
            return REPORT_PLACEHOLDER_TEXT;
        }
        String value = formatInstantTime(loadTest.start());
        return StringUtils.hasText(value) ? value : REPORT_PLACEHOLDER_TEXT;
    }

    private String formatTestEnd(LoadTest loadTest) {
        if (loadTest == null) {
            return REPORT_PLACEHOLDER_TEXT;
        }
        String value = formatInstant(loadTest.end());
        return StringUtils.hasText(value) ? value : REPORT_PLACEHOLDER_TEXT;
    }

    private String formatTestEndDate(LoadTest loadTest) {
        if (loadTest == null) {
            return REPORT_PLACEHOLDER_TEXT;
        }
        String value = formatInstantDate(loadTest.end());
        return StringUtils.hasText(value) ? value : REPORT_PLACEHOLDER_TEXT;
    }

    private String formatTestEndTime(LoadTest loadTest) {
        if (loadTest == null) {
            return REPORT_PLACEHOLDER_TEXT;
        }
        String value = formatInstantTime(loadTest.end());
        return StringUtils.hasText(value) ? value : REPORT_PLACEHOLDER_TEXT;
    }

    private Optional<LoadTest> findLinkedLoadTest(ConfluenceReleaseReportLink link) {
        if (link == null || link.testUuid() == null) {
            return Optional.empty();
        }
        return loadTestRepository.findById(link.testUuid());
    }

    private String titlePlaceholder(String value) {
        return StringUtils.hasText(value) && !REPORT_PLACEHOLDER_TEXT.equals(value) ? value : "";
    }

    private String formatInstant(Instant instant) {
        return DateTimeViewUtils.formatInstant(instant);
    }

    private String formatInstantDate(Instant instant) {
        return DateTimeViewUtils.formatInstantDate(instant);
    }

    private String formatInstantTime(Instant instant) {
        return DateTimeViewUtils.formatInstantTime(instant);
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

    private String formatLoadTestType(LoadTest loadTest) {
        if (loadTest == null) {
            return "UNKNOWN";
        }
        String typeName = loadTest.effectiveTypeName();
        if (StringUtils.hasText(typeName) && !"UNKNOWN".equalsIgnoreCase(typeName.trim())) {
            return typeName.trim();
        }
        return formatLoadTestType(loadTest.type());
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

    private String defaultTestTitle(LoadTest loadTest) {
        if (loadTest == null) {
            return "Отчет по нагрузочному тесту";
        }
        if (loadTest.type() != null && loadTest.type() != LoadTestType.UNKNOWN) {
            return defaultTestTitle(loadTest.type());
        }
        String typeName = loadTest.effectiveTypeName();
        if (StringUtils.hasText(typeName) && !"UNKNOWN".equalsIgnoreCase(typeName.trim())) {
            return typeName.trim();
        }
        return "Отчет по нагрузочному тесту";
    }

    private String orPlaceholder(String value) {
        return orDefault(value, REPORT_PLACEHOLDER_TEXT);
    }

    private String orDefault(String value, String fallback) {
        String normalized = value == null ? "" : value.trim();
        return StringUtils.hasText(normalized) ? normalized : fallback;
    }

    private String normalizeOptionalText(String value) {
        String normalized = value == null ? "" : value.trim();
        return StringUtils.hasText(normalized) ? normalized : null;
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

    private ConfluencePageTreeNode buildCurrentPageTree(
            ConfluenceCredentials credentials,
            String pageId,
            String pageTitle,
            Set<String> visitedPageIds) {
        visitedPageIds.add(pageId);
        List<ConfluencePageTreeNode> children = new ArrayList<>();
        confluenceRequestThrottler.awaitReportParentPagesRequestSlot();
        for (ConfluencePageSummary childPage : confluenceClient.listChildPages(credentials, pageId)) {
            if (!StringUtils.hasText(childPage.id()) || visitedPageIds.contains(childPage.id())) {
                continue;
            }
            children.add(buildCurrentPageTree(
                    credentials,
                    childPage.id(),
                    StringUtils.hasText(childPage.title()) ? childPage.title() : childPage.id(),
                    visitedPageIds));
        }
        return new ConfluencePageTreeNode(
                pageId,
                StringUtils.hasText(pageTitle) ? pageTitle : pageId,
                confluenceClient.buildPageUrl(pageId),
                List.copyOf(children));
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

    private String uploadAttachmentWithRetry(
            ConfluenceCredentials credentials,
            String pageId,
            String attachmentName,
            byte[] imageBytes) {
        String uploadedAttachmentName = uploadAttachmentWithRetry(
                credentials,
                pageId,
                () -> confluenceClient.uploadAttachment(credentials, pageId, attachmentName, imageBytes));
        return StringUtils.hasText(uploadedAttachmentName) ? uploadedAttachmentName : attachmentName;
    }

    private String uploadAttachmentWithRetry(
            ConfluenceCredentials credentials,
            String pageId,
            String attachmentName,
            byte[] bytes,
            MediaType mediaType) {
        String uploadedAttachmentName = uploadAttachmentWithRetry(
                credentials,
                pageId,
                () -> confluenceClient.uploadAttachment(credentials, pageId, attachmentName, bytes, mediaType));
        return StringUtils.hasText(uploadedAttachmentName) ? uploadedAttachmentName : attachmentName;
    }

    private String uploadAttachmentWithRetry(
            ConfluenceCredentials credentials,
            String pageId,
            AttachmentUpload upload) {
        final int maxAttempts = 5;
        final long delayMillis = 700L;
        ConfluenceClientException lastError = null;

        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                waitForPageAvailability(credentials, pageId);
                return upload.upload();
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
        String uploadedAttachmentName = uploadAttachmentWithRetry(
                credentials,
                pageId,
                archive.fileName(),
                archive.content(),
                resolveArchiveMediaType(archive.contentType()));
        attachmentNames.add(uploadedAttachmentName);
        return new ConfigurationArchiveAttachment(uploadedAttachmentName);
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

    private record ResolvedReportItem(LoadTest loadTest, ConfluenceTemplate template) {}

    private record GrafanaImageDimensions(int width, int height) {}

    private record RenderedChartAttachment(String title, String description, String attachmentName) {}

    private record ConfigurationArchiveAttachment(String attachmentName) {}

    private record ReleaseReportsBlock(int replacementStart, int replacementEnd, String itemTemplate) {}

    private record MarkerBlock(int start, int end, String tagName, String attributes) {
        private MarkerBlock {
            attributes = attributes == null ? "" : attributes;
        }
    }

    private record MarkerBlockCandidate(int start, int openEnd, String tagName, String attributes) {
        private MarkerBlockCandidate {
            attributes = attributes == null ? "" : attributes;
        }
    }

    private record JiraReleasePlaceholderValues(
            String task,
            String url,
            List<JiraReleaseDistributionDto> distributions) {}

    private record ReleaseReportPlaceholderValues(
            String release,
            String jiraReleaseTask,
            String jiraReleaseUrl,
            List<JiraReleaseDistributionDto> jiraReleaseDistributions,
            String testNumber,
            String testTitle,
            String testTitleShort,
            String testType,
            String testStart,
            String testStartDate,
            String testStartTime,
            String testEnd,
            String testEndDate,
            String testEndTime,
            String testPeriod,
            String testStatus,
            String testSummary) {}

    @FunctionalInterface
    private interface AttachmentUpload {
        String upload();
    }
}
