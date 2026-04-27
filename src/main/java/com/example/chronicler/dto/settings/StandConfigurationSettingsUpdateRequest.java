package com.example.chronicler.dto.settings;

import com.example.chronicler.dto.dropapp.DropAppServiceSelectionDto;
import java.util.List;

/** Запрос на обновление настроек конфигурации стенда. */
public record StandConfigurationSettingsUpdateRequest(
        List<String> namespaces,
        List<String> components,
        List<DropAppServiceSelectionDto> services) {}
