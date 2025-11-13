package com.example.chronicler.repository;

import com.example.chronicler.model.VictoriaMetricsQuery;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
public class VictoriaMetricsQueryRepository {

    private static final String SELECT_MAP_SQL = """
            SELECT code, expression
              FROM victoria_metrics_queries
             ORDER BY id
            """;
    private static final String SELECT_DETAILED_SQL = """
            SELECT id, code, expression, description
              FROM victoria_metrics_queries
             ORDER BY id
            """;
    private static final String INSERT_SQL = """
            INSERT INTO victoria_metrics_queries (code, expression, description)
            VALUES (?, ?, ?)
            RETURNING id, code, expression, description
            """;
    private static final String UPDATE_SQL = """
            UPDATE victoria_metrics_queries
               SET code = ?,
                   expression = ?,
                   description = ?,
                   updated_at = NOW()
             WHERE id = ?
         RETURNING id, code, expression, description
            """;
    private static final String DELETE_SQL = "DELETE FROM victoria_metrics_queries WHERE id = ?";

    private final JdbcTemplate jdbcTemplate;
    private final RowMapper<VictoriaMetricsQuery> rowMapper = (rs, rowNum) -> new VictoriaMetricsQuery(
            rs.getLong("id"),
            rs.getString("code"),
            rs.getString("expression"),
            rs.getString("description"));

    public VictoriaMetricsQueryRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public Map<String, String> findAll() {
        Map<String, String> queries = new LinkedHashMap<>();
        jdbcTemplate.query(SELECT_MAP_SQL, (RowCallbackHandler) rs -> queries.put(
                rs.getString("code"),
                rs.getString("expression")));
        return queries;
    }

    public List<VictoriaMetricsQuery> findAllDetailed() {
        return jdbcTemplate.query(SELECT_DETAILED_SQL, rowMapper);
    }

    public VictoriaMetricsQuery insert(String code, String expression, String description) {
        return jdbcTemplate.query(
                        INSERT_SQL,
                        ps -> {
                            ps.setString(1, code);
                            ps.setString(2, expression);
                            ps.setString(3, description);
                        },
                        rowMapper)
                .stream()
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("Failed to insert VictoriaMetrics query"));
    }

    public VictoriaMetricsQuery update(long id, String code, String expression, String description) {
        return jdbcTemplate.query(
                        UPDATE_SQL,
                        ps -> {
                            ps.setString(1, code);
                            ps.setString(2, expression);
                            ps.setString(3, description);
                            ps.setLong(4, id);
                        },
                        rowMapper)
                .stream()
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("Failed to update VictoriaMetrics query " + id));
    }

    public void delete(long id) {
        jdbcTemplate.update(DELETE_SQL, id);
    }
}
