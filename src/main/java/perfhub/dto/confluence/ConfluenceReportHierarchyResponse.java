package perfhub.dto.confluence;

import java.util.List;

public record ConfluenceReportHierarchyResponse(String reportPageId, List<String> childPageIds) {
}
