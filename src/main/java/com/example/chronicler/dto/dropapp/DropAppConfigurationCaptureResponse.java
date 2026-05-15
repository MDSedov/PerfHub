package com.example.chronicler.dto.dropapp;

import java.util.List;

public record DropAppConfigurationCaptureResponse(
        Long snapshotId,
        String savedAt,
        String release,
        String jiraReleaseIssueKey,
        String jiraReleaseIssueUrl,
        String comment,
        List<String> namespaces,
        List<String> components,
        List<DropAppConfigurationItemDto> items) {}
