package com.example.chronicler.repository;

import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class ConfluenceTemplateRepository {

    private static final String SELECT_TEMPLATE_SQL =
            "SELECT text FROM confluence_templates WHERE name = ?";

    private final JdbcTemplate jdbcTemplate;

    public ConfluenceTemplateRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public Optional<String> findTemplateBody(String templateName) {
        return jdbcTemplate.query(
                        SELECT_TEMPLATE_SQL,
                        ps -> ps.setString(1, templateName),
                        (rs, rowNum) -> rs.getString("text"))
                .stream()
                .findFirst();
    }
}
