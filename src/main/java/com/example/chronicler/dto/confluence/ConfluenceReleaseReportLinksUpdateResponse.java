package com.example.chronicler.dto.confluence;

public record ConfluenceReleaseReportLinksUpdateResponse(
        String releasePageId,
        boolean updated,
        int reportsCount) {}
