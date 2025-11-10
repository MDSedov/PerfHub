package com.example.chronicler.repository;

import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.stereotype.Repository;

@Repository
public class VictoriaMetricsQueryRepository {

    private static final String SELECT_ALL_SQL = """
            SELECT code, expression
              FROM victoria_metrics_queries
             ORDER BY id
            """;

    private final JdbcTemplate jdbcTemplate;

    public VictoriaMetricsQueryRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public Map<String, String> findAll() {
        Map<String, String> queries = new LinkedHashMap<>();
        jdbcTemplate.query(SELECT_ALL_SQL, (RowCallbackHandler) rs -> queries.put(
                rs.getString("code"),
                rs.getString("expression")));
        return queries;
    }
}
