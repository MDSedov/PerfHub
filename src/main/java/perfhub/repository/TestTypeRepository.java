package perfhub.repository;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;
import org.springframework.util.StringUtils;

@Repository
public class TestTypeRepository {

    private static final String SELECT_ID_BY_VALUE_SQL =
            "SELECT id FROM test_types WHERE lower(test_type) = lower(?)";
    private static final String SELECT_BY_VALUE_SQL = """
            SELECT id,
                   test_type,
                   COALESCE(NULLIF(TRIM(display_name), ''), test_type) AS display_name
              FROM test_types
             WHERE LOWER(test_type) = LOWER(?)
            """;
    private static final String SELECT_ALL_SQL = """
            SELECT id,
                   test_type,
                   COALESCE(NULLIF(TRIM(display_name), ''), test_type) AS display_name
              FROM test_types
             ORDER BY CASE LOWER(test_type)
                        WHEN 'max' THEN 1
                        WHEN 'stability' THEN 2
                        WHEN 'soak' THEN 3
                        WHEN 'psu' THEN 4
                        WHEN 'dt' THEN 5
                        ELSE 6
                      END,
                      LOWER(COALESCE(NULLIF(TRIM(display_name), ''), test_type)),
                      LOWER(test_type),
                      id
            """;
    private static final String SELECT_BY_ID_SQL = """
            SELECT id,
                   test_type,
                   COALESCE(NULLIF(TRIM(display_name), ''), test_type) AS display_name
              FROM test_types
             WHERE id = ?
            """;
    private static final String EXISTS_CODE_SQL = """
            SELECT COUNT(*)
              FROM test_types
             WHERE LOWER(test_type) = LOWER(?)
               AND (? IS NULL OR id <> ?)
            """;
    private static final String INSERT_SQL = """
            INSERT INTO test_types (test_type, display_name, description)
            VALUES (?, ?, ?)
            """;
    private static final String UPDATE_SQL = """
            UPDATE test_types
               SET test_type = ?,
                   display_name = ?,
                   description = ?
             WHERE id = ?
            """;
    private static final String DELETE_SQL = "DELETE FROM test_types WHERE id = ?";

    private final JdbcTemplate jdbcTemplate;
    private final RowMapper<TestTypeRecord> rowMapper = TestTypeRepository::mapRow;

    public TestTypeRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public List<TestTypeRecord> findAll() {
        return jdbcTemplate.query(SELECT_ALL_SQL, rowMapper);
    }

    public Optional<TestTypeRecord> findById(int id) {
        return jdbcTemplate.query(SELECT_BY_ID_SQL, rowMapper, id).stream().findFirst();
    }

    public Optional<TestTypeRecord> findByValue(String value) {
        if (!StringUtils.hasText(value)) {
            return Optional.empty();
        }
        return jdbcTemplate.query(SELECT_BY_VALUE_SQL, rowMapper, value).stream().findFirst();
    }

    public Optional<Integer> findIdByValue(String value) {
        if (!StringUtils.hasText(value)) {
            return Optional.empty();
        }
        return jdbcTemplate.query(
                        SELECT_ID_BY_VALUE_SQL,
                        ps -> ps.setString(1, value),
                        (rs, rowNum) -> rs.getInt("id"))
                .stream()
                .findFirst();
    }

    public boolean existsCode(String code, Integer excludedId) {
        Integer count = jdbcTemplate.queryForObject(
                EXISTS_CODE_SQL,
                Integer.class,
                code,
                excludedId,
                excludedId);
        return count != null && count > 0;
    }

    public int create(String code, String name) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement statement = connection.prepareStatement(INSERT_SQL, new String[]{"id"});
            statement.setString(1, code);
            statement.setString(2, name);
            statement.setString(3, name);
            return statement;
        }, keyHolder);
        Number key = keyHolder.getKey();
        if (key == null) {
            throw new IllegalStateException("Не удалось создать тип теста");
        }
        return key.intValue();
    }

    public void update(int id, String code, String name) {
        int updated = jdbcTemplate.update(UPDATE_SQL, code, name, name, id);
        if (updated == 0) {
            throw new IllegalArgumentException("Тип теста не найден");
        }
    }

    public void delete(int id) {
        int updated = jdbcTemplate.update(DELETE_SQL, id);
        if (updated == 0) {
            throw new IllegalArgumentException("Тип теста не найден");
        }
    }

    private static TestTypeRecord mapRow(ResultSet rs, int rowNum) throws SQLException {
        return new TestTypeRecord(
                rs.getInt("id"),
                rs.getString("test_type"),
                rs.getString("display_name"));
    }

    public record TestTypeRecord(
            int id,
            String code,
            String name) {}
}
