package perfhub.dto.confluence;

import java.util.List;
import java.util.UUID;

public record ConfluenceReportResponse(List<ReportPage> reports) {

    public record ReportPage(
            UUID testUuid,
            String pageId,
            String pageTitle,
            String pageUrl,
            List<String> attachmentNames) {}
}
