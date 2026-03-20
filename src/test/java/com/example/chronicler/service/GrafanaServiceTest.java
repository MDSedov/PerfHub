package com.example.chronicler.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.chronicler.client.GrafanaClient;
import com.example.chronicler.dto.grafana.GrafanaPanelMetadata;
import com.example.chronicler.dto.grafana.GrafanaRenderRequest;
import com.example.chronicler.dto.grafana.GrafanaRenderResponse;
import com.example.chronicler.exception.GrafanaClientException;
import com.example.chronicler.exception.GrafanaInvalidTimeRangeException;
import com.example.chronicler.exception.GrafanaRenderingException;
import com.example.chronicler.model.GrafanaDashboard;
import com.example.chronicler.model.LoadTest;
import com.example.chronicler.model.LoadTestType;
import com.example.chronicler.repository.GrafanaDashboardPanelRepository;
import com.example.chronicler.repository.GrafanaDashboardRepository;
import com.example.chronicler.repository.LoadTestRepository;
import com.example.chronicler.repository.TestTypeRepository;
import com.example.chronicler.repository.model.GrafanaDashboardPanelRecord;
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
    private GrafanaService grafanaService;

    @BeforeEach
    void setUp() {
        grafanaClient = new StubGrafanaClient();
        grafanaRenderCache = new GrafanaRenderCache();
        loadTestRepository = new StubLoadTestRepository();
        grafanaDashboardRepository = new StubGrafanaDashboardRepository();
        grafanaDashboardPanelRepository = new StubGrafanaDashboardPanelRepository();
        grafanaService = new GrafanaService(
                grafanaClient,
                grafanaRenderCache,
                loadTestRepository,
                grafanaDashboardRepository,
                grafanaDashboardPanelRepository);
    }

    @Test
    void renderPanelsReturnsEncodedImages() {
        UUID testUuid = UUID.randomUUID();
        Instant from = Instant.parse("2024-01-01T00:00:00Z");
        Instant to = Instant.parse("2024-01-01T01:00:00Z");

        loadTestRepository.put(new LoadTest(testUuid, from, to, LoadTestType.UNKNOWN, null));
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
    void renderPanelsThrowsWhenDashboardsMissing() {
        UUID testUuid = UUID.randomUUID();
        Instant from = Instant.parse("2024-01-01T00:00:00Z");
        Instant to = Instant.parse("2024-01-01T01:00:00Z");
        loadTestRepository.put(new LoadTest(testUuid, from, to, LoadTestType.UNKNOWN, null));
        grafanaDashboardRepository.setDashboards(List.of());

        assertThatThrownBy(() -> grafanaService.renderPanels(new GrafanaRenderRequest(testUuid)))
                .isInstanceOf(GrafanaRenderingException.class)
                .hasMessage("No enabled Grafana dashboards configured");
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
    void renderPanelsThrowsWhenDashboardsDisabled() {
        UUID testUuid = UUID.randomUUID();
        Instant from = Instant.parse("2024-01-01T00:00:00Z");
        Instant to = Instant.parse("2024-01-01T01:00:00Z");

        loadTestRepository.put(new LoadTest(testUuid, from, to, LoadTestType.UNKNOWN, null));
        grafanaDashboardRepository.setDashboards(List.of(new GrafanaDashboard("uid", "slug", List.of(1, 2), false)));

        assertThatThrownBy(() -> grafanaService.renderPanels(new GrafanaRenderRequest(testUuid)))
                .isInstanceOf(GrafanaRenderingException.class)
                .hasMessage("No enabled Grafana dashboards configured");
    }

    @Test
    void renderPanelsFallsBackToCachedTitleWhenMetadataRequestFails() {
        UUID testUuid = UUID.randomUUID();
        Instant from = Instant.parse("2024-01-01T00:00:00Z");
        Instant to = Instant.parse("2024-01-01T01:00:00Z");

        loadTestRepository.put(new LoadTest(testUuid, from, to, LoadTestType.UNKNOWN, null));
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

    private static final class StubGrafanaClient extends GrafanaClient {

        private final Map<Integer, byte[]> rendered = new HashMap<>();
        private final Map<Integer, GrafanaPanelMetadata> metadata = new HashMap<>();
        private final List<Integer> renderCalls = new ArrayList<>();
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

        void failMetadataForPanel(int panelId) {
            metadataFailures.add(panelId);
        }

        @Override
        public byte[] renderPanel(String dashboardUid, String dashboardSlug, int panelId, Instant from, Instant to) {
            renderCalls.add(panelId);
            return rendered.getOrDefault(panelId, new byte[0]);
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
    }
}
