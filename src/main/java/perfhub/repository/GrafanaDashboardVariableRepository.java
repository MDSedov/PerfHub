package com.example.chronicler.repository;

import com.example.chronicler.repository.model.GrafanaDashboardVariableRecord;
import com.example.chronicler.repository.model.GrafanaVariableOptionRecord;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
public class GrafanaDashboardVariableRepository {

    private static final String BASE_SELECT =
            "SELECT dashboard_uid, name, label, type, default_value, current_value, options_json"
                    + " FROM grafana_dashboard_variables";
    private static final String SELECT_ALL = BASE_SELECT + " ORDER BY dashboard_uid, name";
    private static final String SELECT_BY_UID = BASE_SELECT + " WHERE dashboard_uid = ? ORDER BY name";
    private static final String DELETE_BY_UID = "DELETE FROM grafana_dashboard_variables WHERE dashboard_uid = ?";
    private static final String INSERT_SQL =
            "INSERT INTO grafana_dashboard_variables"
                    + " (dashboard_uid, name, label, type, default_value, current_value, options_json)"
                    + " VALUES (?, ?, ?, ?, ?, ?, ?)";

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final RowMapper<GrafanaDashboardVariableRecord> rowMapper = (rs, rowNum) -> new GrafanaDashboardVariableRecord(
            rs.getString("dashboard_uid"),
            rs.getString("name"),
            rs.getString("label"),
            rs.getString("type"),
            rs.getString("default_value"),
            rs.getString("current_value"),
            parseOptions(rs.getString("options_json")));

    public GrafanaDashboardVariableRepository(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    public List<GrafanaDashboardVariableRecord> findByDashboardUid(String dashboardUid) {
        return jdbcTemplate.query(SELECT_BY_UID, rowMapper, dashboardUid);
    }

    public Map<String, List<GrafanaDashboardVariableRecord>> findAllGroupedByUid() {
        return jdbcTemplate.query(SELECT_ALL, rowMapper).stream()
                .collect(Collectors.groupingBy(GrafanaDashboardVariableRecord::dashboardUid));
    }

    public void replaceForDashboard(String dashboardUid, Collection<GrafanaDashboardVariableRecord> variables) {
        jdbcTemplate.update(DELETE_BY_UID, dashboardUid);
        if (variables == null || variables.isEmpty()) {
            return;
        }
        List<GrafanaDashboardVariableRecord> sanitized = variables.stream().toList();
        jdbcTemplate.batchUpdate(INSERT_SQL, new BatchPreparedStatementSetter() {
            @Override
            public void setValues(PreparedStatement ps, int i) throws SQLException {
                GrafanaDashboardVariableRecord variable = sanitized.get(i);
                ps.setString(1, dashboardUid);
                ps.setString(2, variable.name());
                ps.setString(3, variable.label());
                ps.setString(4, variable.type());
                ps.setString(5, variable.defaultValue());
                ps.setString(6, variable.value());
                ps.setString(7, serializeOptions(variable.options()));
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
            jdbcTemplate.update("DELETE FROM grafana_dashboard_variables");
            return;
        }
        String placeholders = existingUids.stream().map(uid -> "?").collect(Collectors.joining(","));
        String sql = "DELETE FROM grafana_dashboard_variables WHERE dashboard_uid NOT IN (" + placeholders + ")";
        jdbcTemplate.update(sql, existingUids.toArray());
    }

    private List<GrafanaVariableOptionRecord> parseOptions(String rawOptions) {
        if (rawOptions == null || rawOptions.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(rawOptions, new TypeReference<>() {});
        } catch (JsonProcessingException ex) {
            return List.of();
        }
    }

    private String serializeOptions(List<GrafanaVariableOptionRecord> options) {
        if (options == null || options.isEmpty()) {
            return "[]";
        }
        try {
            return objectMapper.writeValueAsString(options);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Failed to serialize Grafana variable options", ex);
        }
    }
}
