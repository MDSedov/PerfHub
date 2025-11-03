package com.example.chronicler.repository;

import com.example.chronicler.model.RequirementEvaluationStatus;
import com.example.chronicler.model.TestRequirementEvaluation;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
public class TestRequirementEvaluationRepository {

    private static final String UPSERT_SQL = """
            INSERT INTO test_requirement_evaluations (
                test_uuid,
                requirement_id,
                status,
                measured_value,
                unit,
                details,
                evaluated_at)
            VALUES (?, ?, ?, ?, ?, ?::jsonb, ?)
            ON CONFLICT (test_uuid, requirement_id)
            DO UPDATE SET
                status = EXCLUDED.status,
                measured_value = EXCLUDED.measured_value,
                unit = EXCLUDED.unit,
                details = EXCLUDED.details,
                evaluated_at = EXCLUDED.evaluated_at
            """;

    private static final String SELECT_BY_TEST_SQL = """
            SELECT id,
                   test_uuid,
                   requirement_id,
                   status,
                   measured_value,
                   unit,
                   details,
                   evaluated_at
              FROM test_requirement_evaluations
             WHERE test_uuid = ?
             ORDER BY requirement_id
            """;

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final RowMapper<TestRequirementEvaluation> rowMapper = this::mapRow;

    public TestRequirementEvaluationRepository(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    public void upsert(
            UUID testUuid,
            int requirementId,
            RequirementEvaluationStatus status,
            Double measuredValue,
            String unit,
            JsonNode details,
            Instant evaluatedAt) {
        jdbcTemplate.update(
                UPSERT_SQL,
                testUuid,
                requirementId,
                status.name(),
                measuredValue,
                unit,
                details != null ? details.toString() : null,
                Timestamp.from(evaluatedAt));
    }

    public List<TestRequirementEvaluation> findByTest(UUID testUuid) {
        return jdbcTemplate.query(SELECT_BY_TEST_SQL, rowMapper, testUuid);
    }

    private TestRequirementEvaluation mapRow(ResultSet rs, int rowNum) throws SQLException {
        RequirementEvaluationStatus status =
                RequirementEvaluationStatus.fromDatabaseValue(rs.getString("status"));
        Double measuredValue = (Double) rs.getObject("measured_value");
        JsonNode details = parseDetails(rs.getObject("details"));
        Timestamp evaluatedAt = rs.getTimestamp("evaluated_at");
        return new TestRequirementEvaluation(
                rs.getLong("id"),
                rs.getObject("test_uuid", UUID.class),
                rs.getInt("requirement_id"),
                status,
                measuredValue,
                rs.getString("unit"),
                details,
                evaluatedAt != null ? evaluatedAt.toInstant() : null);
    }

    private JsonNode parseDetails(Object rawValue) throws SQLException {
        if (rawValue == null) {
            return null;
        }
        try {
            if (rawValue instanceof String stringValue) {
                if (stringValue.isBlank()) {
                    return null;
                }
                return objectMapper.readTree(stringValue);
            }
            return objectMapper.readTree(rawValue.toString());
        } catch (JsonProcessingException ex) {
            throw new SQLException("Unable to parse test requirement evaluation details JSON", ex);
        }
    }
}
