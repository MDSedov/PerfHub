package com.example.chronicler.dto.settings;

import com.example.chronicler.dto.dropapp.DropAppServiceOptionDto;
import com.example.chronicler.dto.dropapp.DropAppServiceSelectionDto;
import java.util.List;

/** Настройки состава конфигурации стенда для сохранения из DropApp. */
public record StandConfigurationSettingsDto(
        List<String> namespaces,
        List<String> components,
        List<DropAppServiceSelectionDto> services,
        List<DropAppServiceOptionDto> availableServices) {}
