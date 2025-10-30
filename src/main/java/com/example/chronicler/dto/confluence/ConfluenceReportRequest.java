package com.example.chronicler.dto.confluence;

import jakarta.validation.constraints.NotBlank;

public record ConfluenceReportRequest(@NotBlank(message = "pageId is required") String pageId) {
}
