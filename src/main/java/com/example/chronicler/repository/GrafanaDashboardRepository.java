package com.example.chronicler.repository;

import com.example.chronicler.model.GrafanaDashboard;
import java.sql.Array;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
public class GrafanaDashboardRepository {

    private static final String SELECT_ALL_SQL =
            "SELECT dashboard_uid, slug, panels FROM grafana_dashboards ORDER BY dashboard_uid";
    private static final String UPSERT_SQL = """
            INSERT INTO grafana_dashboards (dashboard_uid, slug, panels)
            VALUES (?, ?, ?)
            ON CONFLICT (dashboard_uid) DO UPDATE
                SET slug = EXCLUDED.slug,
                    panels = EXCLUDED.panels
            RETURNING dashboard_uid, slug, panels
            """;
    private static final String DELETE_SQL = "DELETE FROM grafana_dashboards WHERE dashboard_uid = ?";

    private final JdbcTemplate jdbcTemplate;
    private final RowMapper<GrafanaDashboard> rowMapper = (rs, rowNum) -> {
        Array array = rs.getArray("panels");
        List<Integer> panels;
        if (array == null) {
            panels = List.of();
        } else {
            try {
                Integer[] panelArray = (Integer[]) array.getArray();
                panels = (panelArray == null || panelArray.length == 0)
                        ? List.of()
                        : Arrays.asList(panelArray);
            } catch (SQLException ex) {
                throw new IllegalStateException("Failed to map Grafana dashboard panels", ex);
            }
        }
        return new GrafanaDashboard(
                rs.getString("dashboard_uid"),
                rs.getString("slug"),
                panels);
    };

    public GrafanaDashboardRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public List<GrafanaDashboard> findAll() {
        return jdbcTemplate.query(SELECT_ALL_SQL, rowMapper);
    }

    public GrafanaDashboard upsert(GrafanaDashboard dashboard) {
        return jdbcTemplate.query(con -> {
            var ps = con.prepareStatement(UPSERT_SQL);
            ps.setString(1, dashboard.uid());
            ps.setString(2, dashboard.slug());
            ps.setArray(3, createPanelsArray(con, dashboard.panels()));
            return ps;
        }, rowMapper).stream()
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("Failed to upsert Grafana dashboard " + dashboard.uid()));
    }

    public void deleteByUid(String uid) {
        jdbcTemplate.update(DELETE_SQL, uid);
    }

    private Array createPanelsArray(Connection connection, List<Integer> panels) throws SQLException {
        Integer[] values = (panels == null || panels.isEmpty())
                ? new Integer[0]
                : panels.toArray(Integer[]::new);
        return connection.createArrayOf("integer", values);
    }
}
