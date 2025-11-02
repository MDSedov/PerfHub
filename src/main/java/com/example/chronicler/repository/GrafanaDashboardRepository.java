package com.example.chronicler.repository;

import com.example.chronicler.model.GrafanaDashboard;
import java.sql.Array;
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
}
