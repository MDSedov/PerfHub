package perfhub.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import perfhub.client.GrafanaClient;
import perfhub.model.ConfluenceTemplate;
import perfhub.model.ConfluenceTemplateChart;
import perfhub.dto.grafana.GrafanaPanelMetadata;
import perfhub.dto.grafana.GrafanaPanelSelection;
import perfhub.dto.grafana.GrafanaRenderOptions;
import perfhub.dto.grafana.GrafanaRenderRequest;
import perfhub.dto.grafana.GrafanaRenderRangeRequest;
import perfhub.dto.grafana.GrafanaRenderResponse;
import perfhub.dto.grafana.GrafanaRenderedPanel;
import perfhub.exception.GrafanaClientException;
import perfhub.exception.GrafanaInvalidTimeRangeException;
import perfhub.exception.GrafanaRenderingException;
import perfhub.model.GrafanaDashboard;
import perfhub.model.LoadTest;
import perfhub.model.LoadTestType;
import perfhub.repository.GrafanaDashboardPanelRepository;
import perfhub.repository.GrafanaDashboardRepository;
import perfhub.repository.GrafanaDashboardVariableRepository;
import perfhub.repository.LoadTestRepository;
import perfhub.repository.TestTypeRepository;
import perfhub.repository.model.GrafanaDashboardPanelRecord;
import perfhub.repository.model.GrafanaDashboardVariableRecord;
import perfhub.repository.model.GrafanaVariableOptionRecord;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.client.RestTemplate;

class GrafanaServiceTest {

    private StubGrafanaClient grafanaClient;
    private GrafanaRenderCache grafanaRenderCache;
    private StubLoadTestRepository loadTestRepository;
    private StubGrafanaDashboardRepository grafanaDashboardRepository;
    private StubGrafanaDashboardPanelRepository grafanaDashboardPanelRepository;
    private StubGrafanaDashboardVariableRepository grafanaDashboardVariableRepository;
    private StubConfluenceTemplateSettingsService confluenceTemplateSettingsService;
    private GrafanaService grafanaService;

    @BeforeEach
    void setUp() {
        grafanaClient = new StubGrafanaClient();
        grafanaRenderCache = new GrafanaRenderCache();
        loadTestRepository = new StubLoadTestRepository();
        grafanaDashboardRepository = new StubGrafanaDashboardRepository();
        grafanaDashboardPanelRepository = new StubGrafanaDashboardPanelRepository();
        grafanaDashboardVariableRepository = new StubGrafanaDashboardVariableRepository();
        confluenceTemplateSettingsService = new StubConfluenceTemplateSettingsService();
        grafanaService = createGrafanaService(null);
    }

    private GrafanaService createGrafanaService(GrafanaRenderedPanelCacheService renderedPanelCacheService) {
        return new GrafanaService(
                grafanaClient,
                grafanaRenderCache,
                loadTestRepository,
                grafanaDashboardRepository,
                grafanaDashboardPanelRepository,
                new GrafanaVariableService(grafanaDashboardVariableRepository),
                confluenceTemplateSettingsService,
                renderedPanelCacheService);
    }

    @Test
    void renderPanelsReturnsEncodedImages() {
        UUID testUuid = UUID.randomUUID();
        Instant from = Instant.parse("2024-01-01T00:00:00Z");
        Instant to = Instant.parse("2024-01-01T01:00:00Z");

        loadTestRepository.put(new LoadTest(testUuid, from, to, LoadTestType.MAX, null));
        confluenceTemplateSettingsService.registerDefaultTemplate(
                LoadTestType.MAX,
                new ConfluenceTemplate(
                        1,
                        "max",
                        "MAX report",
                        "MAX report",
                        "<p>Body</p>",
                        List.of(
                                new ConfluenceTemplateChart(1L, 1, "Panel 1", "uid", 1, 0),
                                new ConfluenceTemplateChart(2L, 1, "Panel 2", "uid", 2, 1))));
        grafanaDashboardRepository.setDashboards(
                List.of(new GrafanaDashboard("uid", "slug", List.of(1, 2), true)));
        grafanaClient.setRenderedPanel(1, new byte[] {1, 2});
        grafanaClient.setRenderedPanel(2, new byte[] {3});
        grafanaClient.setPanelMetadata(1, new GrafanaPanelMetadata("Panel 1", "First panel"));
        grafanaClient.setPanelMetadata(2, new GrafanaPanelMetadata("Panel 2", null));

        GrafanaRenderResponse response = grafanaService.renderPanels(new GrafanaRenderRequest(testUuid));

        assertThat(response.from()).isEqualTo(from.toString());
        assertThat(response.to()).isEqualTo(to.toString());
        assertThat(response.dashboards()).hasSize(1);
        assertThat(response.dashboards().get(0).uid()).isEqualTo("uid");
        assertThat(response.dashboards().get(0).slug()).isEqualTo("slug");
        assertThat(response.dashboards().get(0).panels()).hasSize(2);
        assertThat(response.dashboards().get(0).panels().get(0).panelId()).isEqualTo(1);
        assertThat(response.dashboards().get(0).panels().get(0).title()).isEqualTo("Panel 1");
        assertThat(response.dashboards().get(0).panels().get(0).description()).isEqualTo("First panel");
        assertThat(response.dashboards().get(0).panels().get(0).imageBase64()).isEqualTo("AQI=");
        assertThat(response.dashboards().get(0).panels().get(1).panelId()).isEqualTo(2);
        assertThat(response.dashboards().get(0).panels().get(1).title()).isEqualTo("Panel 2");
        assertThat(response.dashboards().get(0).panels().get(1).description()).isEmpty();
        assertThat(response.dashboards().get(0).panels().get(1).imageBase64()).isEqualTo("Aw==");

        assertThat(grafanaClient.renderCalls()).containsExactly(1, 2);
        assertThat(grafanaRenderCache.getLatest()).contains(response);
    }

    @Test
    void renderPanelsThrowsWhenTestNotFound() {
        UUID testUuid = UUID.randomUUID();

        assertThatThrownBy(() -> grafanaService.renderPanels(new GrafanaRenderRequest(testUuid)))
                .isInstanceOf(GrafanaRenderingException.class)
                .hasMessageContaining("Test with UUID " + testUuid + " not found");
    }

    @Test
    void renderPanelsThrowsWhenTemplateHasNoCharts() {
        UUID testUuid = UUID.randomUUID();
        Instant from = Instant.parse("2024-01-01T00:00:00Z");
        Instant to = Instant.parse("2024-01-01T01:00:00Z");
        loadTestRepository.put(new LoadTest(testUuid, from, to, LoadTestType.MAX, null));
        confluenceTemplateSettingsService.registerDefaultTemplate(
                LoadTestType.MAX,
                new ConfluenceTemplate(1, "max", "MAX report", "MAX report", "<p>Body</p>", List.of()));

        assertThatThrownBy(() -> grafanaService.renderPanels(new GrafanaRenderRequest(testUuid)))
                .isInstanceOf(GrafanaRenderingException.class)
                .hasMessage("Для шаблона отчета 'MAX report' не настроены графики");
    }

    @Test
    void renderPanelsThrowsWhenTimeRangeInvalid() {
        UUID testUuid = UUID.randomUUID();
        Instant from = Instant.parse("2024-01-01T02:00:00Z");
        Instant to = Instant.parse("2024-01-01T01:00:00Z");

        loadTestRepository.put(new LoadTest(testUuid, from, to, LoadTestType.UNKNOWN, null));
        grafanaDashboardRepository.setDashboards(List.of(new GrafanaDashboard("uid", "slug", List.of(1), true)));

        assertThatThrownBy(() -> grafanaService.renderPanels(new GrafanaRenderRequest(testUuid)))
                .isInstanceOf(GrafanaInvalidTimeRangeException.class)
                .hasMessage("End time must be after start time");
    }

    @Test
    void renderPanelsThrowsWhenTemplateNotConfiguredForTestType() {
        UUID testUuid = UUID.randomUUID();
        Instant from = Instant.parse("2024-01-01T00:00:00Z");
        Instant to = Instant.parse("2024-01-01T01:00:00Z");

        loadTestRepository.put(new LoadTest(testUuid, from, to, LoadTestType.PSU, null));

        assertThatThrownBy(() -> grafanaService.renderPanels(new GrafanaRenderRequest(testUuid)))
                .isInstanceOf(GrafanaRenderingException.class)
                .hasMessage("Confluence report template is not configured for test type psu");
    }

    @Test
    void renderPanelsFallsBackToCachedTitleWhenMetadataRequestFails() {
        UUID testUuid = UUID.randomUUID();
        Instant from = Instant.parse("2024-01-01T00:00:00Z");
        Instant to = Instant.parse("2024-01-01T01:00:00Z");

        loadTestRepository.put(new LoadTest(testUuid, from, to, LoadTestType.MAX, null));
        confluenceTemplateSettingsService.registerDefaultTemplate(
                LoadTestType.MAX,
                new ConfluenceTemplate(
                        1,
                        "max",
                        "MAX report",
                        "MAX report",
                        "<p>Body</p>",
                        List.of(new ConfluenceTemplateChart(1L, 1, "", "uid", 7, 0))));
        grafanaDashboardRepository.setDashboards(
                List.of(new GrafanaDashboard("uid", "slug", List.of(7), true)));
        grafanaDashboardPanelRepository.register(
                new GrafanaDashboardPanelRecord("uid", "slug", "Dashboard", 7, "Cached title", "up", null, false));
        grafanaClient.setRenderedPanel(7, new byte[] {9});
        grafanaClient.failMetadataForPanel(7);

        GrafanaRenderResponse response = grafanaService.renderPanels(new GrafanaRenderRequest(testUuid));

        assertThat(response.dashboards()).hasSize(1);
        assertThat(response.dashboards().get(0).panels()).hasSize(1);
        assertThat(response.dashboards().get(0).panels().get(0).title()).isEqualTo("Cached title");
        assertThat(response.dashboards().get(0).panels().get(0).description()).isEmpty();
        assertThat(response.dashboards().get(0).panels().get(0).imageBase64()).isEqualTo("CQ==");
    }

    @Test
    void renderPanelsPassesConfiguredVariablesToGrafanaRenderer() {
        UUID testUuid = UUID.randomUUID();
        Instant from = Instant.parse("2024-01-01T00:00:00Z");
        Instant to = Instant.parse("2024-01-01T01:00:00Z");

        loadTestRepository.put(new LoadTest(testUuid, from, to, LoadTestType.MAX, null));
        confluenceTemplateSettingsService.registerDefaultTemplate(
                LoadTestType.MAX,
                new ConfluenceTemplate(
                        1,
                        "max",
                        "MAX report",
                        "MAX report",
                        "<p>Body</p>",
                        List.of(new ConfluenceTemplateChart(1L, 1, "Panel 10", "uid", 10, 0))));
        grafanaDashboardRepository.setDashboards(
                List.of(new GrafanaDashboard("uid", "slug", List.of(10), true)));
        grafanaDashboardVariableRepository.setVariables(
                "uid",
                List.of(new GrafanaDashboardVariableRecord(
                        "uid",
                        "service",
                        "service",
                        "query",
                        "backend",
                        "frontend",
                        List.of(new GrafanaVariableOptionRecord("frontend", "frontend", true)))));
        grafanaClient.setRenderedPanel(10, new byte[] {5});
        grafanaClient.setPanelMetadata(10, new GrafanaPanelMetadata("Panel 10", ""));

        grafanaService.renderPanels(new GrafanaRenderRequest(testUuid));

        assertThat(grafanaClient.renderVariables(10)).containsEntry("service", "frontend");
    }

    @Test
    void renderPanelsRetriesWithRefreshedDashboardSlugWhenCachedSlugIsStale() {
        UUID testUuid = UUID.randomUUID();
        Instant from = Instant.parse("2024-01-01T00:00:00Z");
        Instant to = Instant.parse("2024-01-01T01:00:00Z");

        loadTestRepository.put(new LoadTest(testUuid, from, to, LoadTestType.MAX, null));
        confluenceTemplateSettingsService.registerDefaultTemplate(
                LoadTestType.MAX,
                new ConfluenceTemplate(
                        1,
                        "max",
                        "MAX report",
                        "MAX report",
                        "<p>Body</p>",
                        List.of(new ConfluenceTemplateChart(1L, 1, "Panel 21", "uid", 21, 0))));
        grafanaDashboardRepository.setDashboards(
                List.of(new GrafanaDashboard("uid", "old-slug", List.of(21), true)));
        grafanaClient.setCurrentSlug("uid", "new-slug");
        grafanaClient.setRenderedPanel(21, new byte[] {4});
        grafanaClient.setPanelMetadata(21, new GrafanaPanelMetadata("Panel 21", ""));

        GrafanaRenderResponse response = grafanaService.renderPanels(new GrafanaRenderRequest(testUuid));

        assertThat(response.dashboards()).hasSize(1);
        assertThat(response.dashboards().get(0).slug()).isEqualTo("new-slug");
        assertThat(grafanaDashboardRepository.findByUid("uid")).hasValueSatisfying(dashboard ->
                assertThat(dashboard.slug()).isEqualTo("new-slug"));
        assertThat(grafanaClient.renderSlugs(21)).containsExactly("old-slug", "new-slug");
    }

    @Test
    void renderPanelsUsesPersistentCacheWhenAvailable() {
        StubRenderedPanelCacheService panelCacheService = new StubRenderedPanelCacheService();
        grafanaService = createGrafanaService(panelCacheService);

        UUID testUuid = UUID.randomUUID();
        Instant from = Instant.parse("2024-01-01T00:00:00Z");
        Instant to = Instant.parse("2024-01-01T01:00:00Z");
        byte[] imageBytes = new byte[] {10, 11};

        loadTestRepository.put(new LoadTest(testUuid, from, to, LoadTestType.MAX, null));
        confluenceTemplateSettingsService.registerDefaultTemplate(
                LoadTestType.MAX,
                new ConfluenceTemplate(
                        1,
                        "max",
                        "MAX report",
                        "MAX report",
                        "<p>Body</p>",
                        List.of(new ConfluenceTemplateChart(1L, 1, "Template title", "uid", 31, 0))));
        grafanaDashboardRepository.setDashboards(
                List.of(new GrafanaDashboard("uid", "slug", List.of(31), true)));
        panelCacheService.put(
                testUuid,
                1,
                "uid",
                31,
                from,
                to,
                Map.of(),
                new GrafanaRenderOptions(1200, 600, "Europe/Moscow"),
                new GrafanaRenderedPanelCacheService.CachedPanelImage(
                        "slug",
                        "Cached title",
                        "Cached description",
                        imageBytes));

        GrafanaRenderResponse response = grafanaService.renderPanels(new GrafanaRenderRequest(testUuid));

        assertThat(response.dashboards()).hasSize(1);
        assertThat(response.dashboards().get(0).panels()).hasSize(1);
        GrafanaRenderedPanel panel = response.dashboards().get(0).panels().get(0);
        assertThat(panel.title()).isEqualTo("Template title");
        assertThat(panel.description()).isEqualTo("Cached description");
        assertThat(panel.imageBase64()).isEqualTo("Cgs=");
        assertThat(grafanaClient.renderCalls()).isEmpty();
        assertThat(panelCacheService.storeCalls()).isEmpty();
    }

    @Test
    void renderPanelsStoresPersistentCacheAfterRendering() {
        StubRenderedPanelCacheService panelCacheService = new StubRenderedPanelCacheService();
        grafanaService = createGrafanaService(panelCacheService);

        UUID testUuid = UUID.randomUUID();
        Instant from = Instant.parse("2024-01-01T00:00:00Z");
        Instant to = Instant.parse("2024-01-01T01:00:00Z");
        byte[] imageBytes = new byte[] {12};

        loadTestRepository.put(new LoadTest(testUuid, from, to, LoadTestType.MAX, null));
        confluenceTemplateSettingsService.registerDefaultTemplate(
                LoadTestType.MAX,
                new ConfluenceTemplate(
                        1,
                        "max",
                        "MAX report",
                        "MAX report",
                        "<p>Body</p>",
                        List.of(new ConfluenceTemplateChart(1L, 1, "Panel 32", "uid", 32, 0))));
        grafanaDashboardRepository.setDashboards(
                List.of(new GrafanaDashboard("uid", "slug", List.of(32), true)));
        grafanaClient.setRenderedPanel(32, imageBytes);
        grafanaClient.setPanelMetadata(32, new GrafanaPanelMetadata("Panel 32", "Rendered description"));

        grafanaService.renderPanels(new GrafanaRenderRequest(testUuid));

        assertThat(grafanaClient.renderCalls()).containsExactly(32);
        assertThat(panelCacheService.storeCalls()).hasSize(1);
        StubRenderedPanelCacheService.StoreCall storeCall = panelCacheService.storeCalls().get(0);
        assertThat(storeCall.testUuid()).isEqualTo(testUuid);
        assertThat(storeCall.templateId()).isEqualTo(1);
        assertThat(storeCall.dashboardUid()).isEqualTo("uid");
        assertThat(storeCall.panelId()).isEqualTo(32);
        assertThat(storeCall.title()).isEqualTo("Panel 32");
        assertThat(storeCall.description()).isEqualTo("Rendered description");
        assertThat(storeCall.imageBytes()).isEqualTo(imageBytes);
    }

    @Test
    void renderPanelsSkipsGrafanaVariablesWithoutResolvedValue() {
        UUID testUuid = UUID.randomUUID();
        Instant from = Instant.parse("2024-01-01T00:00:00Z");
        Instant to = Instant.parse("2024-01-01T01:00:00Z");

        loadTestRepository.put(new LoadTest(testUuid, from, to, LoadTestType.MAX, null));
        confluenceTemplateSettingsService.registerDefaultTemplate(
                LoadTestType.MAX,
                new ConfluenceTemplate(
                        1,
                        "max",
                        "MAX report",
                        "MAX report",
                        "<p>Body</p>",
                        List.of(new ConfluenceTemplateChart(1L, 1, "Panel 11", "uid", 11, 0))));
        grafanaDashboardRepository.setDashboards(
                List.of(new GrafanaDashboard("uid", "slug", List.of(11), true)));
        grafanaDashboardVariableRepository.setVariables(
                "uid",
                List.of(
                        new GrafanaDashboardVariableRecord("uid", "service", "service", "query", null, "backend", List.of()),
                        new GrafanaDashboardVariableRecord("uid", "namespace", "namespace", "query", null, null, List.of()),
                        new GrafanaDashboardVariableRecord("uid", "", "ignored", "query", "prod", null, List.of())));
        grafanaClient.setRenderedPanel(11, new byte[] {7});
        grafanaClient.setPanelMetadata(11, new GrafanaPanelMetadata("Panel 11", ""));

        GrafanaRenderResponse response = grafanaService.renderPanels(new GrafanaRenderRequest(testUuid));

        assertThat(response.dashboards()).hasSize(1);
        assertThat(response.dashboards().get(0).panels()).hasSize(1);
        assertThat(grafanaClient.renderVariables(11))
                .containsEntry("service", "backend")
                .doesNotContainKey("namespace")
                .doesNotContainKey("");
    }

    @Test
    void renderPanelsByRangeAcceptsUnixTimestampsInSecondsAndMilliseconds() {
        Instant from = Instant.parse("2024-01-01T00:00:00Z");
        Instant to = Instant.parse("2024-01-01T01:00:00Z");

        confluenceTemplateSettingsService.registerTemplate(
                new ConfluenceTemplate(
                        77,
                        "max",
                        "MAX report",
                        "MAX report",
                        "<p>Body</p>",
                        List.of(new ConfluenceTemplateChart(1L, 77, "Panel 15", "uid", 15, 0))));
        grafanaDashboardRepository.setDashboards(
                List.of(new GrafanaDashboard("uid", "slug", List.of(15), true)));
        grafanaClient.setRenderedPanel(15, new byte[] {6});
        grafanaClient.setPanelMetadata(15, new GrafanaPanelMetadata("Panel 15", "CPU"));

        GrafanaRenderResponse secondsResponse = grafanaService.renderPanelsByRange(
                new GrafanaRenderRangeRequest("1704067200", "1704070800", 77));
        GrafanaRenderResponse millisecondsResponse = grafanaService.renderPanelsByRange(
                new GrafanaRenderRangeRequest("1704067200000", "1704070800000", 77));

        assertThat(secondsResponse.from()).isEqualTo(from.toString());
        assertThat(secondsResponse.to()).isEqualTo(to.toString());
        assertThat(secondsResponse.dashboards()).hasSize(1);
        assertThat(secondsResponse.dashboards().get(0).panels()).hasSize(1);
        assertThat(secondsResponse.dashboards().get(0).panels().get(0).imageBase64()).isEqualTo("Bg==");

        assertThat(millisecondsResponse.from()).isEqualTo(from.toString());
        assertThat(millisecondsResponse.to()).isEqualTo(to.toString());
        assertThat(millisecondsResponse.dashboards()).hasSize(1);
        assertThat(millisecondsResponse.dashboards().get(0).panels()).hasSize(1);
        assertThat(millisecondsResponse.dashboards().get(0).panels().get(0).imageBase64()).isEqualTo("Bg==");
    }

    @Test
    void renderPanelsByRangeUsesSelectedTemplateCharts() {
        Instant from = Instant.parse("2024-01-01T00:00:00Z");
        Instant to = Instant.parse("2024-01-01T01:00:00Z");

        confluenceTemplateSettingsService.registerTemplate(
                new ConfluenceTemplate(
                        55,
                        "stability",
                        "Stability report",
                        "Stability report",
                        "<p>Body</p>",
                        List.of(
                                new ConfluenceTemplateChart(1L, 55, "CPU", "uid", 7, 0),
                                new ConfluenceTemplateChart(2L, 55, "Memory", "uid", 8, 1))));
        grafanaDashboardRepository.setDashboards(
                List.of(new GrafanaDashboard("uid", "slug", List.of(1, 2, 3), true)));
        grafanaClient.setRenderedPanel(7, new byte[] {1});
        grafanaClient.setRenderedPanel(8, new byte[] {2});
        grafanaClient.setPanelMetadata(7, new GrafanaPanelMetadata("CPU", ""));
        grafanaClient.setPanelMetadata(8, new GrafanaPanelMetadata("Memory", ""));

        GrafanaRenderResponse response = grafanaService.renderPanelsByRange(
                new GrafanaRenderRangeRequest(from.toString(), to.toString(), 55));

        assertThat(response.from()).isEqualTo(from.toString());
        assertThat(response.to()).isEqualTo(to.toString());
        assertThat(response.dashboards()).hasSize(1);
        assertThat(response.dashboards().get(0).panels())
                .extracting(panel -> panel.panelId())
                .containsExactly(7, 8);
        assertThat(grafanaClient.renderCalls()).containsExactly(7, 8);
    }

    @Test
    void renderSelectedPanelsSkipsGrafanaVariablesWithoutResolvedValue() {
        Instant from = Instant.parse("2024-01-01T00:00:00Z");
        Instant to = Instant.parse("2024-01-01T01:00:00Z");

        grafanaDashboardRepository.setDashboards(
                List.of(new GrafanaDashboard("uid", "slug", List.of(12), true)));
        grafanaDashboardPanelRepository.register(
                new GrafanaDashboardPanelRecord("uid", "slug", "Dashboard", 12, "CPU", "up", null, false));
        grafanaDashboardVariableRepository.setVariables(
                "uid",
                List.of(
                        new GrafanaDashboardVariableRecord("uid", "service", "service", "query", null, "backend", List.of()),
                        new GrafanaDashboardVariableRecord("uid", "namespace", "namespace", "query", null, null, List.of())));
        grafanaClient.setRenderedPanel(12, new byte[] {8});

        List<perfhub.dto.grafana.GrafanaRenderedPanel> panels = grafanaService.renderSelectedPanels(
                from,
                to,
                List.of(new GrafanaPanelSelection("uid", 12)));

        assertThat(panels).hasSize(1);
        assertThat(panels.get(0).imageBase64()).isEqualTo("CA==");
        assertThat(grafanaClient.renderVariables(12))
                .containsEntry("service", "backend")
                .doesNotContainKey("namespace");
    }

    private static final class StubRenderedPanelCacheService extends GrafanaRenderedPanelCacheService {

        private final Map<String, CachedPanelImage> cachedImages = new HashMap<>();
        private final List<StoreCall> storeCalls = new ArrayList<>();

        private StubRenderedPanelCacheService() {
            super(null, "grafana-render-cache");
        }

        void put(
                UUID testUuid,
                int templateId,
                String dashboardUid,
                int panelId,
                Instant from,
                Instant to,
                Map<String, String> variables,
                GrafanaRenderOptions options,
                CachedPanelImage cachedPanelImage) {
            cachedImages.put(key(testUuid, templateId, dashboardUid, panelId, from, to, variables, options), cachedPanelImage);
        }

        List<StoreCall> storeCalls() {
            return List.copyOf(storeCalls);
        }

        @Override
        public Optional<CachedPanelImage> find(
                UUID testUuid,
                int templateId,
                String dashboardUid,
                int panelId,
                Instant from,
                Instant to,
                Map<String, String> variables,
                GrafanaRenderOptions options) {
            return Optional.ofNullable(cachedImages.get(
                    key(testUuid, templateId, dashboardUid, panelId, from, to, variables, options)));
        }

        @Override
        public void store(
                UUID testUuid,
                int templateId,
                String dashboardUid,
                String dashboardSlug,
                int panelId,
                Instant from,
                Instant to,
                Map<String, String> variables,
                GrafanaRenderOptions options,
                String title,
                String description,
                byte[] imageBytes) {
            storeCalls.add(new StoreCall(
                    testUuid,
                    templateId,
                    dashboardUid,
                    dashboardSlug,
                    panelId,
                    from,
                    to,
                    variables == null ? Map.of() : Map.copyOf(variables),
                    options,
                    title,
                    description,
                    imageBytes));
        }

        private String key(
                UUID testUuid,
                int templateId,
                String dashboardUid,
                int panelId,
                Instant from,
                Instant to,
                Map<String, String> variables,
                GrafanaRenderOptions options) {
            Map<String, String> safeVariables = variables == null ? Map.of() : new java.util.TreeMap<>(variables);
            return "%s|%d|%s|%d|%s|%s|%s|%s"
                    .formatted(testUuid, templateId, dashboardUid, panelId, from, to, safeVariables, options);
        }

        private record StoreCall(
                UUID testUuid,
                int templateId,
                String dashboardUid,
                String dashboardSlug,
                int panelId,
                Instant from,
                Instant to,
                Map<String, String> variables,
                GrafanaRenderOptions options,
                String title,
                String description,
                byte[] imageBytes) {
        }
    }

    private static final class StubGrafanaClient extends GrafanaClient {

        private final Map<Integer, byte[]> rendered = new HashMap<>();
        private final Map<Integer, GrafanaPanelMetadata> metadata = new HashMap<>();
        private final List<Integer> renderCalls = new ArrayList<>();
        private final Map<Integer, Map<String, String>> renderVariablesByPanel = new HashMap<>();
        private final Map<Integer, List<String>> renderSlugsByPanel = new HashMap<>();
        private final List<Integer> metadataFailures = new ArrayList<>();
        private final Map<String, String> currentSlugsByDashboard = new HashMap<>();

        private StubGrafanaClient() {
            super(new RestTemplate(), new perfhub.service.ConfigurationProvider() {
                @Override
                public String getRequiredValue(String service, String key) {
                    return findValue(service, key)
                            .orElseThrow(() -> new IllegalStateException("Missing config for " + service + ":" + key));
                }

                @Override
                public Optional<String> findValue(String service, String key) {
                    return Optional.empty();
                }
            });
        }

        void setRenderedPanel(int panelId, byte[] data) {
            rendered.put(panelId, data);
        }

        void setPanelMetadata(int panelId, GrafanaPanelMetadata value) {
            metadata.put(panelId, value);
        }

        List<Integer> renderCalls() {
            return List.copyOf(renderCalls);
        }

        Map<String, String> renderVariables(int panelId) {
            return renderVariablesByPanel.getOrDefault(panelId, Map.of());
        }

        List<String> renderSlugs(int panelId) {
            return renderSlugsByPanel.getOrDefault(panelId, List.of());
        }

        void failMetadataForPanel(int panelId) {
            metadataFailures.add(panelId);
        }

        void setCurrentSlug(String dashboardUid, String slug) {
            currentSlugsByDashboard.put(dashboardUid, slug);
        }

        @Override
        public byte[] renderPanel(String dashboardUid, String dashboardSlug, int panelId, Instant from, Instant to) {
            renderCalls.add(panelId);
            renderSlugsByPanel
                    .computeIfAbsent(panelId, ignored -> new ArrayList<>())
                    .add(dashboardSlug);
            String currentSlug = currentSlugsByDashboard.get(dashboardUid);
            if (currentSlug != null && !currentSlug.equals(dashboardSlug)) {
                throw new GrafanaClientException(
                        "Failed to render Grafana panel %d for dashboard '%s' (%s): HTTP 404 Not Found"
                                .formatted(panelId, dashboardSlug, dashboardUid),
                        null);
            }
            return rendered.getOrDefault(panelId, new byte[0]);
        }

        @Override
        public byte[] renderPanel(
                String dashboardUid,
                String dashboardSlug,
                int panelId,
                Instant from,
                Instant to,
                Map<String, String> variables) {
            renderVariablesByPanel.put(panelId, variables == null ? Map.of() : Map.copyOf(variables));
            return renderPanel(dashboardUid, dashboardSlug, panelId, from, to);
        }

        @Override
        public byte[] renderPanel(
                String dashboardUid,
                String dashboardSlug,
                int panelId,
                Instant from,
                Instant to,
                Map<String, String> variables,
                GrafanaRenderOptions renderOptions) {
            renderVariablesByPanel.put(panelId, variables == null ? Map.of() : Map.copyOf(variables));
            return renderPanel(dashboardUid, dashboardSlug, panelId, from, to);
        }

        @Override
        public GrafanaRenderOptions loadRenderOptions() {
            return new GrafanaRenderOptions(1200, 600, "Europe/Moscow");
        }

        @Override
        public GrafanaPanelMetadata getPanelMetadata(String dashboardUid, int panelId) {
            if (metadataFailures.contains(panelId)) {
                throw new GrafanaClientException("Metadata request failed", null);
            }
            return metadata.get(panelId);
        }

        @Override
        public String loadDashboardSlug(String dashboardUid) {
            return currentSlugsByDashboard.get(dashboardUid);
        }
    }

    private static final class StubLoadTestRepository extends LoadTestRepository {

        private final Map<UUID, LoadTest> tests = new HashMap<>();

        private StubLoadTestRepository() {
            super(new JdbcTemplate(), new TestTypeRepository(new JdbcTemplate()));
        }

        void put(LoadTest loadTest) {
            tests.put(loadTest.id(), loadTest);
        }

        @Override
        public Optional<LoadTest> findById(UUID id) {
            return Optional.ofNullable(tests.get(id));
        }

        @Override
        public LoadTest saveOrUpdate(Instant start, Instant end, LoadTestType type) {
            throw new UnsupportedOperationException();
        }
    }

    private static final class StubGrafanaDashboardRepository extends GrafanaDashboardRepository {

        private List<GrafanaDashboard> dashboards = List.of();

        private StubGrafanaDashboardRepository() {
            super(new JdbcTemplate());
        }

        void setDashboards(List<GrafanaDashboard> dashboards) {
            this.dashboards = dashboards;
        }

        @Override
        public List<GrafanaDashboard> findAll() {
            return dashboards;
        }

        @Override
        public Optional<GrafanaDashboard> findByUid(String uid) {
            return dashboards.stream()
                    .filter(dashboard -> dashboard.uid().equals(uid))
                    .findFirst();
        }

        @Override
        public GrafanaDashboard upsert(GrafanaDashboard dashboard) {
            dashboards = List.of(dashboard);
            return dashboard;
        }

        @Override
        public void deleteByUid(String uid) {
            throw new UnsupportedOperationException();
        }
    }

    private static final class StubGrafanaDashboardPanelRepository extends GrafanaDashboardPanelRepository {

        private final Map<String, Map<Integer, GrafanaDashboardPanelRecord>> panelsByDashboard = new HashMap<>();

        private StubGrafanaDashboardPanelRepository() {
            super(new JdbcTemplate());
        }

        void register(GrafanaDashboardPanelRecord panel) {
            panelsByDashboard
                    .computeIfAbsent(panel.dashboardUid(), ignored -> new HashMap<>())
                    .put(panel.panelId(), panel);
        }

        @Override
        public List<GrafanaDashboardPanelRecord> findByDashboardUid(String dashboardUid) {
            return new ArrayList<>(panelsByDashboard.getOrDefault(dashboardUid, Map.of()).values());
        }

        @Override
        public Optional<GrafanaDashboardPanelRecord> findByUidAndPanelId(String dashboardUid, int panelId) {
            return Optional.ofNullable(panelsByDashboard.getOrDefault(dashboardUid, Map.of()).get(panelId));
        }
    }

    private static final class StubGrafanaDashboardVariableRepository extends GrafanaDashboardVariableRepository {

        private final Map<String, List<perfhub.repository.model.GrafanaDashboardVariableRecord>>
                variablesByDashboard = new HashMap<>();

        private StubGrafanaDashboardVariableRepository() {
            super(new JdbcTemplate(), new ObjectMapper());
        }

        @Override
        public List<perfhub.repository.model.GrafanaDashboardVariableRecord> findByDashboardUid(
                String dashboardUid) {
            return variablesByDashboard.getOrDefault(dashboardUid, List.of());
        }

        void setVariables(String dashboardUid, List<GrafanaDashboardVariableRecord> variables) {
            variablesByDashboard.put(dashboardUid, List.copyOf(variables));
        }
    }

    private static final class StubConfluenceTemplateSettingsService extends ConfluenceTemplateSettingsService {

        private final Map<LoadTestType, ConfluenceTemplate> templatesByType = new HashMap<>();
        private final Map<Integer, ConfluenceTemplate> templatesById = new HashMap<>();

        private StubConfluenceTemplateSettingsService() {
            super(null, null, null, null);
        }

        void registerDefaultTemplate(LoadTestType type, ConfluenceTemplate template) {
            templatesByType.put(type, template);
            registerTemplate(template);
        }

        void registerTemplate(ConfluenceTemplate template) {
            templatesById.put(template.id(), template);
        }

        @Override
        public ConfluenceTemplate resolveDefaultTemplate(LoadTestType loadTestType) {
            if (loadTestType == null || loadTestType == LoadTestType.UNKNOWN) {
                throw new IllegalArgumentException("Тип теста не определен");
            }
            ConfluenceTemplate template = templatesByType.get(loadTestType);
            if (template != null) {
                return template;
            }
            throw new IllegalArgumentException(
                    "Confluence report template is not configured for test type " + loadTestType.value());
        }

        @Override
        public ConfluenceTemplate getActiveTemplate(int id) {
            ConfluenceTemplate template = templatesById.get(id);
            if (template != null) {
                return template;
            }
            throw new IllegalArgumentException("Шаблон отчета не найден");
        }
    }
}
