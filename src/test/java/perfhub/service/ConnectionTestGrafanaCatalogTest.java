package perfhub.service;

import perfhub.client.GrafanaClient;
import org.junit.jupiter.api.Test;
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
}
