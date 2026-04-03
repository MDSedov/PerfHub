package com.example.chronicler.dto.dropapp;

import java.util.List;

/** Детальная информация о сохраненной конфигурации DropApp. */
public record DropAppConfigurationSnapshotDetailsDto(
        Long id,
        String savedAt,
        String release,
        String comment,
        List<String> namespaces,
        List<DropAppConfigurationItemDto> items) {}
