package perfhub.dto.grafana;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Только разрешённые диагностические поля; транспортные объекты сюда не передаются. */
public record GrafanaCatalogDiagnostic(UUID checkId, Instant startedAt, Instant completedAt,
        State state, int updatedDashboards, int failedDashboards, int globalFailures, int deploymentOptions,
        int omittedFailures, List<Failure> failures) {
    public static final int MAX_FAILURES = 100;

    public GrafanaCatalogDiagnostic {
        Objects.requireNonNull(checkId);
        Objects.requireNonNull(startedAt);
        Objects.requireNonNull(completedAt);
        Objects.requireNonNull(state);
        omittedFailures += Math.max(0, failures.size() - MAX_FAILURES);
        failures = List.copyOf(failures.subList(0, Math.min(MAX_FAILURES, failures.size())));
    }

    public enum State { READY, EMPTY, PARTIAL, STALE, UNAVAILABLE }

    public enum Stage {
        CATALOG_INITIALIZE, DASHBOARD_SEARCH, DASHBOARD_LOAD, DASHBOARD_PARSE,
        DEPLOYMENT_CONTEXT, DEPLOYMENT_VALUES, CONTEXT_CHECK, SNAPSHOT_PUBLISH
    }

    public enum Category {
        HTTP_ERROR("Источник вернул ошибку HTTP; код сам по себе не устанавливает причину."),
        TRANSPORT_ERROR("Не удалось завершить обмен с источником."),
        INVALID_RESPONSE("Ответ источника пуст или имеет неподдержанный формат."),
        INVALID_CONTEXT("Контекст datasource/namespace или переменной не поддержан."),
        UNSUPPORTED_METADATA("Метаданные дашборда не поддержаны."),
        CONTEXT_CHANGED("Контекст изменился до публикации результата."),
        LOCAL_STORAGE_ERROR("Операция локального хранилища не завершена."),
        UNKNOWN("Неожиданный сбой; сырые сведения исключения не включены.");

        private final String explanation;

        Category(String explanation) {
            this.explanation = explanation;
        }

        public String explanation() {
            return explanation;
        }
    }

    public record Failure(Stage stage, Category category, Integer httpStatus, String dashboardKey) {
        public Failure {
            Objects.requireNonNull(stage);
            Objects.requireNonNull(category);
            if (httpStatus != null && (httpStatus < 100 || httpStatus > 999)) {
                httpStatus = null;
            }
            if (dashboardKey != null && !dashboardKey.matches("[a-f0-9]{64}")) {
                dashboardKey = null;
            }
        }
    }
}
