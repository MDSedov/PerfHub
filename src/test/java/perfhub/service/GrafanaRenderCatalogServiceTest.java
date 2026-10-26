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
import java.net.SocketTimeoutException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.client.ResponseCreator;
import org.springframework.test.web.client.response.MockRestResponseCreators;
import org.springframework.web.client.RestTemplate;
import ch.qos.logback.classic.Level;
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
    private Logger diagnosticLogger;
    private Level previousLogLevel;
    private ListAppender<ILoggingEvent> diagnosticEvents;
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
        diagnosticLogger = (Logger) LoggerFactory.getLogger(GrafanaCatalogDiagnosticsService.class);
        previousLogLevel = diagnosticLogger.getLevel();
        diagnosticLogger.setLevel(Level.INFO);
        diagnosticEvents = new ListAppender<>();
        diagnosticEvents.start();
        diagnosticLogger.addAppender(diagnosticEvents);
    }

    @AfterEach
    void close() {
        diagnosticLogger.detachAppender(diagnosticEvents);
        diagnosticLogger.setLevel(previousLogLevel);
        diagnosticEvents.stop();
        database.shutdown();
    }

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
    void warnsForFailedDashboardThenRecoversWithoutRoutineLogsOrExtraRequests() {
        service = withDiagnostics(repository);
        when(client.listRenderDashboardUids()).thenReturn(List.of("demo", "private-dashboard"));
        when(client.loadRenderDashboard("private-dashboard")).thenThrow(new GrafanaCatalogClientException(
                Stage.DASHBOARD_LOAD, Category.HTTP_ERROR, 404, new IllegalStateException("private-response")));
        var partial = service.refresh();
        assertThat(partial.status()).isEqualTo("PARTIAL");
        assertThat(partial.message()).doesNotContain("logs/", "Диагностика", "ID проверки");
        assertThat(diagnosticEvents.list).hasSize(1);
        assertThat(warningText()).contains("stage=DASHBOARD_LOAD", "category=HTTP_ERROR", "http=404")
                .doesNotContain("private-dashboard", "private-response");
        assertThat(service.localSnapshots()).hasSize(1);
        diagnosticEvents.list.clear();
        when(client.listRenderDashboardUids()).thenReturn(List.of("demo"));
        assertThat(service.refresh().status()).isEqualTo("READY");
        assertThat(diagnosticEvents.list).isEmpty();
        clearInvocations(client);
        assertThat(service.findPanel("demo", 106)).isPresent();
        assertThat(diagnosticEvents.list).isEmpty();
        verifyNoInteractions(client);
    }

    @Test
    void searchFailureWarnsAndRetainsPreviouslyPublishedSnapshot() {
        service = withDiagnostics(repository);
        service.refresh();
        when(client.listRenderDashboardUids()).thenThrow(new GrafanaCatalogClientException(
                Stage.DASHBOARD_SEARCH, Category.TRANSPORT_ERROR, null, new IllegalStateException("private-error")));
        assertThat(service.refresh().status()).isEqualTo("STALE");
        assertThat(diagnosticEvents.list).hasSize(1);
        assertThat(warningText()).contains("stage=DASHBOARD_SEARCH", "category=TRANSPORT_ERROR", "http=null")
                .doesNotContain("private-error");
        assertThat(service.localSnapshots().get(0).stale()).isTrue();
    }

    @Test
    void warnsForParserAndInvalidContextWithoutPublishingSnapshot() {
        service = withDiagnostics(repository);
        when(parser.parse(eq("demo"), any())).thenThrow(new IllegalArgumentException("private-metadata"));
        assertThat(service.refresh().status()).isEqualTo("UNAVAILABLE");
        assertThat(diagnosticEvents.list).hasSize(1);
        assertThat(warningText()).contains("stage=DASHBOARD_PARSE", "category=UNSUPPORTED_METADATA").doesNotContain("private-metadata");
        diagnosticEvents.list.clear();
        doReturn(DropAppFixtures.snapshot().dashboard()).when(parser).parse(eq("demo"), any());
        when(variables.loadValues("demo")).thenReturn(Map.of("namespace", "*private-namespace"));
        clearInvocations(client);
        assertThat(service.refresh().status()).isEqualTo("UNAVAILABLE");
        assertThat(diagnosticEvents.list).hasSize(1);
        assertThat(warningText()).contains("stage=DEPLOYMENT_CONTEXT", "category=INVALID_CONTEXT").doesNotContain("private-namespace");
        verify(client, never()).loadDeploymentLabelValues(anyString(), anyString());
        diagnosticEvents.list.clear();
        when(variables.loadValues("demo")).thenReturn(Map.of("datasource", "$private-source"));
        assertThat(service.refresh().status()).isEqualTo("UNAVAILABLE");
        assertThat(diagnosticEvents.list).hasSize(1);
        assertThat(warningText()).contains("stage=DEPLOYMENT_CONTEXT", "category=INVALID_CONTEXT").doesNotContain("private-source");
        assertThat(service.localSnapshots()).isEmpty();
    }

    @Test
    void warnsForContextChangeAndRejectedLatePublication() {
        service = withDiagnostics(repository);
        when(variables.loadValues("demo")).thenReturn(Map.of(), Map.of("namespace", "other"));
        assertThat(service.refresh().status()).isEqualTo("UNAVAILABLE");
        assertThat(diagnosticEvents.list).hasSize(1);
        assertThat(warningText()).contains("stage=CONTEXT_CHECK", "category=CONTEXT_CHANGED", "http=null");
        diagnosticEvents.list.clear();
        when(variables.loadValues("demo")).thenReturn(Map.of());
        when(client.loadDeploymentLabelValues(anyString(), anyString())).thenAnswer(call -> {
            repository.rotate(1);
            return List.of("profile");
        });
        assertThat(service.refresh().status()).isEqualTo("UNAVAILABLE");
        assertThat(diagnosticEvents.list).hasSize(1);
        assertThat(warningText()).contains("stage=SNAPSHOT_PUBLISH", "category=CONTEXT_CHANGED");
        assertThat(service.localSnapshots()).isEmpty();
    }

    @Test
    void warnsForStorageFailureWithoutLeakingException() {
        var broken = mock(GrafanaRenderCatalogRepository.class);
        when(broken.revision(1)).thenThrow(new IllegalStateException("private-storage"));
        service = withDiagnostics(broken);
        assertThat(service.refresh().status()).isEqualTo("UNAVAILABLE");
        assertThat(diagnosticEvents.list).hasSize(1);
        assertThat(warningText()).contains("stage=CATALOG_INITIALIZE", "category=LOCAL_STORAGE_ERROR").doesNotContain("private-storage");
        verifyNoInteractions(client);
        diagnosticEvents.list.clear();
        doReturn("test-revision").when(broken).revision(1);
        when(broken.save(eq(1L), eq("test-revision"), any())).thenThrow(new IllegalStateException("private-save"));
        assertThat(service.refresh().status()).isEqualTo("UNAVAILABLE");
        assertThat(diagnosticEvents.list).hasSize(1);
        assertThat(warningText()).contains("stage=SNAPSHOT_PUBLISH", "category=LOCAL_STORAGE_ERROR").doesNotContain("private-save");
    }

    @Test
    void realHttpFailureKeepsSafeWarningAndConnectionResponse() throws Exception {
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
        var diagnostics = new GrafanaCatalogDiagnosticsService();
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
            String text = warningText();
            assertThat(text).contains("stage=DEPLOYMENT_VALUES", "http=403", "category=HTTP_ERROR");
            String[] secrets = {"private-base", "private-token", "private-uid", "private-title", "private-namespace", "private-response", "forged-log", "Authorization", "http://"};
            assertThat(text).doesNotContain(secrets);
            assertThat(mapper.writeValueAsString(result)).doesNotContain(secrets);
            assertThat(events.list).hasSize(1).allSatisfy(event -> {
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

    @Test
    void resourcesRecoveryPublishesFilteredOptionsThenEmptyWithoutRequestsOnLocalRead() throws Exception {
        var http = useHttpCatalog();
        expectCatalogRead(http);
        expectDeploymentRead(http, false, MockRestResponseCreators.withStatus(HttpStatus.NOT_FOUND));
        expectDeploymentRead(http, true, MockRestResponseCreators.withSuccess(
                "{\"status\":\"success\",\"data\":[\"profile\",\"other\",\"$__all\",\"All\",\"profile\"]}", MediaType.APPLICATION_JSON));
        assertThat(service.refresh().status()).isEqualTo("READY");
        assertThat(service.localSnapshots()).singleElement().satisfies(snapshot -> {
            assertThat(snapshot.stale()).isFalse();
            assertThat(snapshot.deployments()).extracting(o -> o.value()).containsExactly("other", "profile");
        });
        assertThat(diagnosticEvents.list).isEmpty();
        assertThat(service.findPanel("demo", 106)).isPresent();
        when(currentUser.currentUserId()).thenReturn(Optional.of(2L));
        assertThat(service.localSnapshots()).isEmpty();
        when(currentUser.currentUserId()).thenReturn(Optional.of(1L));
        assertThat(diagnosticEvents.list).isEmpty();
        http.verify();
        http.reset();
        expectCatalogRead(http);
        expectDeploymentRead(http, false, MockRestResponseCreators.withStatus(HttpStatus.NOT_FOUND));
        expectDeploymentRead(http, true, MockRestResponseCreators.withSuccess("{\"status\":\"success\",\"data\":[]}", MediaType.APPLICATION_JSON));
        assertThat(service.refresh().status()).isEqualTo("EMPTY");
        assertThat(service.localSnapshots()).singleElement().satisfies(snapshot -> {
            assertThat(snapshot.stale()).isFalse();
            assertThat(snapshot.deployments()).isEmpty();
        });
        assertThat(diagnosticEvents.list).isEmpty();
        http.verify();
    }

    @Test
    void resourcesFailureCountsDashboardOnceForUnavailableAndPartialCatalogs() throws Exception {
        var http = useHttpCatalog();
        for (boolean ordinary : List.of(false, true)) {
            diagnosticEvents.list.clear();
            http.expect(request -> assertThat(request.getURI().getPath()).endsWith("/api/search"))
                    .andRespond(MockRestResponseCreators.withSuccess(ordinary ? "[{\"uid\":\"demo\"},{\"uid\":\"ordinary\"}]" : "[{\"uid\":\"demo\"}]", MediaType.APPLICATION_JSON));
            expectDashboardRead(http);
            expectDeploymentRead(http, false, MockRestResponseCreators.withStatus(HttpStatus.NOT_FOUND));
            expectDeploymentRead(http, true, MockRestResponseCreators.withStatus(HttpStatus.INTERNAL_SERVER_ERROR));
            if (ordinary) {
                when(parser.parse(eq("ordinary"), any())).thenReturn(new GrafanaRenderDashboard("ordinary", "ordinary", "Ordinary",
                        List.of(new GrafanaRenderDashboard.Panel(1, "", "CPU", "", false, "ordinary-v1")), Map.of()));
                http.expect(request -> assertThat(request.getURI().getPath()).endsWith("/api/dashboards/uid/ordinary"))
                        .andRespond(MockRestResponseCreators.withSuccess("{}", MediaType.APPLICATION_JSON));
            }
            assertThat(service.refresh().status()).isEqualTo(ordinary ? "PARTIAL" : "UNAVAILABLE");
            assertThat(diagnosticEvents.list).hasSize(1);
            assertThat(warningText()).contains("stage=DEPLOYMENT_VALUES_RESOURCES", "category=HTTP_ERROR", "http=500");
            assertThat(service.localSnapshots()).hasSize(ordinary ? 1 : 0);
            assertThat(service.findPanel("demo", 106)).isEmpty();
            if (ordinary) {
                assertThat(service.findPanel("ordinary", 1)).isPresent();
            }
            http.verify();
            http.reset();
        }
    }

    @Test
    void resourcesFailuresKeepStaleCatalogAndSafeTerminalDiagnostics() throws Exception {
        var http = useHttpCatalog();
        expectCatalogRead(http);
        expectDeploymentRead(http, false, MockRestResponseCreators.withSuccess("{\"status\":\"success\",\"data\":[\"profile\"]}", MediaType.APPLICATION_JSON));
        assertThat(service.refresh().status()).isEqualTo("READY");
        http.verify();
        http.reset();
        when(client.checkHealth()).thenReturn("ok");
        var diagnostics = new GrafanaCatalogDiagnosticsService();
        var connection = new ConnectionTestService(null, null, client, null, null, null, null, service, diagnostics);
        List<ResponseCreator> failures = List.of(
                MockRestResponseCreators.withStatus(HttpStatus.FORBIDDEN).body("private-response\nforged-log"),
                MockRestResponseCreators.withSuccess("{private-response", MediaType.APPLICATION_JSON),
                request -> { throw new SocketTimeoutException("private-response\nforged-log"); });
        Logger logger = (Logger) LoggerFactory.getLogger(GrafanaCatalogDiagnosticsService.class);
        var events = new ListAppender<ILoggingEvent>();
        events.start();
        logger.addAppender(events);
        try {
            for (int i = 0; i < failures.size(); i++) {
                diagnosticEvents.list.clear();
                expectCatalogRead(http);
                expectDeploymentRead(http, false, MockRestResponseCreators.withStatus(HttpStatus.NOT_FOUND).body("private-proxy-response\nforged-proxy"));
                expectDeploymentRead(http, true, failures.get(i));
                var result = connection.testConnection("grafana");
                assertThat(result.success()).isTrue();
                assertThat(result.message()).isEqualTo("Grafana ответила: ok");
                assertThat(result.catalog().status()).isEqualTo("STALE");
                assertThat(diagnosticEvents.list).hasSize(1);
                assertThat(warningText()).contains("stage=DEPLOYMENT_VALUES_RESOURCES",
                        "http=" + List.of("403", "200", "null").get(i),
                        "category=" + List.of("HTTP_ERROR", "INVALID_RESPONSE", "TRANSPORT_ERROR").get(i));
                assertThat(service.localSnapshots()).singleElement().satisfies(snapshot -> {
                    assertThat(snapshot.stale()).isTrue();
                    assertThat(snapshot.deployments()).extracting(o -> o.value()).containsExactly("profile");
                });
                String[] secrets = {"private-base", "private-token", "demo-source", "private-namespace", "private-response", "private-proxy-response", "forged-log", "forged-proxy", "Authorization", "http://"};
                assertThat(warningText()).doesNotContain(secrets);
                assertThat(mapper.writeValueAsString(result)).doesNotContain(secrets);
                assertThat(events.list).hasSize(1).allSatisfy(event -> {
                    assertThat(event.getFormattedMessage()).doesNotContain(secrets);
                    assertThat(event.getThrowableProxy()).isNull();
                });
                events.list.clear();
                http.verify();
                http.reset();
            }
        } finally {
            logger.detachAppender(events);
            events.stop();
        }
    }

    @Test
    void resourcesSuccessDoesNotPublishAfterNamespaceOrRevisionChanges() throws Exception {
        var http = useHttpCatalog();
        for (boolean rotate : List.of(false, true)) {
            diagnosticEvents.list.clear();
            when(variables.loadValues("demo")).thenReturn(Map.of("namespace", "private-namespace"));
            expectCatalogRead(http);
            expectDeploymentRead(http, false, MockRestResponseCreators.withStatus(HttpStatus.NOT_FOUND));
            expectDeploymentRead(http, true, request -> {
                if (rotate) {
                    repository.rotate(1);
                } else {
                    when(variables.loadValues("demo")).thenReturn(Map.of("namespace", "changed"));
                }
                return MockRestResponseCreators.withSuccess("{\"status\":\"success\",\"data\":[\"profile\"]}", MediaType.APPLICATION_JSON).createResponse(request);
            });
            assertThat(service.refresh().status()).isEqualTo("UNAVAILABLE");
            assertThat(diagnosticEvents.list).hasSize(1);
            assertThat(warningText()).contains("stage=" + (rotate ? "SNAPSHOT_PUBLISH" : "CONTEXT_CHECK"), "category=CONTEXT_CHANGED");
            assertThat(service.localSnapshots()).isEmpty();
            http.verify();
            http.reset();
        }
    }

    @Test
    void resourcesRecoveryKeepsSuccessfulMessageWithoutDiagnosticSuffix() {
        var http = useHttpCatalog();
        expectCatalogRead(http);
        expectDeploymentRead(http, false, MockRestResponseCreators.withStatus(HttpStatus.NOT_FOUND));
        expectDeploymentRead(http, true, MockRestResponseCreators.withSuccess("{\"status\":\"success\",\"data\":[\"profile\"]}", MediaType.APPLICATION_JSON));
        var result = service.refresh();
        assertThat(result.status()).isEqualTo("READY");
        assertThat(result.message()).isEqualTo("Каталог графиков и deployment обновлён.");
        assertThat(diagnosticEvents.list).isEmpty();
        assertThat(service.localSnapshots()).singleElement().satisfies(snapshot ->
                assertThat(snapshot.deployments()).extracting(o -> o.value()).containsExactly("profile"));
        http.verify();
    }

    private MockRestServiceServer useHttpCatalog() {
        var rest = new RestTemplate();
        var http = MockRestServiceServer.createServer(rest);
        var settings = Map.of("base-url", "http://grafana.example/private-base", "api-key", "private-token");
        var transport = new GrafanaClient(rest, new ConfigurationProvider() {
            public Optional<String> findValue(String service, String key) { return Optional.ofNullable(settings.get(key)); }
            public String getRequiredValue(String service, String key) { return settings.get(key); }
        });
        when(variables.loadValues("demo")).thenReturn(Map.of("namespace", "private-namespace"));
        service = new GrafanaRenderCatalogService(transport, parser, repository, currentUser, variables,
                new GrafanaCatalogDiagnosticsService());
        return http;
    }

    private void expectCatalogRead(MockRestServiceServer http) {
        http.expect(request -> assertThat(request.getURI().getPath()).endsWith("/api/search"))
                .andRespond(MockRestResponseCreators.withSuccess("[{\"uid\":\"demo\"}]", MediaType.APPLICATION_JSON));
        expectDashboardRead(http);
    }

    private void expectDashboardRead(MockRestServiceServer http) {
        http.expect(request -> assertThat(request.getURI().getPath()).endsWith("/api/dashboards/uid/demo"))
                .andRespond(MockRestResponseCreators.withSuccess("{}", MediaType.APPLICATION_JSON));
    }

    private void expectDeploymentRead(MockRestServiceServer http, boolean resources, ResponseCreator response) {
        http.expect(request -> {
            assertThat(request.getURI().getPath()).isEqualTo("/private-base/api/datasources/"
                    + (resources ? "uid/demo-source/resources" : "proxy/uid/demo-source") + "/api/v1/label/deployment/values");
            assertThat(request.getURI().getQuery()).contains("private-namespace");
            assertThat(request.getMethod()).isEqualTo(HttpMethod.GET);
            assertThat(request.getHeaders().getFirst("Authorization")).isEqualTo("Bearer private-token");
        }).andRespond(response);
    }

    private GrafanaRenderCatalogService withDiagnostics(GrafanaRenderCatalogRepository source) {
        return new GrafanaRenderCatalogService(client, parser, source, currentUser, variables,
                new GrafanaCatalogDiagnosticsService());
    }

    private String warningText() {
        assertThat(diagnosticEvents.list).isNotEmpty().allSatisfy(event -> {
            assertThat(event.getLevel()).isEqualTo(Level.WARN);
            assertThat(event.getThrowableProxy()).isNull();
        });
        return String.join("\n", diagnosticEvents.list.stream().map(ILoggingEvent::getFormattedMessage).toList());
    }
}
