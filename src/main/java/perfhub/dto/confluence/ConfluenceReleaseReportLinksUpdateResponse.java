package perfhub.dto.confluence;

public record ConfluenceReleaseReportLinksUpdateResponse(
        String releasePageId,
        boolean updated,
        int reportsCount) {}
