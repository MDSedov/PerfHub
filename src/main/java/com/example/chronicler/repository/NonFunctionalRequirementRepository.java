package com.example.chronicler.repository;

import com.example.chronicler.model.NonFunctionalRequirement;
import com.example.chronicler.model.RequirementMetricType;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
public class NonFunctionalRequirementRepository {

    private static final String SELECT_ACTIVE_SQL = """
            SELECT id,
                   code,
                   metric_type,
                   threshold_value,
                   threshold_unit,
                   description_ru,
                   description_en,
                   active,
                   parameters
              FROM non_functional_requirements
             WHERE active = TRUE
             ORDER BY id
            """;

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
                rs.getString("description_ru"),
                rs.getString("description_en"));
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
