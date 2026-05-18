package com.example.chronicler.dto.confluence;

import java.util.List;

public record ConfluenceReportHierarchyResponse(String reportPageId, List<String> childPageIds) {
}
