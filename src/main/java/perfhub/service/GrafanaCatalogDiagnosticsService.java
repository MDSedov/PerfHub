package perfhub.service;

import perfhub.dto.grafana.GrafanaCatalogDiagnostic;
import perfhub.dto.grafana.GrafanaCatalogDiagnostic.Category;
import perfhub.dto.grafana.GrafanaCatalogDiagnostic.Failure;
import perfhub.dto.grafana.GrafanaCatalogDiagnostic.Stage;
import perfhub.dto.grafana.GrafanaCatalogDiagnostic.State;
import perfhub.dto.grafana.GrafanaCatalogResult;
import perfhub.security.CurrentUserProvider;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class GrafanaCatalogDiagnosticsService {
    static final int MAX_REPORT_BYTES = 128 * 1024;
    private static final Logger log = LoggerFactory.getLogger(GrafanaCatalogDiagnosticsService.class);
    private final CurrentUserProvider currentUser;
    private final Path directory;

    @Autowired
    public GrafanaCatalogDiagnosticsService(CurrentUserProvider currentUser) {
        this(currentUser, Path.of("logs"));
    }

    GrafanaCatalogDiagnosticsService(CurrentUserProvider currentUser, Path directory) {
        this.currentUser = currentUser;
        this.directory = directory;
    }

    public GrafanaCatalogResult publish(Long owner, GrafanaCatalogDiagnostic report, GrafanaCatalogResult result) {
        log.info("Grafana catalog check={} result={} updated={} failed={} globalFailures={} omitted={}",
                report.checkId(), report.state(), report.updatedDashboards(), report.failedDashboards(),
                report.globalFailures(), report.omittedFailures());
        for (Failure failure : report.failures()) {
            log.warn("Grafana catalog check={} dashboardKey={} stage={} category={} http={}",
                    report.checkId(), failure.dashboardKey(), failure.stage(), failure.category(), failure.httpStatus());
        }
        boolean saved = false;
        Path temporary = null;
        String filename = owner == null || owner < 0 ? null : "grafana-catalog-diagnostics-user-" + owner + ".txt";
        if (filename != null) {
            try {
                createDirectory();
                temporary = Files.createTempFile(directory, ".grafana-catalog-", ".tmp");
                writeTemporary(temporary, render(report));
                synchronized (this) {
                    replaceFile(temporary, directory.resolve(filename));
                }
                saved = true;
            } catch (IOException | RuntimeException ex) {
                log.warn("Grafana catalog check={} reportWrite=FAILED category=LOCAL_STORAGE_ERROR", report.checkId());
            } finally {
                if (temporary != null) {
                    try {
                        deleteTemporary(temporary);
                    } catch (IOException | RuntimeException ex) {
                        log.warn("Grafana catalog check={} reportCleanup=FAILED category=LOCAL_STORAGE_ERROR", report.checkId());
                    }
                }
            }
        }
        String detail = saved
                ? " Диагностика: logs/" + filename + ". ID проверки: " + report.checkId() + "."
                : " Не удалось сохранить диагностику этой проверки. Сведения доступны в консоли. ID проверки: " + report.checkId() + ".";
        return new GrafanaCatalogResult(result.status(), result.message() + detail, result.updatedAt());
    }

    public GrafanaCatalogResult unavailable() {
        Long owner = null;
        try {
            owner = currentUser.currentUserId().orElse(0L);
        } catch (RuntimeException ex) {
            // Без подтверждённого пользователя не перезаписываем глобальный или чужой отчёт.
        }
        Instant now = Instant.now();
        var report = new GrafanaCatalogDiagnostic(UUID.randomUUID(), now, now, State.UNAVAILABLE,
                0, 0, 1, 0, 0, List.of(new Failure(Stage.CATALOG_INITIALIZE, Category.UNKNOWN, null, null)));
        return publish(owner, report, new GrafanaCatalogResult("UNAVAILABLE",
                "Grafana доступна, но каталог обновить не удалось. Повторите проверку подключения.", now));
    }

    private byte[] render(GrafanaCatalogDiagnostic report) {
        String header = "Диагностика каталога Grafana\nВерсия формата: 1\nID проверки: " + report.checkId()
                + "\nНачало (UTC): " + report.startedAt() + "\nЗавершение (UTC): " + report.completedAt()
                + "\nРезультат: " + report.state() + "\nОбновлено дашбордов: " + report.updatedDashboards()
                + "\nОшибок дашбордов: " + report.failedDashboards() + "\nОбщих ошибок: " + report.globalFailures()
                + "\nДоступных deployment: " + report.deploymentOptions() + "\n";
        StringBuilder details = new StringBuilder();
        int omitted = report.omittedFailures();
        // Резерв для итогового счётчика; поля/пояснения ограничены типами, записи не разрываются.
        int bytes = header.getBytes(StandardCharsets.UTF_8).length + 128;
        for (Failure failure : report.failures()) {
            String entry = "\nДашборд (ключ): " + (failure.dashboardKey() == null ? "—" : failure.dashboardKey())
                    + "\nЭтап: " + failure.stage() + "\nКатегория: " + failure.category()
                    + "\nHTTP: " + (failure.httpStatus() == null ? "отсутствует" : failure.httpStatus())
                    + "\nПояснение: " + failure.category().explanation() + "\n";
            int length = entry.getBytes(StandardCharsets.UTF_8).length;
            if (bytes + length <= MAX_REPORT_BYTES) {
                details.append(entry);
                bytes += length;
            } else {
                omitted++;
            }
        }
        return (header + "Пропущено записей ошибок: " + omitted + "\n" + details).getBytes(StandardCharsets.UTF_8);
    }

    void createDirectory() throws IOException {
        Files.createDirectories(directory);
    }

    void writeTemporary(Path temporary, byte[] bytes) throws IOException {
        Files.write(temporary, bytes);
    }

    void replaceFile(Path temporary, Path destination) throws IOException {
        try {
            Files.move(temporary, destination, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException ex) {
            Files.move(temporary, destination, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    void deleteTemporary(Path temporary) throws IOException {
        Files.deleteIfExists(temporary);
    }
}
