package perfhub.dto.grafana;

/**
 * Описание панели Grafana, используемое при синхронизации списка дашбордов.
 *
 * @param panelId   идентификатор панели в Grafana
 * @param title     заголовок панели
 * @param query     первый запрос панели (первый target.expr)
 * @param threshold первое пороговое значение thresholds (value из steps)
 */
public record GrafanaPanelDefinition(int panelId, String title, String query, Double threshold) {}
