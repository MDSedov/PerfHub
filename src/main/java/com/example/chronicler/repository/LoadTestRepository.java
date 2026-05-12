package com.example.chronicler.repository;

import com.example.chronicler.model.LoadTest;
import com.example.chronicler.model.LoadTestType;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.IncorrectResultSizeDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
public class LoadTestRepository {

    private static final String BASE_SELECT =
            "SELECT t.test_uuid, t.start_time, t.end_time, tt.test_type AS test_type, "
                    + "t.comment, t.report_status, t.report_summary "
                    + "FROM tests t "
                    + "LEFT JOIN test_types tt ON t.test_type_id = tt.id ";
    private static final String SELECT_OVERLAP_SQL =
            BASE_SELECT
                    + "WHERE t.start_time <= ? AND t.end_time >= ? "
                    + "ORDER BY t.start_time LIMIT 1";
    private static final String SELECT_BY_ID_SQL = BASE_SELECT + "WHERE t.test_uuid = ?";
    private static final String SELECT_ALL_ORDERED_SQL =
            BASE_SELECT + "ORDER BY t.start_time DESC";
    private static final String INSERT_SQL =
            "INSERT INTO tests (test_uuid, start_time, end_time, test_type_id, comment) VALUES (?, ?, ?, ?, ?)";
    private static final String UPDATE_SQL =
            "UPDATE tests SET start_time = ?, end_time = ?, test_type_id = ? WHERE test_uuid = ?";
    private static final String UPDATE_TYPE_SQL = "UPDATE tests SET test_type_id = ? WHERE test_uuid = ?";
    private static final String UPDATE_COMMENT_SQL = "UPDATE tests SET comment = ? WHERE test_uuid = ?";
    private static final String UPDATE_REPORT_DETAILS_SQL =
            "UPDATE tests SET report_status = ?, report_summary = ? WHERE test_uuid = ?";

    private final JdbcTemplate jdbcTemplate;
    private final TestTypeRepository testTypeRepository;
    private final RowMapper<LoadTest> rowMapper =
            (rs, rowNum) -> new LoadTest(
                    rs.getObject("test_uuid", UUID.class),
                    rs.getTimestamp("start_time").toInstant(),
                    rs.getTimestamp("end_time").toInstant(),
                    LoadTestType.fromDatabaseValue(rs.getString("test_type")),
                    rs.getString("comment"),
                    rs.getString("report_status"),
                    rs.getString("report_summary"));

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
        UUID testId = UUID.randomUUID();
        jdbcTemplate.update(
                INSERT_SQL,
                ps -> {
                    ps.setObject(1, testId);
                    ps.setTimestamp(2, Timestamp.from(start));
                    ps.setTimestamp(3, Timestamp.from(end));
                    if (testTypeId == null) {
                        ps.setNull(4, Types.INTEGER);
                    } else {
                        ps.setInt(4, testTypeId);
                    }
                    ps.setObject(1, testId);
                    ps.setTimestamp(2, Timestamp.from(start));
                    ps.setTimestamp(3, Timestamp.from(end));
                    if (testTypeId == null) {
                        ps.setNull(4, Types.INTEGER);
                    } else {
                        ps.setInt(4, testTypeId);
                    }
                    ps.setNull(5, Types.CLOB);
                });
        return findById(testId)
                .orElseThrow(() -> new IllegalStateException("Failed to load inserted test " + testId));
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
        int updated =
                jdbcTemplate.update(
                        UPDATE_SQL,
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
        if (updated == 0) {
            throw new IncorrectResultSizeDataAccessException("Expected to update one row", 1, 0);
        }
        return findById(id).orElseThrow(() -> new IllegalStateException("Failed to load updated test " + id));
    }

    public LoadTest updateComment(UUID id, String comment) {
        int updated = jdbcTemplate.update(
                UPDATE_COMMENT_SQL,
                ps -> {
                    if (comment == null) {
                        ps.setNull(1, Types.CLOB);
                    } else {
                        ps.setString(1, comment);
                    }
                    ps.setObject(2, id);
                });
        if (updated == 0) {
            throw new IncorrectResultSizeDataAccessException("Expected to update one row", 1, 0);
        }
        return findById(id).orElseThrow(() -> new IllegalStateException("Failed to load updated test " + id));
    }

    public LoadTest updateReportDetails(UUID id, String reportStatus, String reportSummary) {
        int updated = jdbcTemplate.update(
                UPDATE_REPORT_DETAILS_SQL,
                ps -> {
                    if (reportStatus == null) {
                        ps.setNull(1, Types.CLOB);
                    } else {
                        ps.setString(1, reportStatus);
                    }
                    if (reportSummary == null) {
                        ps.setNull(2, Types.CLOB);
                    } else {
                        ps.setString(2, reportSummary);
                    }
                    ps.setObject(3, id);
                });
        if (updated == 0) {
            throw new IncorrectResultSizeDataAccessException("Expected to update one row", 1, 0);
        }
        return findById(id).orElseThrow(() -> new IllegalStateException("Failed to load updated test " + id));
    }

    public LoadTest updateType(UUID id, LoadTestType type) {
        Integer testTypeId = resolveTestTypeId(type);
        int updated = jdbcTemplate.update(
                UPDATE_TYPE_SQL,
                ps -> {
                    if (testTypeId == null) {
                        ps.setNull(1, Types.INTEGER);
                    } else {
                        ps.setInt(1, testTypeId);
                    }
                    ps.setObject(2, id);
                });
        if (updated == 0) {
            throw new IncorrectResultSizeDataAccessException("Expected to update one row", 1, 0);
        }
        return findById(id).orElseThrow(() -> new IllegalStateException("Failed to load updated test " + id));
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

}
