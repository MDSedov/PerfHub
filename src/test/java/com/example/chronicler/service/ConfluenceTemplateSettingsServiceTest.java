package com.example.chronicler.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.example.chronicler.model.ConfluenceTemplate;
import com.example.chronicler.model.ConfluenceTemplateType;
import com.example.chronicler.model.LoadTestType;
import com.example.chronicler.repository.ConfluenceTemplateChartRepository;
import com.example.chronicler.repository.ConfluenceTemplateRepository;
import com.example.chronicler.repository.GrafanaDashboardPanelRepository;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ConfluenceTemplateSettingsServiceTest {

    private final ConfluenceTemplateSettingsService service =
            new ConfluenceTemplateSettingsService(null, null, null, null);

    @Mock
    private ConfluenceTemplateRepository templateRepository;

    @Mock
    private ConfluenceTemplateChartRepository chartRepository;

    @Mock
    private GrafanaDashboardPanelRepository grafanaDashboardPanelRepository;

    @Test
    void resolveDefaultTemplateShortNameSupportsKnownTestTypes() {
        assertThat(service.resolveDefaultTemplateShortName(LoadTestType.MAX)).isEqualTo("max_template");
        assertThat(service.resolveDefaultTemplateShortName(LoadTestType.STABILITY)).isEqualTo("stability_template");
        assertThat(service.resolveDefaultTemplateShortName(LoadTestType.SOAK)).isEqualTo("soak_template");
        assertThat(service.resolveDefaultTemplateShortName(LoadTestType.PSU)).isEqualTo("psu_template");
        assertThat(service.resolveDefaultTemplateShortName(LoadTestType.DT)).isEqualTo("dt_template");
    }

    @Test
    void resolveReleaseTemplateShortNameReturnsSystemName() {
        assertThat(service.resolveReleaseTemplateShortName()).isEqualTo("release_template");
    }

    @Test
    void resolveDefaultTemplateShortNameRejectsUnknownTestType() {
        assertThatThrownBy(() -> service.resolveDefaultTemplateShortName(LoadTestType.UNKNOWN))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Тип теста не определен");
    }

    @Test
    void createTemplateStoresOnlyPageIdFromConfluenceUrl() {
        ConfluenceTemplateSettingsService savingService =
                new ConfluenceTemplateSettingsService(
                        templateRepository,
                        chartRepository,
                        grafanaDashboardPanelRepository,
                        null);
        when(templateRepository.create(
                        eq("max_template"),
                        eq("Отчет"),
                        eq("Отчет"),
                        eq(ConfluenceTemplateType.TEST),
                        eq("123456"),
                        eq("")))
                .thenReturn(42);
        when(templateRepository.findActiveById(42))
                .thenReturn(Optional.of(new ConfluenceTemplate(
                        42,
                        "max_template",
                        "Отчет",
                        "Отчет",
                        ConfluenceTemplateType.TEST,
                        "123456",
                        "",
                        List.of())));
        when(chartRepository.findByTemplateId(42)).thenReturn(List.of());

        savingService.createTemplate(templateCommand(
                "https://confluence.example/pages/viewpage.action?pageId=123456"));

        verify(templateRepository).create(
                "max_template",
                "Отчет",
                "Отчет",
                ConfluenceTemplateType.TEST,
                "123456",
                "");
    }

    @Test
    void createTemplateAllowsInlineBodyWithoutTemplatePageId() {
        ConfluenceTemplateSettingsService savingService =
                new ConfluenceTemplateSettingsService(
                        templateRepository,
                        chartRepository,
                        grafanaDashboardPanelRepository,
                        null);
        when(templateRepository.create(
                        eq("max_template"),
                        eq("Отчет"),
                        eq("Отчет"),
                        eq(ConfluenceTemplateType.TEST),
                        eq(""),
                        eq("<p>Body</p>")))
                .thenReturn(43);
        when(templateRepository.findActiveById(43))
                .thenReturn(Optional.of(new ConfluenceTemplate(
                        43,
                        "max_template",
                        "Отчет",
                        "Отчет",
                        ConfluenceTemplateType.TEST,
                        "",
                        "<p>Body</p>",
                        List.of())));
        when(chartRepository.findByTemplateId(43)).thenReturn(List.of());

        savingService.createTemplate(new ConfluenceTemplateSettingsService.TemplateSaveCommand(
                null,
                null,
                "Отчет",
                "TEST",
                "max",
                "",
                "<p>Body</p>",
                List.of()));

        verify(templateRepository).create(
                "max_template",
                "Отчет",
                "Отчет",
                ConfluenceTemplateType.TEST,
                "",
                "<p>Body</p>");
    }

    @Test
    void createTemplateCanSkipGrafanaPanelValidationForSettingsImport() {
        ConfluenceTemplateSettingsService savingService =
                new ConfluenceTemplateSettingsService(
                        templateRepository,
                        chartRepository,
                        grafanaDashboardPanelRepository,
                        null);
        when(templateRepository.create(
                        eq("max_template"),
                        eq("Отчет"),
                        eq("Отчет"),
                        eq(ConfluenceTemplateType.TEST),
                        eq("123456"),
                        eq("")))
                .thenReturn(44);
        when(templateRepository.findActiveById(44))
                .thenReturn(Optional.of(new ConfluenceTemplate(
                        44,
                        "max_template",
                        "Отчет",
                        "Отчет",
                        ConfluenceTemplateType.TEST,
                        "123456",
                        "",
                        List.of())));
        when(chartRepository.findByTemplateId(44)).thenReturn(List.of());

        savingService.createTemplate(
                new ConfluenceTemplateSettingsService.TemplateSaveCommand(
                        null,
                        null,
                        "Отчет",
                        "TEST",
                        "max",
                        "123456",
                        "",
                        List.of(new ConfluenceTemplateSettingsService.TemplateChartCommand(
                                "CPU",
                                "dashboard-uid",
                                7))),
                false);

        verify(chartRepository).replaceForTemplate(eq(44), any());
        verifyNoInteractions(grafanaDashboardPanelRepository);
    }

    @Test
    void createTemplateRejectsNonPageIdValue() {
        ConfluenceTemplateSettingsService savingService =
                new ConfluenceTemplateSettingsService(
                        templateRepository,
                        chartRepository,
                        grafanaDashboardPanelRepository,
                        null);

        assertThatThrownBy(() -> savingService.createTemplate(templateCommand("not-a-page-id")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Укажите ID страницы с шаблоном отчета в Confluence");

        verifyNoInteractions(templateRepository, chartRepository, grafanaDashboardPanelRepository);
    }

    private ConfluenceTemplateSettingsService.TemplateSaveCommand templateCommand(String templatePageId) {
        return new ConfluenceTemplateSettingsService.TemplateSaveCommand(
                null,
                null,
                "Отчет",
                "TEST",
                "max",
                templatePageId,
                "",
                List.of());
    }
}
