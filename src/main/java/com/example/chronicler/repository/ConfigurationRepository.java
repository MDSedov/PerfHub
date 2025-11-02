package com.example.chronicler.repository;

import com.example.chronicler.model.ConfigurationEntry;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
public class ConfigurationRepository {

    private static final String SELECT_ALL_SQL =
            "SELECT id, service, \"key\", value FROM configurations ORDER BY service, \"key\"";
    private static final String SELECT_ONE_SQL =
            "SELECT value FROM configurations WHERE service = ? AND \"key\" = ?";
    private static final String UPSERT_SQL = """
            INSERT INTO configurations (service, "key", value)
            VALUES (?, ?, ?)
            ON CONFLICT (service, "key") DO UPDATE SET value = EXCLUDED.value
            """;

    private final JdbcTemplate jdbcTemplate;
    private final RowMapper<ConfigurationEntry> rowMapper = ConfigurationRepository::mapRow;

    public ConfigurationRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public List<ConfigurationEntry> findAllOrdered() {
        return jdbcTemplate.query(SELECT_ALL_SQL, rowMapper);
    }

    public Optional<String> findValue(String service, String key) {
        List<String> results = jdbcTemplate.query(SELECT_ONE_SQL, (rs, rowNum) -> rs.getString("value"), service, key);
        if (results.isEmpty()) {
            return Optional.empty();
        }
        return Optional.ofNullable(results.get(0));
    }

    public void upsert(String service, String key, String value) {
        jdbcTemplate.update(UPSERT_SQL, service, key, value);
    }

    private static ConfigurationEntry mapRow(ResultSet rs, int rowNum) throws SQLException {
        Number idValue = (Number) rs.getObject("id");
        Long id = idValue != null ? idValue.longValue() : null;
        return new ConfigurationEntry(id, rs.getString("service"), rs.getString("key"), rs.getString("value"));
    }
}
