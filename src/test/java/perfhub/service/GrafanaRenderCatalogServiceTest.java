package perfhub.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import perfhub.client.GrafanaClient;
import perfhub.client.GrafanaRenderCatalogParser;
import perfhub.dto.grafana.GrafanaRenderDashboard;
import perfhub.dto.grafana.GrafanaCatalogDiagnostic.Category;
import perfhub.dto.grafana.GrafanaCatalogDiagnostic.Stage;
import perfhub.exception.GrafanaCatalogClientException;
import perfhub.repository.GrafanaRenderCatalogRepository;
import perfhub.security.CurrentUserProvider;
import perfhub.support.DropAppFixtures;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.client.response.MockRestResponseCreators;
import org.springframework.web.client.RestTemplate;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabase;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.never;

class GrafanaRenderCatalogServiceTest {
    @TempDir
    Path reports;
    private EmbeddedDatabase database;
    private GrafanaClient client;
    private GrafanaRenderCatalogRepository repository;
    private GrafanaVariableService variables;
    private CurrentUserProvider currentUser;
    private GrafanaRenderCatalogService service;
    private GrafanaRenderCatalogParser parser;
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    @BeforeEach
    void setUp() {
        database = new EmbeddedDatabaseBuilder().generateUniqueName(true).setType(EmbeddedDatabaseType.H2).addScript("schema.sql").build();
        repository = new GrafanaRenderCatalogRepository(new JdbcTemplate(database),mapper);
        client = mock(GrafanaClient.class);
        parser = mock(GrafanaRenderCatalogParser.class);
        variables = mock(GrafanaVariableService.class);
        currentUser = mock(CurrentUserProvider.class);
        when(currentUser.currentUserId()).thenReturn(Optional.of(1L));
        when(client.listRenderDashboardUids()).thenReturn(List.of("demo"));
        when(client.loadRenderDashboard("demo")).thenReturn(mapper.createObjectNode());
        when(parser.parse(eq("demo"),any())).thenReturn(DropAppFixtures.snapshot().dashboard());
        when(client.loadDeploymentLabelValues(anyString(),anyString())).thenReturn(List.of("profile","other","$__all","All","profile"));
        when(variables.loadValues(anyString())).thenReturn(Map.of());
        service = new GrafanaRenderCatalogService(client,parser,repository,currentUser,variables);
    }

    @AfterEach
    void close() { database.shutdown(); }

    @Test
    void refreshPreservesLiteralSelectorAndLocalReadDoesNotCallGrafanaOrNfrDiscover() {
        assertThat(service.refresh().status()).isEqualTo("READY");
        verify(client).loadDeploymentLabelValues("demo-source", "{\"kube_deployment_status_replicas{namespace=~\"demo\"}\"}");
        var snapshot = service.localSnapshots().get(0);
        assertThat(snapshot.deployments()).extracting(o -> o.value()).containsExactly("other","profile");
        clearInvocations(client);
        assertThat(service.findPanel("demo",106)).isPresent();
        verifyNoInteractions(client);
    }

    @Test
    void failureRetainsSnapshotAsStaleAndEmptySuccessReplacesOptions() {
        service.refresh();
        when(client.loadDeploymentLabelValues(anyString(),anyString())).thenThrow(new IllegalStateException("synthetic"));
        assertThat(service.refresh().status()).isEqualTo("STALE");
        assertThat(service.localSnapshots().get(0).stale()).isTrue();
        assertThat(service.localSnapshots().get(0).deployments()).hasSize(2);
        doReturn(List.of()).when(client).loadDeploymentLabelValues(anyString(),anyString());
        assertThat(service.refresh().status()).isEqualTo("EMPTY");
        assertThat(service.localSnapshots().get(0).deployments()).isEmpty();
    }

    @Test
    void isolatesOwnerConnectionAndNamespaceAndRejectsLateRevision() {
        service.refresh();
        when(currentUser.currentUserId()).thenReturn(Optional.of(2L));
        assertThat(service.localSnapshots()).isEmpty();
        when(currentUser.currentUserId()).thenReturn(Optional.of(1L));
        when(variables.loadValues("demo")).thenReturn(Map.of("datasource","other-source"));
        assertThat(service.localSnapshots()).isEmpty();
        when(variables.loadValues("demo")).thenReturn(Map.of("namespace","other"));
        assertThat(service.localSnapshots()).isEmpty();
        when(variables.loadValues("demo")).thenReturn(Map.of());
        when(client.loadDeploymentLabelValues(anyString(),anyString())).thenAnswer(call -> { repository.rotate(1); return List.of("profile"); });
        assertThat(service.refresh().status()).isEqualTo("UNAVAILABLE");
        assertThat(service.localSnapshots()).isEmpty();
    }

    @Test
    void panelOptionApplicabilityUsesOnlyCurrentOwnerSourceAndVariableContext() {
        var dashboards = mock(GrafanaDashboardSettingsService.class);
        when(dashboards.listDashboards()).thenReturn(List.of(new perfhub.dto.settings.GrafanaDashboardDetailsDto(
                "demo", "dropapp", "DropApp", true,
                List.of(new perfhub.dto.settings.GrafanaDashboardPanelDto(106, "CPU", true, null, null, false)), List.of())));
        var templates = new ConfluenceTemplateSettingsService(null, null, null, dashboards, null, service);
        assertThat(templates.listPanelOptions().get(0).deploymentApplicability()).isEqualTo("UNKNOWN");
        service.refresh();
        clearInvocations(client);
        assertThat(templates.listPanelOptions()).allSatisfy(option ->
                assertThat(option.deploymentApplicability()).isEqualTo("SUPPORTED"));
        when(currentUser.currentUserId()).thenReturn(Optional.of(2L));
        assertThat(templates.listPanelOptions()).singleElement().satisfies(option ->
                assertThat(option.deploymentApplicability()).isEqualTo("UNKNOWN"));
        when(currentUser.currentUserId()).thenReturn(Optional.of(1L));
        for (var changed : List.of(Map.of("namespace", "other"), Map.of("datasource", "other-source"))) {
            when(variables.loadValues("demo")).thenReturn(changed);
            assertThat(templates.listPanelOptions()).singleElement().satisfies(option ->
                    assertThat(option.deploymentApplicability()).isEqualTo("UNKNOWN"));
        }
        when(variables.loadValues("demo")).thenReturn(Map.of());
        repository.rotate(1);
        assertThat(templates.listPanelOptions()).singleElement().satisfies(option ->
                assertThat(option.deploymentApplicability()).isEqualTo("UNKNOWN"));
        verifyNoInteractions(client);
    }

    @Test
    void regexKeepsTextAndValueDistinctAndUnsupportedQueryDoesNotPublish() {
        var dashboard = DropAppFixtures.snapshot().dashboard();
        var vars = new java.util.LinkedHashMap<>(dashboard.variables());
        var original = vars.get("deployment");
        vars.put("deployment",new GrafanaRenderDashboard.Variable(original.name(),original.type(),original.datasource(),
                original.query(),1,"/(?<text>[^:]+):(?<value>[^:]+)$/","value",true,true,null,"profile"));
        when(parser.parse(eq("demo"),any())).thenReturn(new GrafanaRenderDashboard("demo","dropapp","DropApp",dashboard.panels(),vars));
        when(client.loadDeploymentLabelValues(anyString(),anyString())).thenReturn(List.of("Profile:profile"));
        service.refresh();
        assertThat(service.localSnapshots().get(0).deployments()).containsExactly(new GrafanaRenderDashboard.Option("Profile","profile"));
        vars.put("deployment",DropAppFixtures.variable("deployment","query_result(up)","profile",true));
        assertThat(service.refresh().status()).isEqualTo("STALE");
    }

    @Test
    void partialDashboardFailureDoesNotOverwriteSuccessfulDashboard() {
        when(client.listRenderDashboardUids()).thenReturn(List.of("demo","unknown"));
        when(client.loadRenderDashboard("unknown")).thenThrow(new IllegalStateException("synthetic"));
        assertThat(service.refresh().status()).isEqualTo("PARTIAL");
        assertThat(service.localSnapshots()).hasSize(1);
    }

    @Test
    void reportsPartialPerDashboardThenReplacesItOnRecoveryWithoutExtraRequests() throws Exception {
        service = withDiagnostics(repository);
        when(client.listRenderDashboardUids()).thenReturn(List.of("demo", "private-dashboard"));
        when(client.loadRenderDashboard("private-dashboard")).thenThrow(new GrafanaCatalogClientException(
                Stage.DASHBOARD_LOAD, Category.HTTP_ERROR, 404, new IllegalStateException("private-response")));
        var partial = service.refresh();
        assertThat(partial.status()).isEqualTo("PARTIAL");
        assertThat(partial.message()).contains("logs/grafana-catalog-diagnostics-user-1.txt");
        String old = reportText();
        assertThat(old).contains("Обновлено дашбордов: 1", "Ошибок дашбордов: 1", "Общих ошибок: 0", "DASHBOARD_LOAD", "HTTP: 404")
                .doesNotContain("private-dashboard", "private-response");
        when(client.listRenderDashboardUids()).thenReturn(List.of("demo"));
        assertThat(service.refresh().status()).isEqualTo("READY");
        String recovered = reportText();
        assertThat(recovered).contains("Результат: READY", "Ошибок дашбордов: 0").doesNotContain("HTTP: 404");
        assertThat(recovered).isNotEqualTo(old);
        clearInvocations(client);
        assertThat(service.findPanel("demo", 106)).isPresent();
        assertThat(reportText()).isEqualTo(recovered);
        verifyNoInteractions(client);
    }

    @Test
    void searchFailureCountsAsGlobalAndRetainsPreviouslyPublishedSnapshot() throws Exception {
        service = withDiagnostics(repository);
        service.refresh();
        when(client.listRenderDashboardUids()).thenThrow(new GrafanaCatalogClientException(
                Stage.DASHBOARD_SEARCH, Category.TRANSPORT_ERROR, null, new IllegalStateException("private-error")));
        assertThat(service.refresh().status()).isEqualTo("STALE");
        assertThat(reportText()).contains("Ошибок дашбордов: 0", "Общих ошибок: 1", "DASHBOARD_SEARCH", "TRANSPORT_ERROR", "HTTP: отсутствует")
                .doesNotContain("private-error");
        assertThat(service.localSnapshots().get(0).stale()).isTrue();
    }

    @Test
    void reportsParserAndInvalidContextWithoutPublishingSnapshot() throws Exception {
        service = withDiagnostics(repository);
        when(parser.parse(eq("demo"), any())).thenThrow(new IllegalArgumentException("private-metadata"));
        assertThat(service.refresh().status()).isEqualTo("UNAVAILABLE");
        assertThat(reportText()).contains("DASHBOARD_PARSE", "UNSUPPORTED_METADATA").doesNotContain("private-metadata");
        doReturn(DropAppFixtures.snapshot().dashboard()).when(parser).parse(eq("demo"), any());
        when(variables.loadValues("demo")).thenReturn(Map.of("namespace", "*private-namespace"));
        clearInvocations(client);
        assertThat(service.refresh().status()).isEqualTo("UNAVAILABLE");
        assertThat(reportText()).contains("DEPLOYMENT_CONTEXT", "INVALID_CONTEXT").doesNotContain("private-namespace");
        verify(client, never()).loadDeploymentLabelValues(anyString(), anyString());
        when(variables.loadValues("demo")).thenReturn(Map.of("datasource", "$private-source"));
        assertThat(service.refresh().status()).isEqualTo("UNAVAILABLE");
        assertThat(reportText()).contains("DEPLOYMENT_CONTEXT", "INVALID_CONTEXT").doesNotContain("private-source");
        assertThat(service.localSnapshots()).isEmpty();
    }

    @Test
    void recordsContextChangeAndRejectedLatePublication() throws Exception {
        service = withDiagnostics(repository);
        when(variables.loadValues("demo")).thenReturn(Map.of(), Map.of("namespace", "other"));
        assertThat(service.refresh().status()).isEqualTo("UNAVAILABLE");
        assertThat(reportText()).contains("Этап: CONTEXT_CHECK", "Категория: CONTEXT_CHANGED", "HTTP: отсутствует");
        when(variables.loadValues("demo")).thenReturn(Map.of());
        when(client.loadDeploymentLabelValues(anyString(), anyString())).thenAnswer(call -> {
            repository.rotate(1);
            return List.of("profile");
        });
        assertThat(service.refresh().status()).isEqualTo("UNAVAILABLE");
        assertThat(reportText()).contains("Этап: SNAPSHOT_PUBLISH", "Категория: CONTEXT_CHANGED");
        assertThat(service.localSnapshots()).isEmpty();
    }

    @Test
    void reportsStorageFailureBeforeLoopWithoutLeakingException() throws Exception {
        var broken = mock(GrafanaRenderCatalogRepository.class);
        when(broken.revision(1)).thenThrow(new IllegalStateException("private-storage"));
        service = withDiagnostics(broken);
        assertThat(service.refresh().status()).isEqualTo("UNAVAILABLE");
        assertThat(reportText()).contains("CATALOG_INITIALIZE", "LOCAL_STORAGE_ERROR", "Общих ошибок: 1").doesNotContain("private-storage");
        verifyNoInteractions(client);
        doReturn("test-revision").when(broken).revision(1);
        when(broken.save(eq(1L), eq("test-revision"), any())).thenThrow(new IllegalStateException("private-save"));
        assertThat(service.refresh().status()).isEqualTo("UNAVAILABLE");
        assertThat(reportText()).contains("SNAPSHOT_PUBLISH", "LOCAL_STORAGE_ERROR", "Ошибок дашбордов: 1").doesNotContain("private-save");
    }

    @Test
    void realHttpFailureProducesSafeFileLogsAndConnectionResponse() throws Exception {
        var rest = new RestTemplate();
        var http = MockRestServiceServer.createServer(rest);
        var settings = Map.of("base-url", "http://grafana.example/private-base", "api-key", "private-token");
        var provider = new ConfigurationProvider() {
            public Optional<String> findValue(String service, String key) { return Optional.ofNullable(settings.get(key)); }
            public String getRequiredValue(String service, String key) { return settings.get(key); }
        };
        var transport = new GrafanaClient(rest, provider);
        http.expect(request -> assertThat(request.getURI().getPath()).endsWith("/api/search"))
                .andRespond(MockRestResponseCreators.withSuccess("[{\"uid\":\"private-uid\"}]", MediaType.APPLICATION_JSON));
        http.expect(request -> assertThat(request.getURI().getPath()).endsWith("/api/dashboards/uid/private-uid"))
                .andRespond(MockRestResponseCreators.withSuccess("{}", MediaType.APPLICATION_JSON));
        http.expect(request -> {
            assertThat(request.getHeaders().getFirst("Authorization")).isEqualTo("Bearer private-token");
            assertThat(request.getURI().getQuery()).contains("private-namespace");
        }).andRespond(MockRestResponseCreators.withStatus(HttpStatus.FORBIDDEN)
                .contentType(MediaType.APPLICATION_JSON).body("{\"error\":\"private-response\\nforged-log\"}"));
        var dashboard = DropAppFixtures.snapshot().dashboard();
        when(parser.parse(eq("private-uid"), any())).thenReturn(new GrafanaRenderDashboard(dashboard.uid(),
                dashboard.slug(), "private-title", dashboard.panels(), dashboard.variables()));
        when(variables.loadValues("demo")).thenReturn(Map.of("namespace", "private-namespace"));
        var diagnostics = new GrafanaCatalogDiagnosticsService(currentUser, reports);
        service = new GrafanaRenderCatalogService(transport, parser, repository, currentUser, variables, diagnostics);
        when(client.checkHealth()).thenReturn("ok");
        var connection = new ConnectionTestService(null, null, client, null, null, null, null, service, diagnostics);
        Logger logger = (Logger) LoggerFactory.getLogger(GrafanaCatalogDiagnosticsService.class);
        var events = new ListAppender<ILoggingEvent>();
        events.start();
        logger.addAppender(events);
        try {
            var result = connection.testConnection("grafana");
            assertThat(result.success()).isTrue();
            assertThat(result.message()).isEqualTo("Grafana ответила: ok");
            assertThat(result.catalog().status()).isEqualTo("UNAVAILABLE");
            String text = reportText();
            assertThat(text).contains("DEPLOYMENT_VALUES", "HTTP: 403", "HTTP_ERROR");
            String[] secrets = {"private-base", "private-token", "private-uid", "private-title", "private-namespace", "private-response", "forged-log", "Authorization", "http://"};
            assertThat(text).doesNotContain(secrets);
            assertThat(mapper.writeValueAsString(result)).doesNotContain(secrets);
            assertThat(events.list).allSatisfy(event -> {
                assertThat(event.getFormattedMessage()).doesNotContain(secrets);
                assertThat(event.getThrowableProxy()).isNull();
            });
            var json = mapper.valueToTree(result);
            assertThat(json.properties()).extracting(Map.Entry::getKey).containsExactlyInAnyOrder("service", "success", "message", "catalog");
            assertThat(json.path("catalog").properties()).extracting(Map.Entry::getKey).containsExactlyInAnyOrder("status", "message", "updatedAt");
            http.verify();
        } finally {
            logger.detachAppender(events);
            events.stop();
        }
    }

    private GrafanaRenderCatalogService withDiagnostics(GrafanaRenderCatalogRepository source) {
        return new GrafanaRenderCatalogService(client, parser, source, currentUser, variables,
                new GrafanaCatalogDiagnosticsService(currentUser, reports));
    }

    private String reportText() throws Exception {
        return Files.readString(reports.resolve("grafana-catalog-diagnostics-user-1.txt"));
    }
}
