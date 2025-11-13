package com.example.chronicler.repository;

import com.example.chronicler.model.NonFunctionalRequirement;
import com.example.chronicler.model.RequirementMetricType;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
public class NonFunctionalRequirementRepository {

    private static final String SELECT_COLUMNS = """
            id,
            code,
            metric_type,
            threshold_value,
            threshold_unit,
            description_ru,
            active,
            parameters
            """;
    private static final String SELECT_ACTIVE_SQL = """
            SELECT %s
              FROM non_functional_requirements
             WHERE active = TRUE
             ORDER BY id
            """.formatted(SELECT_COLUMNS);
    private static final String SELECT_ALL_SQL = """
            SELECT %s
              FROM non_functional_requirements
             ORDER BY id
            """.formatted(SELECT_COLUMNS);
    private static final String INSERT_SQL = """
            INSERT INTO non_functional_requirements (
                code,
                metric_type,
                threshold_value,
                threshold_unit,
                description_ru,
                active,
                parameters
            ) VALUES (?, ?, ?, ?, ?, ?, ?::jsonb)
            RETURNING %s
            """.formatted(SELECT_COLUMNS);
    private static final String UPDATE_SQL = """
            UPDATE non_functional_requirements
               SET code = ?,
                   metric_type = ?,
                   threshold_value = ?,
                   threshold_unit = ?,
                   description_ru = ?,
                   active = ?,
                   parameters = ?::jsonb,
                   updated_at = NOW()
             WHERE id = ?
         RETURNING %s
            """.formatted(SELECT_COLUMNS);
    private static final String DELETE_SQL = "DELETE FROM non_functional_requirements WHERE id = ?";

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final RowMapper<NonFunctionalRequirement> rowMapper = this::mapRow;

    public NonFunctionalRequirementRepository(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    public List<NonFunctionalRequirement> findAllActive() {
        return jdbcTemplate.query(SELECT_ACTIVE_SQL, rowMapper);
    }

    public List<NonFunctionalRequirement> findAll() {
        return jdbcTemplate.query(SELECT_ALL_SQL, rowMapper);
    }

    public NonFunctionalRequirement insert(NonFunctionalRequirement requirement) {
        return jdbcTemplate.query(
                        INSERT_SQL,
                        ps -> bindRequirement(ps, requirement),
                        rowMapper)
                .stream()
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("Failed to insert requirement"));
    }

    public NonFunctionalRequirement update(NonFunctionalRequirement requirement) {
        return jdbcTemplate.query(
                        UPDATE_SQL,
                        ps -> {
                            bindRequirement(ps, requirement);
                            ps.setInt(8, requirement.id());
                        },
                        rowMapper)
                .stream()
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("Failed to update requirement " + requirement.id()));
    }

    public void delete(int id) {
        jdbcTemplate.update(DELETE_SQL, id);
    }

    private void bindRequirement(PreparedStatement ps, NonFunctionalRequirement requirement) throws SQLException {
        ps.setString(1, requirement.code());
        ps.setString(2, requirement.metricType().name());
        ps.setDouble(3, requirement.thresholdValue());
        ps.setString(4, requirement.thresholdUnit());
        ps.setString(5, requirement.descriptionRu());
        ps.setBoolean(6, requirement.active());
        ps.setObject(7, toJsonDatabaseValue(requirement.parameters()), Types.OTHER);
    }

    private Object toJsonDatabaseValue(JsonNode node) {
        try {
            if (node == null || node.isNull()) {
                return "{}";
            }
            return objectMapper.writeValueAsString(node);
        } catch (JsonProcessingException ex) {
            throw new IllegalArgumentException("Failed to serialize requirement parameters to JSON", ex);
        }
    }

    private NonFunctionalRequirement mapRow(ResultSet rs, int rowNum) throws SQLException {
        RequirementMetricType metricType =
                RequirementMetricType.fromDatabaseValue(rs.getString("metric_type"));
        JsonNode parameters = parseParameters(rs.getObject("parameters"));
        return new NonFunctionalRequirement(
                rs.getInt("id"),
                rs.getString("code"),
                metricType,
                rs.getDouble("threshold_value"),
                rs.getString("threshold_unit"),
                rs.getBoolean("active"),
                parameters,
                rs.getString("description_ru"));
    }

    private JsonNode parseParameters(Object rawValue) throws SQLException {
        if (rawValue == null) {
            return objectMapper.createObjectNode();
        }
        try {
            if (rawValue instanceof String stringValue) {
                if (stringValue.isBlank()) {
                    return objectMapper.createObjectNode();
                }
                return objectMapper.readTree(stringValue);
            }
            return objectMapper.readTree(rawValue.toString());
        } catch (JsonProcessingException ex) {
            throw new SQLException("Unable to parse requirement parameters JSON", ex);
        }
    }
}
