package com.example.chronicler.repository;

import com.example.chronicler.model.ConfluenceTemplate;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
public class ConfluenceTemplateRepository {

    private static final String SELECT_TEMPLATE_SQL =
            "SELECT text FROM confluence_templates WHERE name = ?";
    private static final String SELECT_ALL_SQL = """
            SELECT ct.id,
                   ct.name,
                   ct.text,
                   tt.test_type
              FROM confluence_templates ct
              JOIN test_types tt ON tt.id = ct.test_type_id
             ORDER BY tt.test_type, ct.name
            """;
    private static final String UPDATE_TEMPLATE_SQL =
            "UPDATE confluence_templates SET text = ? WHERE id = ?";

    private final JdbcTemplate jdbcTemplate;
    private final RowMapper<ConfluenceTemplate> rowMapper = (rs, rowNum) -> new ConfluenceTemplate(
            rs.getInt("id"),
            rs.getString("name"),
            rs.getString("test_type"),
            rs.getString("text"));

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

    public List<ConfluenceTemplate> findAllTemplates() {
        return jdbcTemplate.query(SELECT_ALL_SQL, rowMapper);
    }

    public void updateTemplateText(int id, String text) {
        jdbcTemplate.update(UPDATE_TEMPLATE_SQL, text, id);
    }
}
