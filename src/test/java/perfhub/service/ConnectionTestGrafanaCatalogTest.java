package perfhub.service;

import perfhub.client.GrafanaClient;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.clearInvocations;

class ConnectionTestGrafanaCatalogTest {
    @Test
    void healthSuccessRemainsSuccessWhenCatalogThrowsAndHealthFailureSkipsCatalog() {
        var client = mock(GrafanaClient.class);
        var catalog = mock(GrafanaRenderCatalogService.class);
        var service = new ConnectionTestService(null,null,client,null,null,null,null,catalog);
        when(client.checkHealth()).thenReturn("ok");
        when(catalog.refresh()).thenThrow(new IllegalStateException("synthetic"));
        var result = service.testConnection("grafana");
        assertThat(result.success()).isTrue();
        assertThat(result.message()).isEqualTo("Grafana ответила: ok");
        assertThat(result.catalog().status()).isEqualTo("UNAVAILABLE");
        clearInvocations(catalog);
        when(client.checkHealth()).thenThrow(new IllegalStateException("synthetic"));
        assertThat(service.testConnection("grafana").success()).isFalse();
        verifyNoInteractions(catalog);
    }

    @Test
    void unexpectedCatalogFailureKeepsSafeWarningWithoutDiagnosticSuffix() {
        var client = mock(GrafanaClient.class);
        var catalog = mock(GrafanaRenderCatalogService.class);
        var diagnostics = new GrafanaCatalogDiagnosticsService();
        var service = new ConnectionTestService(null, null, client, null, null, null, null, catalog, diagnostics);
        when(client.checkHealth()).thenReturn("ok");
        when(catalog.refresh()).thenThrow(new IllegalStateException("private-exception"));
        Logger logger = (Logger) LoggerFactory.getLogger(GrafanaCatalogDiagnosticsService.class);
        Level previousLevel = logger.getLevel();
        logger.setLevel(Level.INFO);
        var events = new ListAppender<ILoggingEvent>();
        events.start();
        logger.addAppender(events);
        try {
            var result = service.testConnection("grafana");
            assertThat(result.success()).isTrue();
            assertThat(result.message()).isEqualTo("Grafana ответила: ok");
            assertThat(result.catalog().status()).isEqualTo("UNAVAILABLE");
            assertThat(result.catalog().message()).isEqualTo("Grafana доступна, но каталог обновить не удалось. Повторите проверку подключения.");
            assertThat(events.list).singleElement().satisfies(event -> {
                assertThat(event.getLevel()).isEqualTo(Level.WARN);
                assertThat(event.getFormattedMessage()).contains("stage=CATALOG_INITIALIZE", "category=UNKNOWN", "http=null")
                        .doesNotContain("private-exception", "result=", "reportWrite", "reportCleanup");
                assertThat(event.getThrowableProxy()).isNull();
            });
        } finally {
            logger.detachAppender(events);
            logger.setLevel(previousLevel);
            events.stop();
        }
    }
}
