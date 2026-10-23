package perfhub.exception;

import perfhub.dto.grafana.GrafanaCatalogDiagnostic.Category;
import perfhub.dto.grafana.GrafanaCatalogDiagnostic.Stage;

/** Диагностические метаданные отдельного catalog API; cause не предназначен для вывода. */
public class GrafanaCatalogClientException extends GrafanaClientException {
    private final Stage stage;
    private final Category category;
    private final Integer httpStatus;

    public GrafanaCatalogClientException(Stage stage, Category category, Integer httpStatus, Throwable cause) {
        super(category.explanation(), cause);
        this.stage = stage;
        this.category = category;
        this.httpStatus = httpStatus;
    }

    public Stage stage() {
        return stage;
    }

    public Category category() {
        return category;
    }

    public Integer httpStatus() {
        return httpStatus;
    }
}
