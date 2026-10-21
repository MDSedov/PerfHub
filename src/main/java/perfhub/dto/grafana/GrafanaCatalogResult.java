package perfhub.dto.grafana;

import java.time.Instant;

public record GrafanaCatalogResult(String status, String message, Instant updatedAt) {}
