package com.example.chronicler.service;

import com.example.chronicler.dto.settings.GrafanaDashboardDetailsDto;
import com.example.chronicler.dto.settings.GrafanaDashboardPanelDto;
import com.example.chronicler.model.ConfluenceTemplateChart;
import com.example.chronicler.model.ConfluenceTemplate;
import com.example.chronicler.model.ConfluenceTemplateType;
import com.example.chronicler.model.LoadTestType;
import com.example.chronicler.repository.ConfluenceTemplateChartRepository;
import com.example.chronicler.repository.ConfluenceTemplateRepository;
import com.example.chronicler.repository.GrafanaDashboardPanelRepository;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
public class ConfluenceTemplateSettingsService {

    private static final Pattern PAGE_ID_QUERY_PATTERN = Pattern.compile("[?&]pageId=([0-9]+)");
    private static final Pattern PAGE_ID_PATH_PATTERN = Pattern.compile("/pages/(?:viewpage\\.action/)?([0-9]+)");
    private static final Pattern PAGE_ID_VALUE_PATTERN = Pattern.compile("[0-9]+");
    private static final String TEMPLATE_SUFFIX = "_template";
    private static final String RELEASE_TEMPLATE_SHORT_NAME = "release_template";

    private final ConfluenceTemplateRepository templateRepository;
    private final ConfluenceTemplateChartRepository chartRepository;
    private final GrafanaDashboardPanelRepository grafanaDashboardPanelRepository;
    private final GrafanaDashboardSettingsService grafanaDashboardSettingsService;

    public ConfluenceTemplateSettingsService(
            ConfluenceTemplateRepository templateRepository,
            ConfluenceTemplateChartRepository chartRepository,
            GrafanaDashboardPanelRepository grafanaDashboardPanelRepository,
            GrafanaDashboardSettingsService grafanaDashboardSettingsService) {
        this.templateRepository = templateRepository;
        this.chartRepository = chartRepository;
        this.grafanaDashboardPanelRepository = grafanaDashboardPanelRepository;
        this.grafanaDashboardSettingsService = grafanaDashboardSettingsService;
    }

    public List<ConfluenceTemplate> listTemplates() {
        Map<Integer, List<ConfluenceTemplateChart>> chartsByTemplateId =
                chartRepository.findAllGroupedByTemplateId();
        return templateRepository.findAllActive().stream()
                .map(template -> withCharts(template, chartsByTemplateId.get(template.id())))
                .toList();
    }

    public List<PanelOption> listPanelOptions() {
        return grafanaDashboardSettingsService.listDashboards().stream()
                .flatMap(this::toPanelOptions)
                .sorted(Comparator.comparing(PanelOption::code, String.CASE_INSENSITIVE_ORDER))
                .toList();
    }

    public Optional<ConfluenceTemplate> findActiveTemplateByShortName(String shortName) {
        return templateRepository.findActiveByShortName(shortName)
                .map(template -> withCharts(template, chartRepository.findByTemplateId(template.id())));
    }

    public ConfluenceTemplate getActiveTemplate(int id) {
        ConfluenceTemplate template = templateRepository.findActiveById(id)
                .orElseThrow(() -> new IllegalArgumentException("Шаблон отчета не найден"));
        return withCharts(template, chartRepository.findByTemplateId(id));
    }

    public ConfluenceTemplate resolveDefaultTemplate(LoadTestType loadTestType) {
        String shortName = resolveDefaultTemplateShortName(loadTestType);
        return findActiveTemplateByShortName(shortName)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Confluence report template is not configured for test type " + loadTestType.value()));
    }

    public String resolveDefaultTemplateShortName(LoadTestType loadTestType) {
        if (loadTestType == null || loadTestType == LoadTestType.UNKNOWN) {
            throw new IllegalArgumentException("Тип теста не определен");
        }
        return loadTestType.value() + TEMPLATE_SUFFIX;
    }

    public String resolveReleaseTemplateShortName() {
        return RELEASE_TEMPLATE_SHORT_NAME;
    }

    public Optional<LoadTestType> resolveTemplateTestType(ConfluenceTemplate template) {
        if (template == null || template.templateType() != ConfluenceTemplateType.TEST) {
            return Optional.empty();
        }
        String shortName = StringUtils.trimWhitespace(template.shortName());
        if (!StringUtils.hasText(shortName) || !shortName.endsWith(TEMPLATE_SUFFIX)) {
            return Optional.empty();
        }
        LoadTestType testType = LoadTestType.fromDatabaseValue(
                shortName.substring(0, shortName.length() - TEMPLATE_SUFFIX.length()));
        return testType == LoadTestType.UNKNOWN ? Optional.empty() : Optional.of(testType);
    }

    @Transactional
    public ConfluenceTemplate createTemplate(TemplateSaveCommand command) {
        return createTemplate(command, true);
    }

    @Transactional
    public ConfluenceTemplate createTemplate(TemplateSaveCommand command, boolean validateCharts) {
        SanitizedTemplate sanitized = sanitize(command, validateCharts);
        validateUniqueNames(sanitized.shortName(), null);
        int templateId = templateRepository.create(
                sanitized.shortName(),
                sanitized.fullName(),
                sanitized.reportTitle(),
                sanitized.reportTitleShort(),
                sanitized.templateType(),
                sanitized.templatePageId(),
                sanitized.text());
        chartRepository.replaceForTemplate(templateId, toCharts(templateId, sanitized.charts()));
        return getActiveTemplate(templateId);
    }

    @Transactional
    public ConfluenceTemplate updateTemplate(int id, TemplateSaveCommand command) {
        return updateTemplate(id, command, true);
    }

    @Transactional
    public ConfluenceTemplate updateTemplate(int id, TemplateSaveCommand command, boolean validateCharts) {
        templateRepository.findActiveById(id)
                .orElseThrow(() -> new IllegalArgumentException("Шаблон отчета не найден"));
        SanitizedTemplate sanitized = sanitize(command, validateCharts);
        validateUniqueNames(sanitized.shortName(), id);
        templateRepository.update(
                id,
                sanitized.shortName(),
                sanitized.fullName(),
                sanitized.reportTitle(),
                sanitized.reportTitleShort(),
                sanitized.templateType(),
                sanitized.templatePageId(),
                sanitized.text());
        chartRepository.replaceForTemplate(id, toCharts(id, sanitized.charts()));
        return getActiveTemplate(id);
    }

    @Transactional
    public void deleteTemplate(int id) {
        templateRepository.softDelete(id);
    }

    private ConfluenceTemplate withCharts(ConfluenceTemplate template, List<ConfluenceTemplateChart> charts) {
        return new ConfluenceTemplate(
                template.id(),
                template.shortName(),
                template.fullName(),
                template.reportTitle(),
                template.reportTitleShort(),
                template.templateType(),
                template.templatePageId(),
                template.text(),
                charts == null ? List.of() : List.copyOf(charts));
    }

    private java.util.stream.Stream<PanelOption> toPanelOptions(GrafanaDashboardDetailsDto dashboard) {
        String dashboardUid = StringUtils.trimWhitespace(dashboard.uid());
        String dashboardName = StringUtils.hasText(dashboard.title())
                ? dashboard.title().trim()
                : dashboardUid;
        List<GrafanaDashboardPanelDto> panels = dashboard.panels() == null ? List.of() : dashboard.panels();
        return panels.stream()
                .map(panel -> toPanelOption(dashboardUid, dashboardName, panel));
    }

    private PanelOption toPanelOption(String dashboardUid, String dashboardName, GrafanaDashboardPanelDto panel) {
        String panelName = StringUtils.hasText(panel.title())
                ? panel.title().trim()
                : ("Panel " + panel.panelId());
        return new PanelOption(
                dashboardUid,
                panel.panelId(),
                dashboardName + " -> " + panelName,
                dashboardName,
                panelName);
    }

    private SanitizedTemplate sanitize(TemplateSaveCommand command, boolean validateCharts) {
        if (command == null) {
            throw new IllegalArgumentException("Данные шаблона не переданы");
        }

        ConfluenceTemplateType templateType = ConfluenceTemplateType.fromDatabaseValue(command.templateType());
        LoadTestType testType = resolveCommandTestType(command.testType(), templateType);
        String shortName = resolveSystemShortName(templateType, testType);
        String reportTitle = StringUtils.trimWhitespace(command.reportTitle());
        String reportTitleShort = StringUtils.trimWhitespace(command.reportTitleShort());
        String templatePageId = normalizeTemplatePageId(command.templatePageId());
        String text = command.text() == null ? "" : command.text();
        if (!StringUtils.hasText(reportTitle)) {
            throw new IllegalArgumentException("Название отчета обязательно");
        }
        if (!StringUtils.hasText(templatePageId) && !StringUtils.hasText(text)) {
            throw new IllegalArgumentException("ID страницы с шаблоном отчета в Confluence обязателен");
        }

        List<SanitizedChart> charts = new ArrayList<>();
        List<TemplateChartCommand> requestedCharts = command.charts() == null ? List.of() : command.charts();
        for (int index = 0; index < requestedCharts.size(); index++) {
            TemplateChartCommand chart = requestedCharts.get(index);
            if (chart == null) {
                throw new IllegalArgumentException("График шаблона не может быть пустым");
            }
            String title = StringUtils.trimWhitespace(chart.title());
            String dashboardUid = StringUtils.trimWhitespace(chart.dashboardUid());
            if (!StringUtils.hasText(title)) {
                throw new IllegalArgumentException("Название графика обязательно");
            }
            if (!StringUtils.hasText(dashboardUid) || chart.panelId() <= 0) {
                throw new IllegalArgumentException("Код графика обязателен");
            }
            if (validateCharts) {
                grafanaDashboardPanelRepository.findByUidAndPanelId(dashboardUid, chart.panelId())
                        .orElseThrow(() -> new IllegalArgumentException(
                                "График %s (panelId=%s) не найден в кэше Grafana".formatted(dashboardUid, chart.panelId())));
            }
            charts.add(new SanitizedChart(title, dashboardUid, chart.panelId(), index));
        }

        return new SanitizedTemplate(
                shortName,
                resolveFullName(command.fullName(), reportTitle, shortName),
                reportTitle,
                StringUtils.hasText(reportTitleShort) ? reportTitleShort : reportTitle,
                templateType,
                templatePageId,
                text,
                List.copyOf(charts));
    }

    private LoadTestType resolveCommandTestType(String rawTestType, ConfluenceTemplateType templateType) {
        if (templateType == ConfluenceTemplateType.RELEASE) {
            return null;
        }
        LoadTestType testType = LoadTestType.fromDatabaseValue(rawTestType);
        if (testType == LoadTestType.UNKNOWN) {
            throw new IllegalArgumentException("Выберите тип теста");
        }
        return testType;
    }

    private String resolveSystemShortName(ConfluenceTemplateType templateType, LoadTestType testType) {
        if (templateType == ConfluenceTemplateType.RELEASE) {
            return resolveReleaseTemplateShortName();
        }
        return resolveDefaultTemplateShortName(testType);
    }

    private String resolveFullName(String requestedFullName, String reportTitle, String shortName) {
        String fullName = StringUtils.trimWhitespace(requestedFullName);
        if (StringUtils.hasText(fullName)) {
            return fullName;
        }
        if (StringUtils.hasText(reportTitle)) {
            return reportTitle;
        }
        return shortName;
    }

    private String normalizeTemplatePageId(String rawValue) {
        String value = StringUtils.trimWhitespace(rawValue);
        if (!StringUtils.hasText(value)) {
            return "";
        }
        if (PAGE_ID_VALUE_PATTERN.matcher(value).matches()) {
            return value;
        }
        Matcher queryMatcher = PAGE_ID_QUERY_PATTERN.matcher(value);
        if (queryMatcher.find()) {
            return queryMatcher.group(1);
        }
        Matcher pathMatcher = PAGE_ID_PATH_PATTERN.matcher(value);
        if (pathMatcher.find()) {
            return pathMatcher.group(1);
        }
        throw new IllegalArgumentException("Укажите ID страницы с шаблоном отчета в Confluence");
    }

    private void validateUniqueNames(String shortName, Integer templateId) {
        if (templateRepository.existsActiveByShortName(shortName, templateId)) {
            throw new IllegalArgumentException("Название шаблона должно быть уникальным");
        }
    }

    private List<ConfluenceTemplateChart> toCharts(int templateId, List<SanitizedChart> charts) {
        return charts.stream()
                .map(chart -> new ConfluenceTemplateChart(
                        0L,
                        templateId,
                        chart.title(),
                        chart.dashboardUid(),
                        chart.panelId(),
                        chart.sortOrder()))
                .toList();
    }

    public record TemplateSaveCommand(
            String shortName,
            String fullName,
            String reportTitle,
            String reportTitleShort,
            String templateType,
            String testType,
            String templatePageId,
            String text,
            List<TemplateChartCommand> charts) {

        public TemplateSaveCommand(
                String shortName,
                String fullName,
                String reportTitle,
                String templateType,
                String testType,
                String templatePageId,
                String text,
                List<TemplateChartCommand> charts) {
            this(shortName, fullName, reportTitle, reportTitle, templateType, testType, templatePageId, text, charts);
        }
    }

    public record TemplateChartCommand(String title, String dashboardUid, int panelId) {}

    public record PanelOption(
            String dashboardUid,
            int panelId,
            String code,
            String dashboardTitle,
            String panelTitle) {}

    private record SanitizedTemplate(
            String shortName,
            String fullName,
            String reportTitle,
            String reportTitleShort,
            ConfluenceTemplateType templateType,
            String templatePageId,
            String text,
            List<SanitizedChart> charts) {}

    private record SanitizedChart(
            String title,
            String dashboardUid,
            int panelId,
            int sortOrder) {}
}
