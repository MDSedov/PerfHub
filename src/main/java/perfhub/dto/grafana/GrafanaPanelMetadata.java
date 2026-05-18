package perfhub.dto.grafana;

/**
 * Метаданные панели Grafana, включающие заголовок и описание, полученные из REST API.
 */
public record GrafanaPanelMetadata(String title, String description) {
}
