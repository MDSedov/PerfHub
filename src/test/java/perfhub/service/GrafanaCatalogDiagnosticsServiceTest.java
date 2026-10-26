package perfhub.service;

import perfhub.dto.grafana.GrafanaCatalogDiagnostic;
import perfhub.dto.grafana.GrafanaCatalogDiagnostic.Category;
import perfhub.dto.grafana.GrafanaCatalogDiagnostic.Failure;
import perfhub.dto.grafana.GrafanaCatalogDiagnostic.Stage;
import perfhub.dto.grafana.GrafanaCatalogDiagnostic.State;
import perfhub.dto.grafana.GrafanaCatalogResult;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;
import static org.assertj.core.api.Assertions.assertThat;

class GrafanaCatalogDiagnosticsServiceTest {
    @TempDir
    Path directory;
    private final GrafanaCatalogDiagnosticsService service = new GrafanaCatalogDiagnosticsService();
    private Logger logger;
    private Level previousLevel;
    private ListAppender<ILoggingEvent> events;

    @BeforeEach
    void captureLogs() {
        logger = (Logger) LoggerFactory.getLogger(GrafanaCatalogDiagnosticsService.class);
        previousLevel = logger.getLevel();
        logger.setLevel(Level.INFO);
        events = new ListAppender<>();
        events.start();
        logger.addAppender(events);
    }

    @AfterEach
    void stopCapturingLogs() {
        logger.detachAppender(events);
        logger.setLevel(previousLevel);
        events.stop();
    }

    @ParameterizedTest
    @EnumSource(value = State.class, names = {"READY", "EMPTY"})
    void successfulPublicationKeepsResultWithoutRoutineLogOrDiagnosticSuffix(State state) {
        var original = result(state);
        assertThat(service.publish(1L, report(state, 0), original)).isSameAs(original);
        assertThat(events.list).isEmpty();
    }

    @ParameterizedTest
    @EnumSource(value = State.class, names = {"PARTIAL", "STALE", "UNAVAILABLE"})
    void failedPublicationKeepsResultAndOnlyActualFailureWarning(State state) {
        var original = result(state);
        assertThat(service.publish(1L, report(state, 1), original)).isSameAs(original);
        assertThat(events.list).singleElement().satisfies(event -> {
            assertThat(event.getLevel()).isEqualTo(Level.WARN);
            assertThat(event.getFormattedMessage()).contains("stage=DEPLOYMENT_VALUES", "category=HTTP_ERROR", "http=403")
                    .doesNotContain("result=", "updated=", "failed=", "globalFailures=", "omitted=");
            assertThat(event.getThrowableProxy()).isNull();
        });
    }

    @Test
    void boundedFailureWarningsDoNotReintroduceRoutineSummary() {
        var diagnostic = report(State.UNAVAILABLE, 1000);
        assertThat(diagnostic.failures()).hasSize(100);
        assertThat(diagnostic.omittedFailures()).isEqualTo(900);
        service.publish(2L, diagnostic, result(State.UNAVAILABLE));
        assertThat(events.list).hasSize(100).allSatisfy(event -> {
            assertThat(event.getLevel()).isEqualTo(Level.WARN);
            assertThat(event.getFormattedMessage()).contains("stage=DEPLOYMENT_VALUES", "http=403").doesNotContain("result=");
        });
    }

    @Test
    void unexpectedFailureAndUntrustedMetadataProduceOnlySafeWarnings() {
        var unavailable = service.unavailable();
        assertThat(unavailable.status()).isEqualTo("UNAVAILABLE");
        assertThat(unavailable.message()).isEqualTo("Grafana доступна, но каталог обновить не удалось. Повторите проверку подключения.");
        Instant now = Instant.now();
        var unsafeKey = new Failure(Stage.DEPLOYMENT_VALUES, Category.HTTP_ERROR, 403, "synthetic-secret\nforged-log");
        var original = result(State.UNAVAILABLE);
        assertThat(service.publish(null, new GrafanaCatalogDiagnostic(UUID.randomUUID(), now, now, State.UNAVAILABLE,
                0, 1, 0, 0, 0, List.of(unsafeKey)), original)).isSameAs(original);
        assertThat(events.list).hasSize(2).allSatisfy(event -> {
            assertThat(event.getLevel()).isEqualTo(Level.WARN);
            assertThat(event.getFormattedMessage()).doesNotContain("synthetic-secret", "forged-log", "http://", "Authorization", "result=");
            assertThat(event.getThrowableProxy()).isNull();
        });
        assertThat(events.list.get(0).getFormattedMessage()).contains("stage=CATALOG_INITIALIZE", "category=UNKNOWN", "http=null");
        assertThat(events.list.get(1).getFormattedMessage()).contains("dashboardKey=null", "stage=DEPLOYMENT_VALUES", "http=403");
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void publicationCreatesNoFilesAndLeavesExistingReportsUntouched(boolean existingReports) throws Exception {
        Path logs = directory.resolve("logs");
        Path previousReport = logs.resolve("grafana-catalog-diagnostics-user-1.txt");
        Path unrelatedLog = logs.resolve("unrelated.log");
        if (existingReports) {
            Files.createDirectory(logs);
            Files.writeString(previousReport, "previous diagnostic report");
            Files.writeString(unrelatedLog, "keep existing log");
        }
        var previousModifiedAt = existingReports ? Files.getLastModifiedTime(previousReport) : null;
        var unrelatedModifiedAt = existingReports ? Files.getLastModifiedTime(unrelatedLog) : null;
        List<Path> before;
        try (var paths = Files.walk(directory)) {
            before = paths.map(directory::relativize).sorted().toList();
        }
        String javaExecutable = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        String classpath = System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
        var process = new ProcessBuilder(javaExecutable, "-cp", classpath, NoFileProbe.class.getName())
                .directory(directory.toFile()).redirectErrorStream(true).start();
        try {
            assertThat(process.waitFor(15, TimeUnit.SECONDS)).isTrue();
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            assertThat(process.exitValue()).withFailMessage(output).isZero();
        } finally {
            process.destroyForcibly();
        }
        try (var paths = Files.walk(directory)) {
            assertThat(paths.map(directory::relativize).sorted().toList()).isEqualTo(before);
        }
        if (existingReports) {
            assertThat(Files.readString(previousReport)).isEqualTo("previous diagnostic report");
            assertThat(Files.getLastModifiedTime(previousReport)).isEqualTo(previousModifiedAt);
            assertThat(Files.readString(unrelatedLog)).isEqualTo("keep existing log");
            assertThat(Files.getLastModifiedTime(unrelatedLog)).isEqualTo(unrelatedModifiedAt);
        } else {
            assertThat(logs).doesNotExist();
        }
    }

    public static class NoFileProbe {
        public static void main(String[] args) {
            var service = new GrafanaCatalogDiagnosticsService();
            for (State state : State.values()) {
                service.publish(1L, report(state, state == State.READY || state == State.EMPTY ? 0 : 1), result(state));
                service.publish(2L, report(state, 0), result(state));
            }
            service.unavailable();
        }
    }

    private static GrafanaCatalogDiagnostic report(State state, int failures) {
        Instant now = Instant.now();
        return new GrafanaCatalogDiagnostic(UUID.randomUUID(), now, now, state, state == State.PARTIAL ? 1 : 0,
                failures, 0, 0, 0, IntStream.range(0, failures)
                .mapToObj(i -> new Failure(Stage.DEPLOYMENT_VALUES, Category.HTTP_ERROR, 403, "a".repeat(64))).toList());
    }

    private static GrafanaCatalogResult result(State state) {
        return new GrafanaCatalogResult(state.name(), "Прежний результат каталога.", Instant.now());
    }
}
