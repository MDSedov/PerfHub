package com.example.chronicler.repository;

import com.example.chronicler.model.ApplicationUser;
import com.example.chronicler.model.UserRole;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

@Repository
public class ApplicationUserRepository {

    private static final String SELECT_ACTIVE_BASE = """
            SELECT id,
                   username,
                   first_name,
                   last_name,
                   role,
                   password_hash,
                   deleted_at,
                   created_at,
                   updated_at
              FROM app_users
             WHERE deleted_at IS NULL
            """;
    private static final String SELECT_ALL_ACTIVE_SQL =
            SELECT_ACTIVE_BASE + " ORDER BY LOWER(username), id";
    private static final String SELECT_BY_ID_SQL =
            SELECT_ACTIVE_BASE + " AND id = ?";
    private static final String SELECT_BY_USERNAME_SQL =
            SELECT_ACTIVE_BASE + " AND LOWER(username) = LOWER(?) ORDER BY id DESC";
    private static final String EXISTS_ACTIVE_USERNAME_SQL = """
            SELECT COUNT(*)
              FROM app_users
             WHERE deleted_at IS NULL
               AND LOWER(username) = LOWER(?)
               AND (? IS NULL OR id <> ?)
            """;
    private static final String EXISTS_ACTIVE_ADMIN_SQL = """
            SELECT COUNT(*)
              FROM app_users
             WHERE deleted_at IS NULL
               AND role = 'ADMIN'
            """;
    private static final String INSERT_SQL = """
            INSERT INTO app_users (username, first_name, last_name, role, password_hash)
            VALUES (?, ?, ?, ?, ?)
            """;
    private static final String UPDATE_WITH_PASSWORD_SQL = """
            UPDATE app_users
               SET username = ?,
                   first_name = ?,
                   last_name = ?,
                   role = ?,
                   password_hash = ?,
                   updated_at = CURRENT_TIMESTAMP
             WHERE id = ?
               AND deleted_at IS NULL
            """;
    private static final String UPDATE_WITHOUT_PASSWORD_SQL = """
            UPDATE app_users
               SET username = ?,
                   first_name = ?,
                   last_name = ?,
                   role = ?,
                   updated_at = CURRENT_TIMESTAMP
             WHERE id = ?
               AND deleted_at IS NULL
            """;
    private static final String SOFT_DELETE_SQL = """
            UPDATE app_users
               SET deleted_at = CURRENT_TIMESTAMP,
                   updated_at = CURRENT_TIMESTAMP
             WHERE id = ?
               AND deleted_at IS NULL
            """;

    private final JdbcTemplate jdbcTemplate;
    private final RowMapper<ApplicationUser> rowMapper = ApplicationUserRepository::mapRow;

    public ApplicationUserRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public List<ApplicationUser> findAllActive() {
        return jdbcTemplate.query(SELECT_ALL_ACTIVE_SQL, rowMapper);
    }

    public Optional<ApplicationUser> findActiveById(long id) {
        return jdbcTemplate.query(SELECT_BY_ID_SQL, rowMapper, id).stream().findFirst();
    }

    public Optional<ApplicationUser> findActiveByUsername(String username) {
        return jdbcTemplate.query(SELECT_BY_USERNAME_SQL, rowMapper, username).stream().findFirst();
    }

    public boolean existsActiveUsername(String username, Long excludedId) {
        Integer count = jdbcTemplate.queryForObject(
                EXISTS_ACTIVE_USERNAME_SQL,
                Integer.class,
                username,
                excludedId,
                excludedId);
        return count != null && count > 0;
    }

    public boolean existsActiveAdmin() {
        Integer count = jdbcTemplate.queryForObject(EXISTS_ACTIVE_ADMIN_SQL, Integer.class);
        return count != null && count > 0;
    }

    public long create(
            String username,
            String firstName,
            String lastName,
            UserRole role,
            String passwordHash) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement statement =
                    connection.prepareStatement(INSERT_SQL, new String[]{"id"});
            statement.setString(1, username);
            statement.setString(2, firstName);
            statement.setString(3, lastName);
            statement.setString(4, role.name());
            statement.setString(5, passwordHash);
            return statement;
        }, keyHolder);
        Number key = keyHolder.getKey();
        if (key == null) {
            throw new IllegalStateException("Не удалось создать пользователя");
        }
        return key.longValue();
    }

    public void update(
            long id,
            String username,
            String firstName,
            String lastName,
            UserRole role,
            String passwordHash) {
        int updated = passwordHash == null
                ? jdbcTemplate.update(UPDATE_WITHOUT_PASSWORD_SQL, username, firstName, lastName, role.name(), id)
                : jdbcTemplate.update(UPDATE_WITH_PASSWORD_SQL, username, firstName, lastName, role.name(), passwordHash, id);
        if (updated == 0) {
            throw new IllegalArgumentException("Пользователь не найден");
        }
    }

    public void softDelete(long id) {
        int updated = jdbcTemplate.update(SOFT_DELETE_SQL, id);
        if (updated == 0) {
            throw new IllegalArgumentException("Пользователь не найден");
        }
    }

    private static ApplicationUser mapRow(ResultSet rs, int rowNum) throws SQLException {
        return new ApplicationUser(
                rs.getLong("id"),
                rs.getString("username"),
                rs.getString("first_name"),
                rs.getString("last_name"),
                UserRole.fromValue(rs.getString("role")),
                rs.getString("password_hash"),
                toLocalDateTime(rs.getTimestamp("deleted_at")),
                toLocalDateTime(rs.getTimestamp("created_at")),
                toLocalDateTime(rs.getTimestamp("updated_at")));
    }

    private static LocalDateTime toLocalDateTime(Timestamp value) {
        return value == null ? null : value.toLocalDateTime();
    }
}
