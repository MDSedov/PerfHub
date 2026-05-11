package com.example.chronicler.repository;

import com.example.chronicler.model.ConfigurationEntry;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
public class ConfigurationRepository {

    private static final Set<String> CONNECTION_SERVICES = Set.of(
            "victoria-metrics",
            "grafana",
            "dropapp",
            "confluence",
            "gigachat");
    private static final String CONNECTION_SERVICES_SQL =
            "'victoria-metrics', 'grafana', 'dropapp', 'confluence', 'gigachat'";
    private static final String SELECT_ALL_SQL = """
            SELECT c.id,
                   c.service,
                   c."key",
                   CASE
                       WHEN c.service IN (%s) AND ? IS NOT NULL THEN COALESCE(uc.config_value, '')
                       ELSE c.config_value
                   END AS config_value,
                   c.description,
                   c.sort_order
              FROM configurations c
              LEFT JOIN user_configurations uc
                ON uc.user_id = ?
               AND uc.service = c.service
               AND uc."key" = c."key"
             ORDER BY c.service, c.sort_order NULLS LAST, c."key"
            """;
    private static final String SELECT_ONE_SQL =
            "SELECT config_value FROM configurations WHERE service = ? AND \"key\" = ?";
    private static final String SELECT_ONE_FOR_USER_SQL = """
            SELECT COALESCE(uc.config_value, '') AS config_value
              FROM configurations c
              LEFT JOIN user_configurations uc
                ON uc.user_id = ?
               AND uc.service = c.service
               AND uc."key" = c."key"
             WHERE c.service = ?
               AND c."key" = ?
            """;
    private static final String INSERT_SQL =
            "INSERT INTO configurations (service, \"key\", config_value, sort_order) VALUES (?, ?, ?, ?)";
    private static final String UPDATE_SQL =
            "UPDATE configurations SET config_value = ? WHERE service = ? AND \"key\" = ?";
    private static final String INSERT_USER_SQL = """
            INSERT INTO user_configurations (user_id, service, "key", config_value)
            VALUES (?, ?, ?, ?)
            """;
    private static final String UPDATE_USER_SQL = """
            UPDATE user_configurations
               SET config_value = ?,
                   updated_at = CURRENT_TIMESTAMP
             WHERE user_id = ?
               AND service = ?
               AND "key" = ?
            """;
    private static final String COPY_GLOBAL_CONNECTIONS_TO_USER_SQL = """
            INSERT INTO user_configurations (user_id, service, "key", config_value)
            SELECT ?, c.service, c."key", c.config_value
              FROM configurations c
             WHERE c.service IN (%s)
               AND LENGTH(c.config_value) > 0
               AND NOT EXISTS (
                   SELECT 1
                     FROM user_configurations uc
                    WHERE uc.user_id = ?
                      AND uc.service = c.service
                      AND uc."key" = c."key"
               )
            """;

    private final JdbcTemplate jdbcTemplate;
    private final RowMapper<ConfigurationEntry> rowMapper = ConfigurationRepository::mapRow;

    public ConfigurationRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public List<ConfigurationEntry> findAllOrdered() {
        return findAllOrdered(null);
    }

    public List<ConfigurationEntry> findAllOrdered(Long userId) {
        return jdbcTemplate.query(SELECT_ALL_SQL.formatted(CONNECTION_SERVICES_SQL), rowMapper, userId, userId);
    }

    public Optional<String> findValue(String service, String key) {
        return findValue(service, key, null);
    }

    public Optional<String> findValue(String service, String key, Long userId) {
        if (isConnectionService(service) && userId != null) {
            List<String> results = jdbcTemplate.query(
                    SELECT_ONE_FOR_USER_SQL,
                    (rs, rowNum) -> rs.getString("config_value"),
                    userId,
                    service,
                    key);
            if (results.isEmpty()) {
                return Optional.empty();
            }
            return Optional.ofNullable(results.get(0));
        }
        List<String> results = jdbcTemplate.query(
                SELECT_ONE_SQL,
                (rs, rowNum) -> rs.getString("config_value"),
                service,
                key);
        if (results.isEmpty()) {
            return Optional.empty();
        }
        return Optional.ofNullable(results.get(0));
    }

    public void upsert(String service, String key, String value) {
        upsert(service, key, value, null);
    }

    public void upsert(String service, String key, String value, Long userId) {
        if (isConnectionService(service) && userId != null) {
            upsertForUser(userId, service, key, value);
            return;
        }
        int updated = jdbcTemplate.update(UPDATE_SQL, value, service, key);
        if (updated == 0) {
            jdbcTemplate.update(INSERT_SQL, service, key, value, null);
        }
    }

    public void copyGlobalConnectionValuesToUser(long userId) {
        jdbcTemplate.update(
                COPY_GLOBAL_CONNECTIONS_TO_USER_SQL.formatted(CONNECTION_SERVICES_SQL),
                userId,
                userId);
    }

    public boolean isConnectionService(String service) {
        return CONNECTION_SERVICES.contains(service);
    }

    private void upsertForUser(long userId, String service, String key, String value) {
        int updated = jdbcTemplate.update(UPDATE_USER_SQL, value, userId, service, key);
        if (updated == 0) {
            jdbcTemplate.update(INSERT_USER_SQL, userId, service, key, value);
        }
    }

    private static ConfigurationEntry mapRow(ResultSet rs, int rowNum) throws SQLException {
        Number idValue = (Number) rs.getObject("id");
        Long id = idValue != null ? idValue.longValue() : null;
        Number orderValue = (Number) rs.getObject("sort_order");
        Integer sortOrder = orderValue != null ? orderValue.intValue() : null;
        return new ConfigurationEntry(
                id,
                rs.getString("service"),
                rs.getString("key"),
                rs.getString("config_value"),
                rs.getString("description"),
                sortOrder);
    }
}
