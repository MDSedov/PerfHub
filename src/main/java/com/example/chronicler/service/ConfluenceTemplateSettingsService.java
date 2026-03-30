package com.example.chronicler.service;

import com.example.chronicler.dto.settings.GrafanaDashboardDetailsDto;
import com.example.chronicler.dto.settings.GrafanaDashboardPanelDto;
import com.example.chronicler.model.ConfluenceTemplateChart;
import com.example.chronicler.model.ConfluenceTemplate;
import com.example.chronicler.model.LoadTestType;
import com.example.chronicler.repository.ConfluenceTemplateChartRepository;
import com.example.chronicler.repository.ConfluenceTemplateRepository;
import com.example.chronicler.repository.GrafanaDashboardPanelRepository;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
public class ConfluenceTemplateSettingsService {

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
        return switch (loadTestType) {
            case MAX -> "max";
            case STABILITY -> "stability";
            case SOAK -> "soak";
            case PSU, DT -> throw new IllegalArgumentException(
                    "Confluence report template is not configured for test type " + loadTestType.value());
            case UNKNOWN -> throw new IllegalArgumentException("Тип теста не определен");
        };
    }

    @Transactional
    public ConfluenceTemplate createTemplate(TemplateSaveCommand command) {
        SanitizedTemplate sanitized = sanitize(command);
        validateUniqueNames(sanitized.shortName(), sanitized.fullName(), null);
        int templateId = templateRepository.create(
                sanitized.shortName(),
                sanitized.fullName(),
                sanitized.reportTitle(),
                sanitized.text());
        chartRepository.replaceForTemplate(templateId, toCharts(templateId, sanitized.charts()));
        return getActiveTemplate(templateId);
    }

    @Transactional
    public ConfluenceTemplate updateTemplate(int id, TemplateSaveCommand command) {
        templateRepository.findActiveById(id)
                .orElseThrow(() -> new IllegalArgumentException("Шаблон отчета не найден"));
        SanitizedTemplate sanitized = sanitize(command);
        validateUniqueNames(sanitized.shortName(), sanitized.fullName(), id);
        templateRepository.update(
                id,
                sanitized.shortName(),
                sanitized.fullName(),
                sanitized.reportTitle(),
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

    private SanitizedTemplate sanitize(TemplateSaveCommand command) {
        if (command == null) {
            throw new IllegalArgumentException("Данные шаблона не переданы");
        }

        String shortName = StringUtils.trimWhitespace(command.shortName());
        String fullName = StringUtils.trimWhitespace(command.fullName());
        String reportTitle = StringUtils.trimWhitespace(command.reportTitle());
        if (!StringUtils.hasText(shortName)) {
            throw new IllegalArgumentException("Короткое имя шаблона обязательно");
        }
        if (shortName.length() > 12) {
            throw new IllegalArgumentException("Короткое имя шаблона должно быть не длиннее 12 символов");
        }
        if (!StringUtils.hasText(fullName)) {
            throw new IllegalArgumentException("Полное название шаблона обязательно");
        }
        if (!StringUtils.hasText(reportTitle)) {
            throw new IllegalArgumentException("Название отчета обязательно");
        }
        if (!StringUtils.hasText(command.text())) {
            throw new IllegalArgumentException("Текст шаблона обязателен");
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
            grafanaDashboardPanelRepository.findByUidAndPanelId(dashboardUid, chart.panelId())
                    .orElseThrow(() -> new IllegalArgumentException(
                            "График %s (panelId=%s) не найден в кэше Grafana".formatted(dashboardUid, chart.panelId())));
            charts.add(new SanitizedChart(title, dashboardUid, chart.panelId(), index));
        }

        return new SanitizedTemplate(shortName, fullName, reportTitle, command.text(), List.copyOf(charts));
    }

    private void validateUniqueNames(String shortName, String fullName, Integer templateId) {
        if (templateRepository.existsActiveByShortName(shortName, templateId)) {
            throw new IllegalArgumentException("Короткое имя шаблона должно быть уникальным");
        }
        if (templateRepository.existsActiveByFullName(fullName, templateId)) {
            throw new IllegalArgumentException("Полное название шаблона должно быть уникальным");
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
            String text,
            List<TemplateChartCommand> charts) {}

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
            String text,
            List<SanitizedChart> charts) {}

    private record SanitizedChart(
            String title,
            String dashboardUid,
            int panelId,
            int sortOrder) {}
}
