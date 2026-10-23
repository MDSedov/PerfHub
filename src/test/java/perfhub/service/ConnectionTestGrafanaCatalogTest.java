package perfhub.service;

import perfhub.client.GrafanaClient;
import perfhub.security.CurrentUserProvider;
import java.util.Optional;
import java.nio.file.Path;
import java.nio.file.Files;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.clearInvocations;

class ConnectionTestGrafanaCatalogTest {
    @TempDir
    Path reports;
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
    void unexpectedCatalogFailureWritesReportThroughTheSameSafeComponent() throws Exception {
        var client = mock(GrafanaClient.class);
        var catalog = mock(GrafanaRenderCatalogService.class);
        var user = mock(CurrentUserProvider.class);
        when(user.currentUserId()).thenReturn(Optional.of(7L));
        var diagnostics = new GrafanaCatalogDiagnosticsService(user, reports);
        var service = new ConnectionTestService(null, null, client, null, null, null, null, catalog, diagnostics);
        when(client.checkHealth()).thenReturn("ok");
        when(catalog.refresh()).thenThrow(new IllegalStateException("private-exception"));
        var result = service.testConnection("grafana");
        assertThat(result.success()).isTrue();
        assertThat(result.message()).isEqualTo("Grafana ответила: ok");
        assertThat(result.catalog().status()).isEqualTo("UNAVAILABLE");
        assertThat(result.catalog().message()).contains("logs/grafana-catalog-diagnostics-user-7.txt").doesNotContain("private-exception");
        assertThat(Files.readString(reports.resolve("grafana-catalog-diagnostics-user-7.txt")))
                .contains("CATALOG_INITIALIZE", "UNKNOWN").doesNotContain("private-exception");
    }
}
