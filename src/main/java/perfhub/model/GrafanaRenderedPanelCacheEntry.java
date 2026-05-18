package perfhub.model;

import java.time.Instant;
import java.util.UUID;

public record GrafanaRenderedPanelCacheEntry(
        Long id,
        UUID testUuid,
        Integer templateId,
        String dashboardUid,
        String dashboardSlug,
        int panelId,
        Instant from,
        Instant to,
        String variablesHash,
        int imageWidth,
        int imageHeight,
        String timezone,
        String title,
        String description,
        String filePath,
        String contentType,
        String imageSha256,
        Instant renderedAt) {
}
