package perfhub.dto.confluence;

import java.util.List;

public record ConfluenceChartsAppendResponse(
        String pageId,
        String pageTitle,
        String pageUrl,
        int appendedChartsCount,
        List<String> attachmentNames) {}
