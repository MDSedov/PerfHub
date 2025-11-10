package com.example.chronicler.repository;

import com.example.chronicler.model.LoadTest;
import com.example.chronicler.model.LoadTestType;
import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.IncorrectResultSizeDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.PreparedStatementSetter;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
public class LoadTestRepository {

    private static final String BASE_SELECT =
            "SELECT t.test_uuid, t.start_time, t.end_time, tt.test_type AS test_type "
                    + "FROM tests t "
                    + "LEFT JOIN test_types tt ON t.test_type_id = tt.id ";
    private static final String SELECT_OVERLAP_SQL =
            BASE_SELECT
                    + "WHERE t.start_time <= ? AND t.end_time >= ? "
                    + "ORDER BY t.start_time LIMIT 1";
    private static final String SELECT_BY_ID_SQL = BASE_SELECT + "WHERE t.test_uuid = ?";
    private static final String SELECT_ALL_ORDERED_SQL =
            BASE_SELECT + "ORDER BY t.start_time DESC";
    private static final String INSERT_RETURNING_SQL =
            "INSERT INTO tests (start_time, end_time, test_type_id) VALUES (?, ?, ?) "
                    + "RETURNING test_uuid, start_time, end_time, "
                    + "(SELECT test_type FROM test_types WHERE id = tests.test_type_id) AS test_type";
    private static final String UPDATE_RETURNING_SQL =
            "UPDATE tests SET start_time = ?, end_time = ?, test_type_id = ? WHERE test_uuid = ? "
                    + "RETURNING test_uuid, start_time, end_time, "
                    + "(SELECT test_type FROM test_types WHERE id = tests.test_type_id) AS test_type";

    private final JdbcTemplate jdbcTemplate;
    private final TestTypeRepository testTypeRepository;
    private final RowMapper<LoadTest> rowMapper =
            (rs, rowNum) -> new LoadTest(
                    rs.getObject("test_uuid", UUID.class),
                    rs.getTimestamp("start_time").toInstant(),
                    rs.getTimestamp("end_time").toInstant(),
                    LoadTestType.fromDatabaseValue(rs.getString("test_type")));

    public LoadTestRepository(JdbcTemplate jdbcTemplate, TestTypeRepository testTypeRepository) {
        this.jdbcTemplate = jdbcTemplate;
        this.testTypeRepository = testTypeRepository;
    }

    public Optional<LoadTest> findOverlap(Instant start, Instant end) {
        return jdbcTemplate.query(
                        SELECT_OVERLAP_SQL,
                        ps -> {
                            ps.setTimestamp(1, Timestamp.from(end));
                            ps.setTimestamp(2, Timestamp.from(start));
                        },
                        rowMapper)
                .stream()
                .findFirst();
    }

    public LoadTest insert(Instant start, Instant end, LoadTestType type) {
        Integer testTypeId = resolveTestTypeId(type);
        return executeReturningStatement(
                INSERT_RETURNING_SQL,
                ps -> {
                    ps.setTimestamp(1, Timestamp.from(start));
                    ps.setTimestamp(2, Timestamp.from(end));
                    if (testTypeId == null) {
                        ps.setNull(3, Types.INTEGER);
                    } else {
                        ps.setInt(3, testTypeId);
                    }
                });
    }

    public Optional<LoadTest> findById(UUID id) {
        return jdbcTemplate.query(
                        SELECT_BY_ID_SQL,
                        ps -> ps.setObject(1, id),
                        rowMapper)
                .stream()
                .findFirst();
    }

    public LoadTest update(UUID id, Instant start, Instant end, LoadTestType type) {
        Integer testTypeId = resolveTestTypeId(type);
        return executeReturningStatement(
                UPDATE_RETURNING_SQL,
                ps -> {
                    ps.setTimestamp(1, Timestamp.from(start));
                    ps.setTimestamp(2, Timestamp.from(end));
                    if (testTypeId == null) {
                        ps.setNull(3, Types.INTEGER);
                    } else {
                        ps.setInt(3, testTypeId);
                    }
                    ps.setObject(4, id);
                });
    }

    public List<LoadTest> findAllOrderByStartDesc() {
        return jdbcTemplate.query(SELECT_ALL_ORDERED_SQL, rowMapper);
    }

    public LoadTest saveOrUpdate(Instant start, Instant end, LoadTestType type) {
        Optional<LoadTest> existingOpt = findOverlap(start, end);
        if (existingOpt.isPresent()) {
            LoadTest existing = existingOpt.get();
            Instant newStart = start.isBefore(existing.start()) ? start : existing.start();
            Instant newEnd = end.isAfter(existing.end()) ? end : existing.end();
            return update(existing.id(), newStart, newEnd, type);
        }
        return insert(start, end, type);
    }

    private Integer resolveTestTypeId(LoadTestType type) {
        if (type == null || type == LoadTestType.UNKNOWN) {
            return null;
        }
        return testTypeRepository.findIdByValue(type.value())
                .orElseThrow(() -> new IllegalStateException(
                        "Test type '" + type.value() + "' is not configured in test_types table"));
    }

    private LoadTest executeReturningStatement(String sql, PreparedStatementSetter setter) {
        return jdbcTemplate.query(
                connection -> {
                    PreparedStatement preparedStatement = connection.prepareStatement(sql);
                    setter.setValues(preparedStatement);
                    return preparedStatement;
                },
                rs -> {
                    if (rs.next()) {
                        return rowMapper.mapRow(rs, 1);
                    }
                    throw new IncorrectResultSizeDataAccessException("Expected one row", 1, 0);
                });
    }
}
