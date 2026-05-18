package com.example.chronicler.dto.confluence;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record ConfluenceChartsAppendRequest(
        @NotBlank(message = "pageId is required") String pageId,
        @NotBlank(message = "from is required") String from,
        @NotBlank(message = "to is required") String to,
        @NotNull(message = "templateId is required") Integer templateId) {}
