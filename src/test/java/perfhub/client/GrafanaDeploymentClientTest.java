package perfhub.client;

import perfhub.dto.grafana.GrafanaRenderOptions;
import perfhub.exception.GrafanaClientException;
import perfhub.exception.GrafanaCatalogClientException;
import perfhub.dto.grafana.GrafanaCatalogDiagnostic.Category;
import perfhub.dto.grafana.GrafanaCatalogDiagnostic.Stage;
import perfhub.service.ConfigurationProvider;
import perfhub.support.DropAppFixtures;
import java.time.Instant;
import java.util.Map;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.http.HttpStatus;
import java.net.SocketTimeoutException;
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

    @ParameterizedTest
    @EnumSource(value = Stage.class, names = {"DASHBOARD_SEARCH", "DASHBOARD_LOAD", "DEPLOYMENT_VALUES"})
    void catalogKeepsOperationAndHttpFailureWithoutUsingResponseText(Stage stage) {
        for (int status : List.of(400, 401, 403, 404, 500)) {
            server.expect(request -> {
                assertThat(request.getHeaders().getFirst("Authorization")).isEqualTo("Bearer synthetic-token");
                assertThat(request.getMethod()).isEqualTo(org.springframework.http.HttpMethod.GET);
            }).andRespond(MockRestResponseCreators.withStatus(HttpStatus.valueOf(status))
                    .contentType(MediaType.APPLICATION_JSON).body("{\"error\":\"synthetic-secret\\nforged-log\"}"));
            assertThatThrownBy(() -> catalogOperation(stage)).isInstanceOfSatisfying(GrafanaCatalogClientException.class, ex -> {
                assertThat(ex.stage()).isEqualTo(stage);
                assertThat(ex.category()).isEqualTo(Category.HTTP_ERROR);
                assertThat(ex.httpStatus()).isEqualTo(status);
                assertThat(ex.getMessage()).doesNotContain("synthetic-secret", "forged-log");
                assertThat(ex).isInstanceOf(GrafanaClientException.class);
            });
            server.verify();
            server.reset();
        }
    }

    @ParameterizedTest
    @EnumSource(value = Stage.class, names = {"DASHBOARD_SEARCH", "DASHBOARD_LOAD", "DEPLOYMENT_VALUES"})
    void catalogDistinguishesInvalidHttp200FromNoResponse(Stage stage) {
        for (String body : List.of("", "{synthetic-secret")) {
            server.expect(request -> {}).andRespond(MockRestResponseCreators.withSuccess(body, MediaType.APPLICATION_JSON));
            assertInvalidResponse(stage);
            server.verify();
            server.reset();
        }
        server.expect(request -> {}).andRespond(MockRestResponseCreators.withSuccess("<html>synthetic-secret</html>", MediaType.TEXT_HTML));
        assertInvalidResponse(stage);
        server.verify();
        server.reset();
        server.expect(request -> {}).andRespond(request -> { throw new SocketTimeoutException("synthetic-secret"); });
        assertThatThrownBy(() -> catalogOperation(stage)).isInstanceOfSatisfying(GrafanaCatalogClientException.class, ex -> {
            assertThat(ex.stage()).isEqualTo(stage);
            assertThat(ex.category()).isEqualTo(Category.TRANSPORT_ERROR);
            assertThat(ex.httpStatus()).isNull();
            assertThat(ex.getMessage()).doesNotContain("synthetic-secret");
        });
        server.verify();
    }

    @Test
    void catalogRejectsUnexpectedSuccessfulResponseShape() {
        for (String body : List.of("{}", "{\"status\":\"error\",\"data\":[]}",
                "{\"status\":\"success\",\"data\":[5]}")) {
            server.expect(request -> {}).andRespond(MockRestResponseCreators.withSuccess(body, MediaType.APPLICATION_JSON));
            assertInvalidResponse(Stage.DEPLOYMENT_VALUES);
            server.verify();
            server.reset();
        }
        server.expect(request -> {}).andRespond(MockRestResponseCreators.withSuccess("{}", MediaType.APPLICATION_JSON));
        assertInvalidResponse(Stage.DASHBOARD_SEARCH);
        server.verify();
    }

    private void assertInvalidResponse(Stage stage) {
        assertThatThrownBy(() -> catalogOperation(stage)).isInstanceOfSatisfying(GrafanaCatalogClientException.class, ex -> {
            assertThat(ex.stage()).isEqualTo(stage);
            assertThat(ex.category()).isEqualTo(Category.INVALID_RESPONSE);
            assertThat(ex.httpStatus()).isEqualTo(200);
            assertThat(ex.getMessage()).doesNotContain("synthetic-secret", "http://");
        });
    }

    private void catalogOperation(Stage stage) {
        switch (stage) {
            case DASHBOARD_SEARCH -> client.listRenderDashboardUids();
            case DASHBOARD_LOAD -> client.loadRenderDashboard("demo");
            case DEPLOYMENT_VALUES -> client.loadDeploymentLabelValues("demo-source", "up");
            default -> throw new IllegalArgumentException("Unexpected test operation");
        }
    }
}
