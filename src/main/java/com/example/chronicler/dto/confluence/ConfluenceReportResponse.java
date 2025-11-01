package com.example.chronicler.dto.confluence;

import java.util.List;

public record ConfluenceReportResponse(String pageId, List<String> attachmentNames) {
}
