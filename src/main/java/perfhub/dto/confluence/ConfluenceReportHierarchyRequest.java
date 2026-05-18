package com.example.chronicler.dto.confluence;

import jakarta.validation.constraints.NotBlank;

public record ConfluenceReportHierarchyRequest(@NotBlank String parentPageId) {
}
