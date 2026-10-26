package perfhub.service;

import perfhub.dto.grafana.GrafanaCatalogDiagnostic;
import perfhub.dto.grafana.GrafanaCatalogDiagnostic.Category;
import perfhub.dto.grafana.GrafanaCatalogDiagnostic.Failure;
import perfhub.dto.grafana.GrafanaCatalogDiagnostic.Stage;
import perfhub.dto.grafana.GrafanaCatalogDiagnostic.State;
import perfhub.dto.grafana.GrafanaCatalogResult;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class GrafanaCatalogDiagnosticsService {
    private static final Logger log = LoggerFactory.getLogger(GrafanaCatalogDiagnosticsService.class);

    public GrafanaCatalogResult publish(Long owner, GrafanaCatalogDiagnostic report, GrafanaCatalogResult result) {
        for (Failure failure : report.failures()) {
            log.warn("Grafana catalog check={} dashboardKey={} stage={} category={} http={}",
                    report.checkId(), failure.dashboardKey(), failure.stage(), failure.category(), failure.httpStatus());
        }
        return result;
    }

    public GrafanaCatalogResult unavailable() {
        Instant now = Instant.now();
        var report = new GrafanaCatalogDiagnostic(UUID.randomUUID(), now, now, State.UNAVAILABLE,
                0, 0, 1, 0, 0, List.of(new Failure(Stage.CATALOG_INITIALIZE, Category.UNKNOWN, null, null)));
        return publish(null, report, new GrafanaCatalogResult("UNAVAILABLE",
                "Grafana доступна, но каталог обновить не удалось. Повторите проверку подключения.", now));
    }
}
