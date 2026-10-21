package perfhub.client;

import perfhub.dto.grafana.GrafanaRenderOptions;
import perfhub.exception.GrafanaClientException;
import perfhub.service.ConfigurationProvider;
import perfhub.support.DropAppFixtures;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.client.response.MockRestResponseCreators;
import org.springframework.web.client.RestTemplate;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GrafanaDeploymentClientTest {
    private GrafanaClient client;
    private MockRestServiceServer server;

    @BeforeEach
    void setUp() {
        var rest = new RestTemplate();
        server = MockRestServiceServer.createServer(rest);
        Map<String,String> settings = Map.of("base-url","http://grafana.example","render-path","/render/d-solo/","api-key","synthetic-token");
        ConfigurationProvider provider = new ConfigurationProvider() {
            public Optional<String> findValue(String service, String key) { return Optional.ofNullable(settings.get(key)); }
            public String getRequiredValue(String service, String key) { return settings.get(key); }
        };
        client = new GrafanaClient(rest,provider);
    }

    @Test
    void scopedReferenceUsesExistingRouteAuthAndConfiguredSize() {
        server.expect(request -> {
            assertThat(request.getURI().getPath()).isEqualTo("/render/d-solo/demo/dropapp");
            assertThat(request.getURI().getQuery()).contains("panelId=profile$panel-106","var-pod=$__all","var-container=$__all","width=1000","height=1000");
            assertThat(request.getHeaders().getFirst("Authorization")).isEqualTo("Bearer synthetic-token");
        }).andRespond(MockRestResponseCreators.withSuccess(DropAppFixtures.PNG,MediaType.IMAGE_PNG));
        assertThat(client.renderDeploymentPanel("demo","dropapp","profile$panel-106",Instant.EPOCH,Instant.EPOCH.plusSeconds(60),
                Map.of("deployment","profile","pod","$__all","container","$__all"),new GrafanaRenderOptions(1000,1000,"Europe/Moscow")))
                .isEqualTo(DropAppFixtures.PNG);
        server.verify();
    }

    @Test
    void labelValuesUsesSelectorLiterallyWithoutSendingLabelValuesAsPromql() {
        server.expect(request -> {
            assertThat(request.getURI().getPath()).isEqualTo("/api/datasources/proxy/uid/demo-source/api/v1/label/deployment/values");
            assertThat(request.getURI().getQuery()).isEqualTo("match[]={\"metric{namespace=~\"demo\"}\"}");
        }).andRespond(MockRestResponseCreators.withSuccess("{\"status\":\"success\",\"data\":[\"profile\"]}",MediaType.APPLICATION_JSON));
        assertThat(client.loadDeploymentLabelValues("demo-source","{\"metric{namespace=~\"demo\"}\"}")).containsExactly("profile");
        server.verify();
    }

    @Test
    void scopedEmptyAndNonPngAreFailures() {
        server.expect(request -> {}).andRespond(MockRestResponseCreators.withSuccess(new byte[0],MediaType.IMAGE_PNG));
        assertThatThrownBy(() -> client.renderDeploymentPanel("demo","dropapp","profile$panel-106",Instant.EPOCH,Instant.EPOCH.plusSeconds(1),
                Map.of(),new GrafanaRenderOptions(800,600,"UTC"))).isInstanceOf(GrafanaClientException.class);
        server.verify();
    }
}
