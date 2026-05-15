package com.example.chronicler.controller.settings;

import com.example.chronicler.dto.settings.ConfluenceTemplateChartDto;
import com.example.chronicler.dto.settings.ConfluenceTemplateDto;
import com.example.chronicler.dto.settings.ConfluenceTemplatePanelOptionDto;
import com.example.chronicler.dto.settings.ConfluenceTemplateSaveRequest;
import com.example.chronicler.model.ConfluenceTemplate;
import com.example.chronicler.model.ConfluenceTemplateChart;
import com.example.chronicler.service.ConfluenceTemplateSettingsService;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(
        path = "/api/settings/confluence-templates",
        produces = MediaType.APPLICATION_JSON_VALUE)
public class ConfluenceTemplateSettingsController {

    private final ConfluenceTemplateSettingsService settingsService;

    public ConfluenceTemplateSettingsController(ConfluenceTemplateSettingsService settingsService) {
        this.settingsService = settingsService;
    }

    @GetMapping
    public List<ConfluenceTemplateDto> listTemplates() {
        Map<String, String> codesByPanelKey = buildPanelCodeMap();
        return settingsService.listTemplates().stream()
                .map(template -> toDto(template, codesByPanelKey))
                .toList();
    }

    @GetMapping("/export")
    public List<ConfluenceTemplateDto> exportTemplates() {
        return settingsService.listTemplates().stream()
                .map(template -> toDto(template, Map.of()))
                .toList();
    }

    @GetMapping("/panel-options")
    public List<ConfluenceTemplatePanelOptionDto> listPanelOptions() {
        return settingsService.listPanelOptions().stream()
                .map(option -> new ConfluenceTemplatePanelOptionDto(
                        option.dashboardUid(),
                        option.panelId(),
                        option.code(),
                        option.dashboardTitle(),
                        option.panelTitle()))
                .toList();
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    public ConfluenceTemplateDto createTemplate(
            @Valid @RequestBody ConfluenceTemplateSaveRequest request,
            @RequestParam(name = "validateCharts", defaultValue = "true") boolean validateCharts) {
        return toDto(
                settingsService.createTemplate(toCommand(request), validateCharts),
                validateCharts ? buildPanelCodeMap() : Map.of());
    }

    @PutMapping(path = "/{id}", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ConfluenceTemplateDto updateTemplate(
            @PathVariable("id") int id,
            @Valid @RequestBody ConfluenceTemplateSaveRequest request,
            @RequestParam(name = "validateCharts", defaultValue = "true") boolean validateCharts) {
        return toDto(
                settingsService.updateTemplate(id, toCommand(request), validateCharts),
                validateCharts ? buildPanelCodeMap() : Map.of());
    }

    @DeleteMapping("/{id}")
    public void deleteTemplate(@PathVariable("id") int id) {
        settingsService.deleteTemplate(id);
    }

    private ConfluenceTemplateDto toDto(
            ConfluenceTemplate template,
            Map<String, String> codesByPanelKey) {
        List<ConfluenceTemplateChartDto> charts = template.charts().stream()
                .map(chart -> toChartDto(chart, codesByPanelKey))
                .toList();
        return new ConfluenceTemplateDto(
                template.id(),
                template.shortName(),
                template.fullName(),
                template.reportTitle(),
                template.templateType().name(),
                settingsService.resolveTemplateTestType(template).map(com.example.chronicler.model.LoadTestType::value).orElse(null),
                template.templatePageId(),
                template.text(),
                charts);
    }

    private ConfluenceTemplateChartDto toChartDto(
            ConfluenceTemplateChart chart,
            Map<String, String> codesByPanelKey) {
        String code = codesByPanelKey.getOrDefault(
                panelKey(chart.dashboardUid(), chart.panelId()),
                chart.dashboardUid() + " -> " + chart.panelId());
        return new ConfluenceTemplateChartDto(chart.title(), chart.dashboardUid(), chart.panelId(), code);
    }

    private String panelKey(String dashboardUid, int panelId) {
        return dashboardUid + ":" + panelId;
    }

    private Map<String, String> buildPanelCodeMap() {
        return settingsService.listPanelOptions().stream()
                .collect(java.util.stream.Collectors.toMap(
                        option -> panelKey(option.dashboardUid(), option.panelId()),
                        ConfluenceTemplateSettingsService.PanelOption::code,
                        (first, second) -> first));
    }

    private ConfluenceTemplateSettingsService.TemplateSaveCommand toCommand(ConfluenceTemplateSaveRequest request) {
        List<ConfluenceTemplateSettingsService.TemplateChartCommand> charts =
                request.charts() == null
                        ? List.of()
                        : request.charts().stream()
                                .map(item -> new ConfluenceTemplateSettingsService.TemplateChartCommand(
                                        item.title(),
                                        item.dashboardUid(),
                                        item.panelId()))
                                .toList();
        return new ConfluenceTemplateSettingsService.TemplateSaveCommand(
                request.shortName(),
                request.fullName(),
                request.reportTitle(),
                request.templateType(),
                request.testType(),
                request.templatePageId(),
                request.text(),
                charts);
    }
}
