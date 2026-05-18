package com.example.chronicler.repository;

import com.example.chronicler.repository.model.GrafanaDashboardPanelRecord;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
public class GrafanaDashboardPanelRepository {

    private static final String BASE_SELECT =
            "SELECT dashboard_uid, dashboard_slug, dashboard_title, panel_id, panel_title, panel_query, panel_threshold, is_test_query"
                    + " FROM grafana_dashboard_panels";
    private static final String SELECT_ALL = BASE_SELECT + " ORDER BY dashboard_uid, panel_id";
    private static final String SELECT_BY_UID = BASE_SELECT + " WHERE dashboard_uid = ? ORDER BY panel_id";
    private static final String DELETE_BY_UID =
            "DELETE FROM grafana_dashboard_panels WHERE dashboard_uid = ?";
    private static final String INSERT_SQL =
            "INSERT INTO grafana_dashboard_panels"
                    + " (dashboard_uid, dashboard_slug, dashboard_title, panel_id, panel_title, panel_query, panel_threshold, is_test_query)"
                    + " VALUES (?, ?, ?, ?, ?, ?, ?, ?)";

    private final JdbcTemplate jdbcTemplate;
    private final RowMapper<GrafanaDashboardPanelRecord> rowMapper = (rs, rowNum) -> {
        Double threshold = null;
        Object rawThreshold = rs.getObject("panel_threshold");
        if (rawThreshold != null) {
            threshold = rs.getDouble("panel_threshold");
            if (rs.wasNull()) {
                threshold = null;
            }
        }
        return new GrafanaDashboardPanelRecord(
                rs.getString("dashboard_uid"),
                rs.getString("dashboard_slug"),
                rs.getString("dashboard_title"),
                rs.getInt("panel_id"),
                rs.getString("panel_title"),
                rs.getString("panel_query"),
                threshold,
                rs.getObject("is_test_query") != null ? rs.getBoolean("is_test_query") : Boolean.FALSE);
    };

    public GrafanaDashboardPanelRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public List<GrafanaDashboardPanelRecord> findAll() {
        return jdbcTemplate.query(SELECT_ALL, rowMapper);
    }

    public List<GrafanaDashboardPanelRecord> findByDashboardUid(String dashboardUid) {
        return jdbcTemplate.query(SELECT_BY_UID, rowMapper, dashboardUid);
    }

    public Optional<GrafanaDashboardPanelRecord> findByUidAndPanelId(String dashboardUid, int panelId) {
        String sql = BASE_SELECT + " WHERE dashboard_uid = ? AND panel_id = ? LIMIT 1";
        return jdbcTemplate.query(sql, rowMapper, dashboardUid, panelId).stream().findFirst();
    }

    public Optional<GrafanaDashboardPanelRecord> findFirstByPanelId(int panelId) {
        String sql = BASE_SELECT + " WHERE panel_id = ? LIMIT 1";
        return jdbcTemplate.query(sql, rowMapper, panelId).stream().findFirst();
    }

    public Optional<GrafanaDashboardPanelRecord> findTestQueryPanel() {
        String sql = BASE_SELECT + " WHERE is_test_query = TRUE LIMIT 1";
        return jdbcTemplate.query(sql, rowMapper).stream().findFirst();
    }

    public Map<String, List<GrafanaDashboardPanelRecord>> findAllGroupedByUid() {
        return findAll().stream()
                .collect(Collectors.groupingBy(GrafanaDashboardPanelRecord::dashboardUid));
    }

    public void replaceForDashboard(
            String dashboardUid,
            String dashboardSlug,
            String dashboardTitle,
            Collection<GrafanaDashboardPanelRecord> panels) {
        jdbcTemplate.update(DELETE_BY_UID, dashboardUid);
        if (panels == null || panels.isEmpty()) {
            return;
        }
        List<GrafanaDashboardPanelRecord> sanitized = panels.stream().toList();
        jdbcTemplate.batchUpdate(INSERT_SQL, new BatchPreparedStatementSetter() {
            @Override
            public void setValues(PreparedStatement ps, int i) throws SQLException {
                GrafanaDashboardPanelRecord panel = sanitized.get(i);
                ps.setString(1, dashboardUid);
                ps.setString(2, dashboardSlug);
                ps.setString(3, dashboardTitle);
                ps.setInt(4, panel.panelId());
                ps.setString(5, panel.panelTitle());
                ps.setString(6, panel.panelQuery());
                if (panel.panelThreshold() == null) {
                    ps.setObject(7, null);
                } else {
                    ps.setDouble(7, panel.panelThreshold());
                }
                ps.setBoolean(8, Boolean.TRUE.equals(panel.testQuery()));
            }

            @Override
            public int getBatchSize() {
                return sanitized.size();
            }
        });
    }

    public void deleteByDashboardUid(String dashboardUid) {
        jdbcTemplate.update(DELETE_BY_UID, dashboardUid);
    }

    public void deleteMissingDashboards(Set<String> existingUids) {
        if (existingUids == null || existingUids.isEmpty()) {
            jdbcTemplate.update("DELETE FROM grafana_dashboard_panels");
            return;
        }
        String placeholders = existingUids.stream().map(uid -> "?").collect(Collectors.joining(","));
        String sql = "DELETE FROM grafana_dashboard_panels WHERE dashboard_uid NOT IN (" + placeholders + ")";
        jdbcTemplate.update(sql, existingUids.toArray());
    }

    public void clearTestQueryFlag() {
        jdbcTemplate.update("UPDATE grafana_dashboard_panels SET is_test_query = FALSE WHERE is_test_query = TRUE");
    }
}
