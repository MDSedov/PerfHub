package com.example.chronicler.dto.dropapp;

import java.util.List;

/** Текущая конфигурация DropApp вместе с выбранными namespace и вычисленным релизом. */
public record DropAppConfigurationViewDto(
        List<String> namespaces, String release, List<DropAppConfigurationItemDto> items) {}
