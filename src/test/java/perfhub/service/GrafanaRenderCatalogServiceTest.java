package perfhub.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import perfhub.client.GrafanaClient;
import perfhub.client.GrafanaRenderCatalogParser;
import perfhub.dto.grafana.GrafanaRenderDashboard;
import perfhub.repository.GrafanaRenderCatalogRepository;
import perfhub.security.CurrentUserProvider;
import perfhub.support.DropAppFixtures;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
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

class GrafanaRenderCatalogServiceTest {
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
}
