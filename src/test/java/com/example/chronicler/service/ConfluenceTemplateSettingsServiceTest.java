package com.example.chronicler.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.chronicler.model.LoadTestType;
import org.junit.jupiter.api.Test;

class ConfluenceTemplateSettingsServiceTest {

    private final ConfluenceTemplateSettingsService service =
            new ConfluenceTemplateSettingsService(null, null, null, null);

    @Test
    void resolveDefaultTemplateShortNameSupportsKnownTestTypes() {
        assertThat(service.resolveDefaultTemplateShortName(LoadTestType.MAX)).isEqualTo("max");
        assertThat(service.resolveDefaultTemplateShortName(LoadTestType.STABILITY)).isEqualTo("stability");
        assertThat(service.resolveDefaultTemplateShortName(LoadTestType.SOAK)).isEqualTo("soak");
        assertThat(service.resolveDefaultTemplateShortName(LoadTestType.PSU)).isEqualTo("psu");
        assertThat(service.resolveDefaultTemplateShortName(LoadTestType.DT)).isEqualTo("dt");
    }

    @Test
    void resolveDefaultTemplateShortNameRejectsUnknownTestType() {
        assertThatThrownBy(() -> service.resolveDefaultTemplateShortName(LoadTestType.UNKNOWN))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Тип теста не определен");
    }
}
