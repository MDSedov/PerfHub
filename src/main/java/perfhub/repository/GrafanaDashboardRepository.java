package perfhub.repository;

import perfhub.model.GrafanaDashboard;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import org.springframework.util.StringUtils;

@Repository
public class GrafanaDashboardRepository {

    private static final String SELECT_ALL_SQL =
            "SELECT dashboard_uid, slug, panels, enabled FROM grafana_dashboards ORDER BY dashboard_uid";
    private static final String SELECT_ONE_SQL =
            "SELECT dashboard_uid, slug, panels, enabled FROM grafana_dashboards WHERE dashboard_uid = ?";
    private static final String INSERT_SQL =
            "INSERT INTO grafana_dashboards (dashboard_uid, slug, panels, enabled) VALUES (?, ?, ?, ?)";
    private static final String UPDATE_SQL =
            "UPDATE grafana_dashboards SET slug = ?, panels = ?, enabled = ? WHERE dashboard_uid = ?";
    private static final String DELETE_SQL = "DELETE FROM grafana_dashboards WHERE dashboard_uid = ?";

    private final JdbcTemplate jdbcTemplate;
    private final RowMapper<GrafanaDashboard> rowMapper = (rs, rowNum) -> {
        List<Integer> panels = parsePanels(rs.getString("panels"));
        return new GrafanaDashboard(
                rs.getString("dashboard_uid"),
                rs.getString("slug"),
                panels,
                rs.getBoolean("enabled"));
    };

    public GrafanaDashboardRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public List<GrafanaDashboard> findAll() {
        return jdbcTemplate.query(SELECT_ALL_SQL, rowMapper);
    }

    public Optional<GrafanaDashboard> findByUid(String uid) {
        return jdbcTemplate.query(SELECT_ONE_SQL, rowMapper, uid).stream().findFirst();
    }

    public GrafanaDashboard upsert(GrafanaDashboard dashboard) {
        String panelsValue = serializePanels(dashboard.panels());
        int updated = jdbcTemplate.update(
                UPDATE_SQL,
                dashboard.slug(),
                panelsValue,
                dashboard.enabled(),
                dashboard.uid());
        if (updated == 0) {
            jdbcTemplate.update(INSERT_SQL, dashboard.uid(), dashboard.slug(), panelsValue, dashboard.enabled());
        }
        return jdbcTemplate.query(SELECT_ONE_SQL, rowMapper, dashboard.uid()).stream()
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("Failed to upsert Grafana dashboard " + dashboard.uid()));
    }

    public void deleteByUid(String uid) {
        jdbcTemplate.update(DELETE_SQL, uid);
    }

    private String serializePanels(List<Integer> panels) {
        if (panels == null || panels.isEmpty()) {
            return "";
        }
        return panels.stream()
                .filter(Objects::nonNull)
                .map(String::valueOf)
                .collect(Collectors.joining(","));
    }

    private List<Integer> parsePanels(String rawValue) {
        if (!StringUtils.hasText(rawValue)) {
            return List.of();
        }
        return Arrays.stream(rawValue.split(","))
                .map(String::trim)
                .filter(token -> !token.isEmpty())
                .map(Integer::valueOf)
                .collect(Collectors.toList());
    }
}
