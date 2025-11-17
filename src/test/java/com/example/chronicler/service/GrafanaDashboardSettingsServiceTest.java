package com.example.chronicler.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.groups.Tuple.tuple;

import com.example.chronicler.client.GrafanaClient;
import com.example.chronicler.dto.grafana.GrafanaDashboardDefinition;
import com.example.chronicler.dto.grafana.GrafanaPanelDefinition;
import com.example.chronicler.dto.settings.GrafanaDashboardDetailsDto;
import com.example.chronicler.dto.settings.GrafanaDashboardPanelDto;
import com.example.chronicler.model.GrafanaDashboard;
import com.example.chronicler.repository.GrafanaDashboardPanelRepository;
import com.example.chronicler.repository.GrafanaDashboardRepository;
import com.example.chronicler.repository.model.GrafanaDashboardPanelRecord;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.client.RestTemplate;

class GrafanaDashboardSettingsServiceTest {

    private InMemoryGrafanaDashboardRepository repository;
    private InMemoryDashboardPanelRepository panelRepository;
    private StubGrafanaClient grafanaClient;
    private GrafanaDashboardSettingsService service;

    @BeforeEach
    void setUp() {
        repository = new InMemoryGrafanaDashboardRepository();
        panelRepository = new InMemoryDashboardPanelRepository();
        grafanaClient = new StubGrafanaClient();
        service = new GrafanaDashboardSettingsService(repository, panelRepository, grafanaClient);
    }

    @Test
    void pullFromGrafanaMarksSavedPanelsAndEnabledDashboards() {
        repository.upsert(new GrafanaDashboard("uid1", "slug1", List.of(10), true));
        grafanaClient.setDashboards(List.of(
                new GrafanaDashboardDefinition(
                        "uid1",
                        "slug1",
                        "Main dashboard",
                        List.of(
                                new GrafanaPanelDefinition(10, "Throughput"),
                                new GrafanaPanelDefinition(11, "Latency"))),
                new GrafanaDashboardDefinition(
                        "uid2",
                        "slug2",
                        "Secondary",
                        List.of(new GrafanaPanelDefinition(1, "Errors")))));

        List<GrafanaDashboardDetailsDto> dashboards = service.pullFromGrafana();

        assertThat(dashboards).hasSize(2);

        GrafanaDashboardDetailsDto first = dashboards.get(0);
        assertThat(first.uid()).isEqualTo("uid1");
        assertThat(first.enabled()).isTrue();
        assertThat(first.panels())
                .extracting(GrafanaDashboardPanelDto::panelId, GrafanaDashboardPanelDto::selected)
                .containsExactly(
                        tuple(10, true),
                        tuple(11, false));

        GrafanaDashboardDetailsDto second = dashboards.get(1);
        assertThat(second.uid()).isEqualTo("uid2");
        assertThat(second.enabled()).isFalse();
        assertThat(second.panels())
                .extracting(GrafanaDashboardPanelDto::panelId, GrafanaDashboardPanelDto::selected)
                .containsExactly(tuple(1, false));

        assertThat(panelRepository.findAll())
                .extracting(
                        GrafanaDashboardPanelRecord::dashboardUid,
                        GrafanaDashboardPanelRecord::panelId,
                        GrafanaDashboardPanelRecord::panelTitle)
                .contains(
                        tuple("uid1", 10, "Throughput"),
                        tuple("uid1", 11, "Latency"),
                        tuple("uid2", 1, "Errors"));
    }

    @Test
    void listDashboardsReturnsCachedDataWhenOffline() {
        repository.upsert(new GrafanaDashboard("uid1", "slug1", List.of(10), true));
        panelRepository.replaceForDashboard(
                "uid1",
                "slug1",
                "Cached",
                List.of(new GrafanaDashboardPanelRecord("uid1", "slug1", "Cached", 10, "Throughput")));

        List<GrafanaDashboardDetailsDto> dashboards = service.listDashboards();

        assertThat(dashboards)
                .singleElement()
                .extracting(
                        GrafanaDashboardDetailsDto::uid,
                        GrafanaDashboardDetailsDto::title,
                        GrafanaDashboardDetailsDto::enabled)
                .containsExactly("uid1", "Cached", true);
        assertThat(dashboards.get(0).panels())
                .extracting(GrafanaDashboardPanelDto::panelId, GrafanaDashboardPanelDto::selected)
                .containsExactly(tuple(10, true));
    }

    private static final class InMemoryGrafanaDashboardRepository extends GrafanaDashboardRepository {

        private final Map<String, GrafanaDashboard> dashboards = new LinkedHashMap<>();

        private InMemoryGrafanaDashboardRepository() {
            super(new JdbcTemplate());
        }

        @Override
        public List<GrafanaDashboard> findAll() {
            return List.copyOf(dashboards.values());
        }

        @Override
        public GrafanaDashboard upsert(GrafanaDashboard dashboard) {
            dashboards.put(dashboard.uid(), dashboard);
            return dashboard;
        }

        @Override
        public void deleteByUid(String uid) {
            dashboards.remove(uid);
        }
    }

    private static final class InMemoryDashboardPanelRepository extends GrafanaDashboardPanelRepository {

        private final Map<String, List<GrafanaDashboardPanelRecord>> storage = new LinkedHashMap<>();

        private InMemoryDashboardPanelRepository() {
            super(new JdbcTemplate());
        }

        @Override
        public List<GrafanaDashboardPanelRecord> findAll() {
            return storage.values().stream().flatMap(List::stream).collect(Collectors.toList());
        }

        @Override
        public List<GrafanaDashboardPanelRecord> findByDashboardUid(String dashboardUid) {
            return storage.getOrDefault(dashboardUid, List.of());
        }

        @Override
        public Map<String, List<GrafanaDashboardPanelRecord>> findAllGroupedByUid() {
            return storage;
        }

        @Override
        public void replaceForDashboard(
                String dashboardUid,
                String dashboardSlug,
                String dashboardTitle,
                Collection<GrafanaDashboardPanelRecord> panels) {
            storage.put(dashboardUid, panels.stream().toList());
        }

        @Override
        public void deleteByDashboardUid(String dashboardUid) {
            storage.remove(dashboardUid);
        }

        @Override
        public void deleteMissingDashboards(Set<String> existingUids) {
            storage.keySet().removeIf(uid -> !existingUids.contains(uid));
        }
    }

    private static final class StubGrafanaClient extends GrafanaClient {

        private List<GrafanaDashboardDefinition> dashboards = List.of();

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

        void setDashboards(List<GrafanaDashboardDefinition> dashboards) {
            this.dashboards = dashboards;
        }

        @Override
        public List<GrafanaDashboardDefinition> discoverDashboards() {
            return dashboards;
        }
    }
}
