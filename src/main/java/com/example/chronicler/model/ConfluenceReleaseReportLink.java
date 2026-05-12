package com.example.chronicler.model;

import java.time.Instant;
import java.util.UUID;

public record ConfluenceReleaseReportLink(
        Long id,
        String releasePageId,
        UUID testUuid,
        String reportPageId,
        String reportPageTitle,
        String reportPageUrl,
        String testNumber,
        String testTitle,
        String testType,
        String testPeriod,
        String testStatus,
        String testSummary,
        Instant createdAt) {}
