package perfhub.service;

import perfhub.model.ConfluenceTemplateChart;
import perfhub.repository.ConfluenceTemplateChartRepository;
import perfhub.repository.ConfluenceTemplateRepository;
import perfhub.repository.GrafanaDashboardPanelRepository;
import perfhub.support.DropAppFixtures;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabase;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ConfluenceTemplateDeploymentTest {
    private EmbeddedDatabase database;
    private ConfluenceTemplateSettingsService service;
    private GrafanaRenderCatalogService catalog;
    private GrafanaDashboardSettingsService dashboards;

    @BeforeEach
    void setUp() {
        database = new EmbeddedDatabaseBuilder().generateUniqueName(true).setType(EmbeddedDatabaseType.H2).addScript("schema.sql").build();
        var jdbc = new JdbcTemplate(database);
        catalog = mock(GrafanaRenderCatalogService.class);
        var snapshot = DropAppFixtures.snapshot();
        when(catalog.findPanel(eq("demo"),anyInt())).thenAnswer(call -> snapshot.dashboard().panels().stream()
                .filter(panel -> panel.id() == (int)call.getArgument(1)).findFirst()
                .map(panel -> new GrafanaRenderCatalogService.CatalogPanel(snapshot,panel)));
        dashboards = mock(GrafanaDashboardSettingsService.class);
        when(dashboards.listDashboards()).thenReturn(List.of());
        service = new ConfluenceTemplateSettingsService(new ConfluenceTemplateRepository(jdbc),new ConfluenceTemplateChartRepository(jdbc),
                mock(GrafanaDashboardPanelRepository.class),dashboards,null,catalog);
    }

    @AfterEach
    void close() { database.shutdown(); }

    @Test
    void jdbcRoundTripKeepsThreeRowsOrderAndReissuesIdsOnSave() {
        var command = command(List.of(chart(106,"profile"),chart(106,"other"),chart(78,"profile")));
        var first = service.createTemplate(command);
        assertThat(first.charts()).extracting(ConfluenceTemplateChart::deployment).containsExactly("profile","other","profile");
        assertThat(first.charts()).extracting(ConfluenceTemplateChart::sortOrder).containsExactly(0,1,2);
        var second = service.updateTemplate(first.id(),command(List.of(chart(78,"profile"),chart(106,"profile"),chart(106,"other"))));
        assertThat(service.getActiveTemplate(first.id()).charts()).extracting(ConfluenceTemplateChart::panelId)
                .containsExactly(78,106,106);
        assertThat(service.listTemplates().get(0).charts()).extracting(ConfluenceTemplateChart::deployment)
                .containsExactly("profile","profile","other");
        assertThat(second.charts()).extracting(ConfluenceTemplateChart::id).doesNotContainAnyElementsOf(first.charts().stream().map(ConfluenceTemplateChart::id).toList());
    }

    @Test
    void missingAllListsAndUnknownScopeRejectedEvenForOfflineImport() {
        for (String invalid : new String[]{null,"","All","$__all","profile,other","[\"profile\"]"}) {
            assertThatThrownBy(() -> service.createTemplate(command(List.of(chart(106,invalid))),false))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        var unknown = new ConfluenceTemplateSettingsService.TemplateChartCommand("CPU","demo",106,"UNKNOWN","profile");
        assertThatThrownBy(() -> service.createTemplate(command(List.of(unknown)),false)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void missingDeploymentOnRecognizedLegacyPanelIsNotFilledFromCurrent() {
        var legacy = new ConfluenceTemplateSettingsService.TemplateChartCommand("CPU","demo",106);
        assertThatThrownBy(() -> service.createTemplate(command(List.of(legacy)),false))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("конкретный deployment");
    }

    @Test
    void importAllowsExplicitValueWithoutCatalogAndOrdinaryLegacyShape() {
        when(catalog.findPanel(anyString(),anyInt())).thenReturn(Optional.empty());
        var saved = service.createTemplate(command(List.of(chart(106,"historical"))),false);
        assertThat(saved.charts().get(0).deployment()).isEqualTo("historical");
        var ordinary = new ConfluenceTemplateSettingsService.TemplateChartCommand("Legacy","ordinary",7);
        var updated = service.updateTemplate(saved.id(),command(List.of(ordinary)),false);
        assertThat(updated.charts().get(0).renderScope()).isNull();
    }

    @Test
    void disappearedExistingDeploymentCanBeRetainedButNotAddedAsNew() {
        var saved = service.createTemplate(command(List.of(chart(106,"profile"))));
        var original = DropAppFixtures.snapshot();
        var empty = new perfhub.dto.grafana.GrafanaCatalogSnapshot(original.dashboard(),original.datasource(),original.namespace(),
                original.variables(),List.of(),original.updatedAt(),false);
        when(catalog.findPanel("demo",106)).thenReturn(Optional.of(new GrafanaRenderCatalogService.CatalogPanel(empty,empty.dashboard().panels().get(0))));
        assertThat(service.updateTemplate(saved.id(),command(List.of(chart(106,"profile")))).charts()).hasSize(1);
        assertThatThrownBy(() -> service.updateTemplate(saved.id(),command(List.of(chart(106,"other")))))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("отсутствует в каталоге");
    }

    private ConfluenceTemplateSettingsService.TemplateChartCommand chart(int panel, String deployment) {
        return new ConfluenceTemplateSettingsService.TemplateChartCommand("Chart","demo",panel,DropAppFixtures.SCOPE,deployment);
    }

    @Test
    void additiveDdlPreservesLegacyRowAndCanRunRepeatedly() throws Exception {
        when(catalog.findPanel(anyString(),anyInt())).thenReturn(Optional.empty());
        var ordinary = new ConfluenceTemplateSettingsService.TemplateChartCommand("Legacy","ordinary",7);
        var saved = service.createTemplate(command(List.of(ordinary)),false);
        var jdbc = new JdbcTemplate(database);
        jdbc.execute("ALTER TABLE confluence_report_template_charts DROP COLUMN render_scope");
        jdbc.execute("ALTER TABLE confluence_report_template_charts DROP COLUMN deployment");
        for (int i = 0; i < 2; i++) {
            try (var connection = database.getConnection()) {
                org.springframework.jdbc.datasource.init.ScriptUtils.executeSqlScript(connection,new org.springframework.core.io.ClassPathResource("schema.sql"));
            }
        }
        var restored = service.getActiveTemplate(saved.id()).charts().get(0);
        assertThat(restored.id()).isEqualTo(saved.charts().get(0).id());
        assertThat(restored.title()).isEqualTo("Legacy");
        assertThat(restored.renderScope()).isNull();
        assertThat(restored.deployment()).isNull();
    }

    @Test
    void panelOptionsKeepOrdinaryLookupAndAddOnlyScopedSnapshotPanels() {
        var original = DropAppFixtures.snapshot();
        var panels = new java.util.ArrayList<>(original.dashboard().panels());
        panels.add(new perfhub.dto.grafana.GrafanaRenderDashboard.Panel(42,"panel-42","New ordinary","",false,"ordinary-v1"));
        var dashboard = new perfhub.dto.grafana.GrafanaRenderDashboard("demo","dropapp","DropApp",panels,original.dashboard().variables());
        var snapshot = new perfhub.dto.grafana.GrafanaCatalogSnapshot(dashboard,original.datasource(),original.namespace(),
                original.variables(),original.deployments(),original.updatedAt(),false);
        when(catalog.localSnapshots()).thenReturn(List.of(snapshot));
        assertThat(service.listPanelOptions()).extracting(option -> option.panelId()).containsExactly(106,78);
        assertThat(service.listPanelOptions()).allSatisfy(option -> {
            assertThat(option.deploymentApplicability()).isEqualTo("SUPPORTED");
            assertThat(option.deployments()).isEqualTo(original.deployments());
            assertThat(option.catalogStatus()).isEqualTo("READY");
        });
        when(dashboards.listDashboards()).thenReturn(List.of(new perfhub.dto.settings.GrafanaDashboardDetailsDto(
                "demo","dropapp","DropApp",true,List.of(new perfhub.dto.settings.GrafanaDashboardPanelDto(42,"Legacy ordinary",true,null,null,false)),List.of())));
        assertThat(service.listPanelOptions()).hasSize(3);
        var ordinary = service.listPanelOptions().stream().filter(option -> option.panelId() == 42).findFirst().orElseThrow();
        assertThat(ordinary.renderScope()).isNull();
        assertThat(ordinary.deploymentApplicability()).isEqualTo("NOT_APPLICABLE");
        assertThat(ordinary.code()).isEqualTo("DropApp -> Legacy ordinary");
        assertThat(ordinary.deployments()).isEmpty();
        assertThat(ordinary.catalogUpdatedAt()).isEqualTo(snapshot.updatedAt());

        when(catalog.localSnapshots()).thenReturn(List.of(new perfhub.dto.grafana.GrafanaCatalogSnapshot(
                dashboard, original.datasource(), original.namespace(), original.variables(), original.deployments(),
                original.updatedAt(), true)));
        assertThat(service.listPanelOptions()).allSatisfy(option -> assertThat(option.catalogStatus()).isEqualTo("STALE"));
        assertThat(service.listPanelOptions()).extracting(option -> option.deploymentApplicability())
                .containsExactly("SUPPORTED", "NOT_APPLICABLE", "SUPPORTED");
    }

    @Test
    void legacyLookupWithoutMatchingSnapshotHasUnknownApplicability() {
        when(dashboards.listDashboards()).thenReturn(List.of(new perfhub.dto.settings.GrafanaDashboardDetailsDto(
                "legacy", "legacy", "Legacy", true,
                List.of(new perfhub.dto.settings.GrafanaDashboardPanelDto(7,"Ordinary",true,null,null,false)), List.of())));
        when(catalog.localSnapshots()).thenReturn(List.of(DropAppFixtures.snapshot()));
        var ordinary = service.listPanelOptions().stream().filter(option -> option.panelId() == 7).findFirst().orElseThrow();
        assertThat(ordinary.deploymentApplicability()).isEqualTo("UNKNOWN");
        assertThat(ordinary.catalogStatus()).isNull();
        assertThat(ordinary.catalogUpdatedAt()).isNull();
        assertThat(ordinary.renderScope()).isNull();
    }

    private ConfluenceTemplateSettingsService.TemplateSaveCommand command(List<ConfluenceTemplateSettingsService.TemplateChartCommand> charts) {
        return new ConfluenceTemplateSettingsService.TemplateSaveCommand("max_template","Test","Test","TEST","max","","",charts);
    }
}
