package com.example.chronicler.dto.dropapp;

import java.util.List;

/** Детальная информация о сохраненной конфигурации DropApp. */
public record DropAppConfigurationSnapshotDetailsDto(
        Long id,
        String savedAt,
        String release,
        String jiraReleaseIssueKey,
        String jiraReleaseIssueUrl,
        String comment,
        List<String> namespaces,
        List<String> components,
        List<DropAppConfigurationItemDto> items,
        List<DropAppConfigurationDocumentDto> documents) {}
