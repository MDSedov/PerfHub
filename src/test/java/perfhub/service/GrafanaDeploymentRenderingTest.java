package perfhub.service;

import perfhub.client.GrafanaClient;
import perfhub.dto.grafana.GrafanaRenderPanelRangeRequest;
import perfhub.dto.grafana.GrafanaRenderRangeRequest;
import perfhub.dto.grafana.GrafanaRenderOptions;
import perfhub.dto.grafana.GrafanaRenderRequest;
import perfhub.exception.GrafanaClientException;
import perfhub.exception.GrafanaRenderingException;
import perfhub.model.LoadTest;
import perfhub.model.LoadTestType;
import perfhub.repository.GrafanaDashboardPanelRepository;
import perfhub.repository.GrafanaDashboardRepository;
import perfhub.repository.GrafanaRenderedPanelCacheRepository;
import perfhub.repository.LoadTestRepository;
import perfhub.repository.TestTypeRepository;
import perfhub.support.DropAppFixtures;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabase;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;

class GrafanaDeploymentRenderingTest {
    @TempDir Path cache;
    private EmbeddedDatabase database;
    private GrafanaClient client;
    private GrafanaService service;
    private GrafanaRenderCatalogService catalog;
    private ConfluenceTemplateSettingsService templates;
    private LoadTest test;
    private final Instant start = Instant.parse("2026-01-01T00:00:00Z");
    private final Instant end = start.plusSeconds(3600);

    @BeforeEach
    void setUp() {
        database = new EmbeddedDatabaseBuilder().generateUniqueName(true).setType(EmbeddedDatabaseType.H2).addScript("schema.sql").build();
        var jdbc = new JdbcTemplate(database);
        var tests = new LoadTestRepository(jdbc,new TestTypeRepository(jdbc));
        test = tests.saveOrUpdate(start,end,LoadTestType.MAX);
        client = mock(GrafanaClient.class);
        when(client.loadRenderOptions()).thenReturn(new GrafanaRenderOptions(1000,1000,"Europe/Moscow"));
        when(client.renderDeploymentPanel(anyString(),anyString(),anyString(),any(),any(),anyMap(),any())).thenReturn(DropAppFixtures.PNG);
        templates = mock(ConfluenceTemplateSettingsService.class);
        var template = DropAppFixtures.template(List.of(DropAppFixtures.chart(1,106,"profile",0),
                DropAppFixtures.chart(2,106,"other",1),DropAppFixtures.chart(3,78,"profile",2)));
        when(templates.getActiveTemplate(1)).thenReturn(template);
        when(templates.resolveDefaultTemplate("max")).thenReturn(template);
        catalog = mock(GrafanaRenderCatalogService.class);
        when(catalog.isCurrent(any())).thenReturn(true);
        var snapshot = DropAppFixtures.snapshot();
        when(catalog.findPanel(eq("demo"),anyInt())).thenAnswer(call -> snapshot.dashboard().panels().stream()
                .filter(panel -> panel.id() == (int)call.getArgument(1)).findFirst()
                .map(panel -> new GrafanaRenderCatalogService.CatalogPanel(snapshot,panel)));
        when(catalog.values(any())).thenReturn(Map.of("namespace","demo","datasource","demo-source","pod","single","container","single"));
        when(catalog.cacheContext(any(),anyString(),anyString())).thenAnswer(call -> "source-v1:" + call.getArgument(1));
        service = new GrafanaService(client,new GrafanaRenderCache(),tests,mock(GrafanaDashboardRepository.class),
                mock(GrafanaDashboardPanelRepository.class),mock(GrafanaVariableService.class),templates,
                new GrafanaRenderedPanelCacheService(new GrafanaRenderedPanelCacheRepository(jdbc),cache.toString()),catalog);
    }

    @AfterEach
    void close() { database.shutdown(); }

    @Test
    void onePngPerRowUsesScopedReferenceAndAllWithoutMutatingGlobals() {
        var result = service.renderTemplatePanelsByRange(new GrafanaRenderRangeRequest(start.toString(),end.toString(),1));
        assertThat(result).extracting(panel -> panel.title()).containsExactly("CPU [deployment: profile]","CPU [deployment: other]","RAM [deployment: profile]");
        assertThat(result).extracting(panel -> panel.templateChartId()).containsExactly(1L,2L,3L);
        verify(client).renderDeploymentPanel(eq("demo"),eq("dropapp"),eq("profile$panel-106"),eq(start),eq(end),
                argThat(vars -> vars.get("deployment").equals("profile") && vars.get("pod").equals("$__all") && vars.get("container").equals("$__all")),any());
        verify(client,never()).discoverDashboards();
        verify(client,never()).renderPanel(anyString(),anyString(),anyInt(),any(),any(),anyMap(),any());
    }

    @Test
    void rowIdDisambiguatesAndForeignOrLegacyAmbiguousRequestFails() {
        assertThatThrownBy(() -> service.renderPanelByRange(request(106,null))).isInstanceOf(GrafanaRenderingException.class);
        assertThat(service.renderPanelByRange(request(106,2L)).deployment()).isEqualTo("other");
        assertThatThrownBy(() -> service.renderPanelByRange(request(78,2L))).isInstanceOf(GrafanaRenderingException.class);
        assertThatThrownBy(() -> service.renderPanelByRange(request(106,999L))).isInstanceOf(GrafanaRenderingException.class);
        assertThat(service.renderPanelByRange(request(78,null)).deployment()).isEqualTo("profile");
    }

    @Test
    void realFileAndH2CacheRespectDeploymentSizeSourceAndCacheOnly() {
        when(client.loadRenderOptions()).thenReturn(new GrafanaRenderOptions(1000,500,"Europe/Moscow"));
        service.renderPanels(new GrafanaRenderRequest(test.id(),false,false));
        clearInvocations(client);
        var cached = service.renderPanels(new GrafanaRenderRequest(test.id(),false,true));
        assertThat(cached.dashboards().get(0).panels()).hasSize(3);
        verify(client,never()).renderDeploymentPanel(anyString(),anyString(),anyString(),any(),any(),anyMap(),any());
        when(client.loadRenderOptions()).thenReturn(new GrafanaRenderOptions(1000,1000,"Europe/Moscow"));
        assertThat(service.renderPanels(new GrafanaRenderRequest(test.id(),false,true)).dashboards()).isEmpty();
        when(client.loadRenderOptions()).thenReturn(new GrafanaRenderOptions(1000,500,"Europe/Moscow"));
        when(catalog.cacheContext(any(),anyString(),anyString())).thenAnswer(call -> "source-v2:" + call.getArgument(1));
        assertThat(service.renderPanels(new GrafanaRenderRequest(test.id(),false,true)).dashboards()).isEmpty();
        service.renderPanels(new GrafanaRenderRequest(test.id(),true,false));
        verify(client,times(3)).renderDeploymentPanel(anyString(),anyString(),anyString(),any(),any(),anyMap(),any());
    }

    @Test
    void sameContextRowsReuseBytesButKeepIndependentTitles() {
        var template = DropAppFixtures.template(List.of(DropAppFixtures.chart(1,106,"profile",0),DropAppFixtures.chart(2,106,"profile",1)));
        when(templates.resolveDefaultTemplate("max")).thenReturn(template);
        var result = service.renderPanels(new GrafanaRenderRequest(test.id(),false,false));
        assertThat(result.dashboards().get(0).panels()).hasSize(2);
        verify(client,times(1)).renderDeploymentPanel(anyString(),anyString(),anyString(),any(),any(),anyMap(),any());
    }

    @Test
    void emptyFailureDoesNotCacheAndBulkStopsAtFirstError() {
        when(client.renderDeploymentPanel(anyString(),anyString(),eq("profile$panel-106"),any(),any(),anyMap(),any())).thenReturn(new byte[0]);
        assertThatThrownBy(() -> service.renderPanels(new GrafanaRenderRequest(test.id(),false,false))).isInstanceOf(GrafanaClientException.class);
        verify(client,never()).renderDeploymentPanel(anyString(),anyString(),eq("other$panel-106"),any(),any(),anyMap(),any());
        assertThat(service.renderPanels(new GrafanaRenderRequest(test.id(),false,true)).dashboards()).isEmpty();
    }

    @Test
    void legacyScopedRowNeedsExplicitDeploymentAndGlobalsAreNotUsedAsFallback() {
        when(templates.getActiveTemplate(1)).thenReturn(DropAppFixtures.template(List.of(DropAppFixtures.chart(1,106,null,0))));
        assertThatThrownBy(() -> service.renderPanelByRange(request(106,1L))).isInstanceOf(GrafanaRenderingException.class)
                .hasMessageContaining("конкретный deployment");
    }

    private GrafanaRenderPanelRangeRequest request(int panel, Long row) {
        return new GrafanaRenderPanelRangeRequest(start.toString(),end.toString(),1,"demo",panel,row);
    }

    @Test
    void mvcKeepsRangeContractAndErrorStatuses() throws Exception {
        var mvc = org.springframework.test.web.servlet.setup.MockMvcBuilders
                .standaloneSetup(new perfhub.controller.GrafanaController(service))
                .setControllerAdvice(new perfhub.controller.advice.GrafanaExceptionHandler()).build();
        String body = "{\"from\":\"2026-01-01T00:00:00Z\",\"to\":\"2026-01-01T01:00:00Z\",\"templateId\":1,\"dashboardUid\":\"demo\",\"panelId\":106,\"templateChartId\":2}";
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/grafana/panels/range/panel")
                .contentType("application/json").content(body))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.deployment").value("other"));
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/grafana/panels/range/panel")
                .contentType("application/json").content(body.replace("\"templateChartId\":2","\"templateChartId\":999")))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isBadRequest())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.message").isString());
        when(client.renderDeploymentPanel(anyString(),anyString(),anyString(),any(),any(),anyMap(),any()))
                .thenThrow(new GrafanaClientException("synthetic",null));
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/grafana/panels/range/panel")
                .contentType("application/json").content(body))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isBadGateway());
    }

    @Test
    void changingSourceDuringRequestDoesNotReturnOrCacheImage() {
        when(catalog.isCurrent(any())).thenReturn(true,false);
        assertThatThrownBy(() -> service.renderPanels(new GrafanaRenderRequest(test.id(),false,false)))
                .isInstanceOf(GrafanaRenderingException.class).hasMessageContaining("Контекст Grafana изменился");
    }
}
