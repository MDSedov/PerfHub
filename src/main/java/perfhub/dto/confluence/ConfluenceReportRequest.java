package perfhub.dto.confluence;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import java.util.UUID;

public record ConfluenceReportRequest(
        @NotBlank(message = "parentPageId is required") String parentPageId,
        List<@NotNull UUID> tests,
        List<@Valid ReportItem> items) {

    public record ReportItem(
            @NotNull(message = "testUuid is required") UUID testUuid,
            @NotNull(message = "templateId is required") Integer templateId) {}
}
