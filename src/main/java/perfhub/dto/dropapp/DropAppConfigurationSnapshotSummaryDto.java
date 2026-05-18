package com.example.chronicler.dto.dropapp;

/** Краткая информация о сохраненной конфигурации DropApp. */
public record DropAppConfigurationSnapshotSummaryDto(
        Long id,
        String savedAt,
        String release,
        String jiraReleaseIssueKey,
        String jiraReleaseIssueUrl,
        String comment) {}
