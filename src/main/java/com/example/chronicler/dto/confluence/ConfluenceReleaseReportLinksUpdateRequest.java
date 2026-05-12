package com.example.chronicler.dto.confluence;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import java.util.UUID;

public record ConfluenceReleaseReportLinksUpdateRequest(
        @NotBlank(message = "releasePageId is required") String releasePageId,
        @NotEmpty(message = "reports must not be empty") List<@Valid ReportLinkItem> reports) {

    public record ReportLinkItem(
            @NotNull(message = "testUuid is required") UUID testUuid,
            String testNumber,
            String testTitle,
            @NotBlank(message = "reportPageId is required") String reportPageId,
            String reportPageTitle,
            String reportPageUrl) {}
}
