package com.example.chronicler.repository;

import com.example.chronicler.model.ConfluenceTemplate;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

@Repository
public class ConfluenceTemplateRepository {

    private static final String BASE_SELECT = """
            SELECT id,
                   short_name,
                   full_name,
                   report_title,
                   body
              FROM confluence_report_templates
             WHERE deleted_at IS NULL
            """;
    private static final String SELECT_ALL_SQL = BASE_SELECT + " ORDER BY short_name, full_name";
    private static final String SELECT_BY_ID_SQL = BASE_SELECT + " AND id = ?";
    private static final String SELECT_BY_SHORT_NAME_SQL = BASE_SELECT + " AND LOWER(short_name) = LOWER(?)";
    private static final String SELECT_LEGACY_TEMPLATE_BODY_SQL =
            "SELECT text FROM confluence_templates WHERE name = ?";
    private static final String INSERT_SQL =
            "INSERT INTO confluence_report_templates (short_name, full_name, report_title, body) VALUES (?, ?, ?, ?)";
    private static final String UPDATE_SQL =
            "UPDATE confluence_report_templates SET short_name = ?, full_name = ?, report_title = ?, body = ? WHERE id = ? AND deleted_at IS NULL";
    private static final String SOFT_DELETE_SQL =
            "UPDATE confluence_report_templates SET deleted_at = CURRENT_TIMESTAMP WHERE id = ? AND deleted_at IS NULL";
    private static final String EXISTS_ACTIVE_SHORT_NAME_SQL = """
            SELECT COUNT(*)
              FROM confluence_report_templates
             WHERE deleted_at IS NULL
               AND LOWER(short_name) = LOWER(?)
               AND (? IS NULL OR id <> ?)
            """;
    private static final String EXISTS_ACTIVE_FULL_NAME_SQL = """
            SELECT COUNT(*)
              FROM confluence_report_templates
             WHERE deleted_at IS NULL
               AND LOWER(full_name) = LOWER(?)
               AND (? IS NULL OR id <> ?)
            """;

    private final JdbcTemplate jdbcTemplate;
    private final RowMapper<ConfluenceTemplate> rowMapper = (rs, rowNum) -> new ConfluenceTemplate(
            rs.getInt("id"),
            rs.getString("short_name"),
            rs.getString("full_name"),
            rs.getString("report_title"),
            rs.getString("body"),
            List.of());

    public ConfluenceTemplateRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public List<ConfluenceTemplate> findAllActive() {
        return jdbcTemplate.query(SELECT_ALL_SQL, rowMapper);
    }

    public Optional<String> findTemplateBody(String templateName) {
        return jdbcTemplate.query(
                        SELECT_LEGACY_TEMPLATE_BODY_SQL,
                        ps -> ps.setString(1, templateName),
                        (rs, rowNum) -> rs.getString("text"))
                .stream()
                .findFirst();
    }

    public Optional<ConfluenceTemplate> findActiveById(int id) {
        return jdbcTemplate.query(SELECT_BY_ID_SQL, rowMapper, id).stream().findFirst();
    }

    public Optional<ConfluenceTemplate> findActiveByShortName(String shortName) {
        return jdbcTemplate.query(SELECT_BY_SHORT_NAME_SQL, rowMapper, shortName).stream().findFirst();
    }

    public int create(String shortName, String fullName, String reportTitle, String body) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement statement =
                    connection.prepareStatement(INSERT_SQL, Statement.RETURN_GENERATED_KEYS);
            statement.setString(1, shortName);
            statement.setString(2, fullName);
            statement.setString(3, reportTitle);
            statement.setString(4, body);
            return statement;
        }, keyHolder);
        Number key = keyHolder.getKey();
        if (key == null) {
            throw new IllegalStateException("Failed to create report template");
        }
        return key.intValue();
    }

    public void update(int id, String shortName, String fullName, String reportTitle, String body) {
        int updated = jdbcTemplate.update(UPDATE_SQL, shortName, fullName, reportTitle, body, id);
        if (updated == 0) {
            throw new IllegalArgumentException("Шаблон отчета не найден");
        }
    }

    public void softDelete(int id) {
        int updated = jdbcTemplate.update(SOFT_DELETE_SQL, id);
        if (updated == 0) {
            throw new IllegalArgumentException("Шаблон отчета не найден");
        }
    }

    public boolean existsActiveByShortName(String shortName, Integer excludedId) {
        Integer count = jdbcTemplate.queryForObject(
                EXISTS_ACTIVE_SHORT_NAME_SQL,
                Integer.class,
                shortName,
                excludedId,
                excludedId);
        return count != null && count > 0;
    }

    public boolean existsActiveByFullName(String fullName, Integer excludedId) {
        Integer count = jdbcTemplate.queryForObject(
                EXISTS_ACTIVE_FULL_NAME_SQL,
                Integer.class,
                fullName,
                excludedId,
                excludedId);
        return count != null && count > 0;
    }
}
