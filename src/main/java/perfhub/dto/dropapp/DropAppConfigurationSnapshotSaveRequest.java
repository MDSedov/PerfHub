package com.example.chronicler.dto.dropapp;

import java.util.List;

/** Запрос на сохранение снапшота конфигурации DropApp. */
public record DropAppConfigurationSnapshotSaveRequest(
        List<String> namespaces, String release, String comment, List<DropAppConfigurationItemDto> items) {}
