package com.example.chronicler.dto.confluence;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import java.util.UUID;

public record ConfluenceReleaseReportPageRequest(
        @NotBlank(message = "parentPageId is required") String parentPageId,
        @NotBlank(message = "title is required") String title,
        @NotNull(message = "templateId is required") Integer templateId,
        @NotEmpty(message = "testUuids must not be empty") List<@NotNull UUID> testUuids) {
}
