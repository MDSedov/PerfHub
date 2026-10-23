package perfhub.service;

import perfhub.dto.grafana.GrafanaCatalogDiagnostic;
import perfhub.dto.grafana.GrafanaCatalogDiagnostic.Category;
import perfhub.dto.grafana.GrafanaCatalogDiagnostic.Failure;
import perfhub.dto.grafana.GrafanaCatalogDiagnostic.Stage;
import perfhub.dto.grafana.GrafanaCatalogDiagnostic.State;
import perfhub.dto.grafana.GrafanaCatalogResult;
import perfhub.security.CurrentUserProvider;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

class GrafanaCatalogDiagnosticsServiceTest {
    @TempDir
    Path directory;

    @Test
    void replacesPreviousFailureWithSuccessfulReportWithoutHistory() throws Exception {
        var service = service(directory.resolve("logs"));
        var failed = report(State.PARTIAL, 1);
        var original = result(State.PARTIAL);
        var published = service.publish(1L, failed, original);
        assertThat(published.status()).isEqualTo(original.status());
        assertThat(published.updatedAt()).isEqualTo(original.updatedAt());
        assertThat(published.message()).startsWith(original.message()).contains("logs/grafana-catalog-diagnostics-user-1.txt", failed.checkId().toString());
        Path file = directory.resolve("logs/grafana-catalog-diagnostics-user-1.txt");
        assertThat(Files.readString(file)).contains("HTTP: 403", "DEPLOYMENT_VALUES", "Результат: PARTIAL");
        var successful = report(State.READY, 0);
        service.publish(1L, successful, result(State.READY));
        assertThat(Files.readString(file)).contains(successful.checkId().toString(), "Результат: READY")
                .doesNotContain(failed.checkId().toString(), "HTTP: 403");
        try (var files = Files.list(directory.resolve("logs"))) {
            assertThat(files.map(p -> p.getFileName().toString()).toList()).containsExactly("grafana-catalog-diagnostics-user-1.txt");
        }
    }

    @Test
    void boundsReportAndCountsEveryOmittedFailure() throws Exception {
        var report = report(State.UNAVAILABLE, 1000);
        assertThat(report.failures()).hasSize(100);
        service(directory).publish(2L, report, result(State.UNAVAILABLE));
        Path file = directory.resolve("grafana-catalog-diagnostics-user-2.txt");
        assertThat(Files.size(file)).isLessThanOrEqualTo(GrafanaCatalogDiagnosticsService.MAX_REPORT_BYTES);
        String text = Files.readString(file);
        assertThat(text).contains("Пропущено записей ошибок: 900", "HTTP: 403");
        assertThat(text.split("Этап:", -1)).hasSize(101);
    }

    @Test
    void concurrentChecksPublishWholeSeparateReportsForEachUser() throws Exception {
        var service = service(directory);
        var executor = Executors.newFixedThreadPool(6);
        List<GrafanaCatalogDiagnostic> reports = new ArrayList<>();
        List<Future<GrafanaCatalogResult>> tasks = new ArrayList<>();
        try {
            for (int i = 0; i < 24; i++) {
                var report = report(State.PARTIAL, 1);
                reports.add(report);
                long owner = i % 2 + 1;
                tasks.add(executor.submit(() -> service.publish(owner, report, result(State.PARTIAL))));
            }
            for (var task : tasks) {
                assertThat(task.get().message()).contains("Диагностика: logs/");
            }
        } finally {
            executor.shutdownNow();
        }
        for (long owner : List.of(1L, 2L)) {
            String text = Files.readString(directory.resolve("grafana-catalog-diagnostics-user-" + owner + ".txt"));
            assertThat(reports.stream().filter(r -> text.contains(r.checkId().toString())).count()).isEqualTo(1);
            assertThat(text).contains("Категория: HTTP_ERROR", "HTTP: 403").endsWith("\n");
        }
        try (var files = Files.list(directory)) {
            assertThat(files.count()).isEqualTo(2);
        }
    }

    @Test
    void blockedDirectoryPreservesResultAndDoesNotClaimOldReportIsCurrent() throws Exception {
        Path blocked = directory.resolve("blocked");
        Files.writeString(blocked, "synthetic-secret");
        var service = service(blocked);
        var original = result(State.PARTIAL);
        var published = service.publish(3L, report(State.PARTIAL, 1), original);
        assertThat(published.status()).isEqualTo(original.status());
        assertThat(published.updatedAt()).isEqualTo(original.updatedAt());
        assertThat(published.message()).startsWith(original.message()).contains("Не удалось сохранить диагностику этой проверки")
                .doesNotContain("Диагностика: logs/", "synthetic-secret", blocked.toString());
    }

    @Test
    void createWriteAndMoveFailuresRetainOldFileAndCleanTemporaryFiles() throws Exception {
        var original = result(State.STALE);
        Path file = directory.resolve("grafana-catalog-diagnostics-user-1.txt");
        for (String operation : List.of("create", "write", "move")) {
            Files.writeString(file, "previous report");
            var service = spy(service(directory));
            var failure = new IOException("synthetic-secret\nforged-log");
            switch (operation) {
                case "create" -> doThrow(failure).when(service).createDirectory();
                case "write" -> doThrow(failure).when(service).writeTemporary(any(), any());
                case "move" -> doThrow(failure).when(service).replaceFile(any(), any());
                default -> throw new AssertionError();
            }
            var published = service.publish(1L, report(State.STALE, 1), original);
            assertThat(published.status()).isEqualTo("STALE");
            assertThat(published.updatedAt()).isEqualTo(original.updatedAt());
            assertThat(published.message()).contains("Не удалось сохранить диагностику этой проверки").doesNotContain("synthetic-secret", "previous report");
            assertThat(Files.readString(file)).isEqualTo("previous report");
            try (var files = Files.list(directory)) {
                assertThat(files.count()).isEqualTo(1);
            }
        }
    }

    @Test
    void cleanupFailureIsSafeAndDoesNotChangeSuccessfulPublication() throws Exception {
        var service = spy(service(directory));
        doThrow(new IOException("synthetic-secret")).when(service).deleteTemporary(any());
        var published = service.publish(1L, report(State.READY, 0), result(State.READY));
        assertThat(published.status()).isEqualTo("READY");
        assertThat(published.message()).contains("Диагностика: logs/").doesNotContain("synthetic-secret");
        assertThat(Files.readString(directory.resolve("grafana-catalog-diagnostics-user-1.txt"))).contains("Результат: READY");
    }

    @Test
    void unknownUserDoesNotOverwriteGlobalFileAndOnlyAllowlistedFieldsReachLogs() throws Exception {
        var user = mock(CurrentUserProvider.class);
        when(user.currentUserId()).thenThrow(new IllegalStateException("synthetic-secret\nforged-log"));
        var service = new GrafanaCatalogDiagnosticsService(user, directory);
        Logger logger = (Logger) LoggerFactory.getLogger(GrafanaCatalogDiagnosticsService.class);
        var events = new ListAppender<ILoggingEvent>();
        events.start();
        logger.addAppender(events);
        try {
            var result = service.unavailable();
            assertThat(result.status()).isEqualTo("UNAVAILABLE");
            assertThat(result.message()).contains("Не удалось сохранить диагностику").doesNotContain("synthetic-secret");
            var unsafeKey = new Failure(Stage.DEPLOYMENT_VALUES, Category.HTTP_ERROR, 403, "synthetic-secret\nforged-log");
            Instant now = Instant.now();
            service.publish(5L, new GrafanaCatalogDiagnostic(UUID.randomUUID(), now, now, State.UNAVAILABLE,
                    0, 1, 0, 0, 0, List.of(unsafeKey)), result(State.UNAVAILABLE));
            assertThat(Files.readString(directory.resolve("grafana-catalog-diagnostics-user-5.txt")))
                    .doesNotContain("synthetic-secret", "forged-log", "http://", "Authorization", " at ");
            assertThat(events.list).allSatisfy(event -> {
                assertThat(event.getFormattedMessage()).doesNotContain("synthetic-secret", "forged-log", "http://");
                assertThat(event.getThrowableProxy()).isNull();
            });
            assertThat(directory.resolve("grafana-catalog-diagnostics-user-0.txt")).doesNotExist();
        } finally {
            logger.detachAppender(events);
            events.stop();
        }
    }

    private GrafanaCatalogDiagnosticsService service(Path path) {
        return new GrafanaCatalogDiagnosticsService(mock(CurrentUserProvider.class), path);
    }

    private GrafanaCatalogDiagnostic report(State state, int failures) {
        Instant now = Instant.now();
        return new GrafanaCatalogDiagnostic(UUID.randomUUID(), now, now, state, state == State.PARTIAL ? 1 : 0,
                failures, 0, 0, 0, IntStream.range(0, failures)
                .mapToObj(i -> new Failure(Stage.DEPLOYMENT_VALUES, Category.HTTP_ERROR, 403, "a".repeat(64))).toList());
    }

    private GrafanaCatalogResult result(State state) {
        return new GrafanaCatalogResult(state.name(), "Прежний результат каталога.", Instant.now());
    }
}
