package com.example.chronicler.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.groups.Tuple.tuple;

import com.example.chronicler.client.GrafanaClient;
import com.example.chronicler.dto.grafana.GrafanaDashboardDefinition;
import com.example.chronicler.dto.grafana.GrafanaPanelDefinition;
import com.example.chronicler.dto.settings.GrafanaDashboardDetailsDto;
import com.example.chronicler.dto.settings.GrafanaDashboardPanelDto;
import com.example.chronicler.model.GrafanaDashboard;
import com.example.chronicler.repository.GrafanaDashboardRepository;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.client.RestTemplate;

class GrafanaDashboardSettingsServiceTest {

    private InMemoryGrafanaDashboardRepository repository;
    private StubGrafanaClient grafanaClient;
    private GrafanaDashboardSettingsService service;

    @BeforeEach
    void setUp() {
        repository = new InMemoryGrafanaDashboardRepository();
        grafanaClient = new StubGrafanaClient();
        service = new GrafanaDashboardSettingsService(repository, grafanaClient);
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
