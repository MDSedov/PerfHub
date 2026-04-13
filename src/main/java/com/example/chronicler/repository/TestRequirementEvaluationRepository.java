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

    private static final String INSERT_SQL = """
            INSERT INTO test_requirement_evaluations (
                test_uuid,
                dashboard_uid,
                panel_id,
                status,
                measured_value,
                unit,
                details,
                evaluated_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            """;

    private static final String UPDATE_SQL = """
            UPDATE test_requirement_evaluations
               SET status = ?,
                   measured_value = ?,
                   unit = ?,
                   details = ?,
                   evaluated_at = ?
             WHERE test_uuid = ?
               AND dashboard_uid = ?
               AND panel_id = ?
            """;

    private static final String SELECT_BY_TEST_SQL = """
            SELECT id,
                   test_uuid,
                   dashboard_uid,
                   panel_id,
                   status,
                   measured_value,
                   unit,
                   details,
                   evaluated_at
              FROM test_requirement_evaluations
             WHERE test_uuid = ?
             ORDER BY dashboard_uid, panel_id
            """;

    private static final String DELETE_BY_TEST_SQL = """
            DELETE FROM test_requirement_evaluations
             WHERE test_uuid = ?
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
            String dashboardUid,
            int panelId,
            RequirementEvaluationStatus status,
            Double measuredValue,
            String unit,
            JsonNode details,
            Instant evaluatedAt) {
        Timestamp evaluatedTimestamp = Timestamp.from(evaluatedAt);
        String detailsJson = details != null ? details.toString() : null;
        int updated = jdbcTemplate.update(
                UPDATE_SQL,
                status.name(),
                measuredValue,
                unit,
                detailsJson,
                evaluatedTimestamp,
                testUuid,
                dashboardUid,
                panelId);
        if (updated == 0) {
            jdbcTemplate.update(
                    INSERT_SQL,
                    testUuid,
                    dashboardUid,
                    panelId,
                    status.name(),
                    measuredValue,
                    unit,
                    detailsJson,
                    evaluatedTimestamp);
        }
    }

    public List<TestRequirementEvaluation> findByTest(UUID testUuid) {
        return jdbcTemplate.query(SELECT_BY_TEST_SQL, rowMapper, testUuid);
    }

    public void deleteByTest(UUID testUuid) {
        jdbcTemplate.update(DELETE_BY_TEST_SQL, testUuid);
    }

    private TestRequirementEvaluation mapRow(ResultSet rs, int rowNum) throws SQLException {
        RequirementEvaluationStatus status =
                RequirementEvaluationStatus.fromDatabaseValue(rs.getString("status"));
        Double measuredValue = (Double) rs.getObject("measured_value");
        JsonNode details = parseDetails(rs.getString("details"));
        Timestamp evaluatedAt = rs.getTimestamp("evaluated_at");
        return new TestRequirementEvaluation(
                rs.getLong("id"),
                rs.getObject("test_uuid", UUID.class),
                rs.getString("dashboard_uid"),
                rs.getInt("panel_id"),
                status,
                measuredValue,
                rs.getString("unit"),
                details,
                evaluatedAt != null ? evaluatedAt.toInstant() : null);
    }

    private JsonNode parseDetails(String rawValue) throws SQLException {
        try {
            if (rawValue == null || rawValue.isBlank()) {
                return null;
            }
            return objectMapper.readTree(rawValue);
        } catch (JsonProcessingException ex) {
            throw new SQLException("Unable to parse test requirement evaluation details JSON", ex);
        }
    }
}
