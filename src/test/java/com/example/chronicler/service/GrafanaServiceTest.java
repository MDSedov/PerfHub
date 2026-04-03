package com.example.chronicler.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.chronicler.client.GrafanaClient;
import com.example.chronicler.model.ConfluenceTemplate;
import com.example.chronicler.model.ConfluenceTemplateChart;
import com.example.chronicler.dto.grafana.GrafanaPanelMetadata;
import com.example.chronicler.dto.grafana.GrafanaPanelSelection;
import com.example.chronicler.dto.grafana.GrafanaRenderRequest;
import com.example.chronicler.dto.grafana.GrafanaRenderRangeRequest;
import com.example.chronicler.dto.grafana.GrafanaRenderResponse;
import com.example.chronicler.exception.GrafanaClientException;
import com.example.chronicler.exception.GrafanaInvalidTimeRangeException;
import com.example.chronicler.exception.GrafanaRenderingException;
import com.example.chronicler.model.GrafanaDashboard;
import com.example.chronicler.model.LoadTest;
import com.example.chronicler.model.LoadTestType;
import com.example.chronicler.repository.GrafanaDashboardPanelRepository;
import com.example.chronicler.repository.GrafanaDashboardRepository;
import com.example.chronicler.repository.GrafanaDashboardVariableRepository;
import com.example.chronicler.repository.LoadTestRepository;
import com.example.chronicler.repository.TestTypeRepository;
import com.example.chronicler.repository.model.GrafanaDashboardPanelRecord;
import com.example.chronicler.repository.model.GrafanaDashboardVariableRecord;
import com.example.chronicler.repository.model.GrafanaVariableOptionRecord;
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
        grafanaService = new GrafanaService(
                grafanaClient,
                grafanaRenderCache,
                loadTestRepository,
                grafanaDashboardRepository,
                grafanaDashboardPanelRepository,
                new GrafanaVariableService(grafanaDashboardVariableRepository),
                confluenceTemplateSettingsService);
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

        grafanaDashboardRepository.setDashboards(
                List.of(new GrafanaDashboard("uid", "slug", List.of(15), true)));
        grafanaClient.setRenderedPanel(15, new byte[] {6});
        grafanaClient.setPanelMetadata(15, new GrafanaPanelMetadata("Panel 15", "CPU"));

        GrafanaRenderResponse secondsResponse = grafanaService.renderPanelsByRange(
                new GrafanaRenderRangeRequest("1704067200", "1704070800"));
        GrafanaRenderResponse millisecondsResponse = grafanaService.renderPanelsByRange(
                new GrafanaRenderRangeRequest("1704067200000", "1704070800000"));

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

        List<com.example.chronicler.dto.grafana.GrafanaRenderedPanel> panels = grafanaService.renderSelectedPanels(
                from,
                to,
                List.of(new GrafanaPanelSelection("uid", 12)));

        assertThat(panels).hasSize(1);
        assertThat(panels.get(0).imageBase64()).isEqualTo("CA==");
        assertThat(grafanaClient.renderVariables(12))
                .containsEntry("service", "backend")
                .doesNotContainKey("namespace");
    }

    private static final class StubGrafanaClient extends GrafanaClient {

        private final Map<Integer, byte[]> rendered = new HashMap<>();
        private final Map<Integer, GrafanaPanelMetadata> metadata = new HashMap<>();
        private final List<Integer> renderCalls = new ArrayList<>();
        private final Map<Integer, Map<String, String>> renderVariablesByPanel = new HashMap<>();
        private final List<Integer> metadataFailures = new ArrayList<>();

        private StubGrafanaClient() {
            super(new RestTemplate(), new com.example.chronicler.service.ConfigurationProvider() {
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

        void failMetadataForPanel(int panelId) {
            metadataFailures.add(panelId);
        }

        @Override
        public byte[] renderPanel(String dashboardUid, String dashboardSlug, int panelId, Instant from, Instant to) {
            renderCalls.add(panelId);
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
        public GrafanaPanelMetadata getPanelMetadata(String dashboardUid, int panelId) {
            if (metadataFailures.contains(panelId)) {
                throw new GrafanaClientException("Metadata request failed", null);
            }
            return metadata.get(panelId);
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

        private final Map<String, List<com.example.chronicler.repository.model.GrafanaDashboardVariableRecord>>
                variablesByDashboard = new HashMap<>();

        private StubGrafanaDashboardVariableRepository() {
            super(new JdbcTemplate(), new ObjectMapper());
        }

        @Override
        public List<com.example.chronicler.repository.model.GrafanaDashboardVariableRecord> findByDashboardUid(
                String dashboardUid) {
            return variablesByDashboard.getOrDefault(dashboardUid, List.of());
        }

        void setVariables(String dashboardUid, List<GrafanaDashboardVariableRecord> variables) {
            variablesByDashboard.put(dashboardUid, List.copyOf(variables));
        }
    }

    private static final class StubConfluenceTemplateSettingsService extends ConfluenceTemplateSettingsService {

        private final Map<LoadTestType, ConfluenceTemplate> templatesByType = new HashMap<>();

        private StubConfluenceTemplateSettingsService() {
            super(null, null, null, null);
        }

        void registerDefaultTemplate(LoadTestType type, ConfluenceTemplate template) {
            templatesByType.put(type, template);
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
    }
}
