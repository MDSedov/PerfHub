package com.example.chronicler.repository;

import com.example.chronicler.model.LoadTest;
import com.example.chronicler.model.LoadTestType;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Duration;
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

    private static final Duration MERGE_GAP_THRESHOLD = Duration.ofMinutes(3);
    private static final String BASE_SELECT =
            "SELECT t.test_uuid, t.start_time, t.end_time, tt.test_type AS test_type, "
                    + "t.comment, t.report_status, t.report_summary "
                    + "FROM tests t "
                    + "LEFT JOIN test_types tt ON t.test_type_id = tt.id ";
    private static final String SELECT_OVERLAP_SQL =
            BASE_SELECT
                    + "WHERE t.start_time <= ? AND t.end_time >= ? "
                    + "ORDER BY t.start_time LIMIT 1";
    private static final String SELECT_MERGE_CANDIDATES_SQL =
            BASE_SELECT
                    + "WHERE t.start_time <= ? AND t.end_time >= ? "
                    + "ORDER BY t.start_time";
    private static final String SELECT_BY_ID_SQL = BASE_SELECT + "WHERE t.test_uuid = ?";
    private static final String SELECT_ALL_ORDERED_SQL =
            BASE_SELECT + "ORDER BY t.start_time DESC";
    private static final String INSERT_SQL =
            "INSERT INTO tests (test_uuid, start_time, end_time, test_type_id, comment) VALUES (?, ?, ?, ?, ?)";
    private static final String UPDATE_SQL =
            "UPDATE tests SET start_time = ?, end_time = ?, test_type_id = ? WHERE test_uuid = ?";
    private static final String DELETE_BY_ID_SQL = "DELETE FROM tests WHERE test_uuid = ?";
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

    public List<LoadTest> findMergeCandidates(Instant start, Instant end, Duration mergeGap) {
        Duration safeMergeGap = mergeGap == null ? Duration.ZERO : mergeGap;
        Instant searchStart = start.minus(safeMergeGap);
        Instant searchEnd = end.plus(safeMergeGap);
        return jdbcTemplate.query(
                SELECT_MERGE_CANDIDATES_SQL,
                ps -> {
                    ps.setTimestamp(1, Timestamp.from(searchEnd));
                    ps.setTimestamp(2, Timestamp.from(searchStart));
                },
                rowMapper);
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
        List<LoadTest> candidates = findMergeCandidates(start, end, MERGE_GAP_THRESHOLD);
        if (!candidates.isEmpty()) {
            LoadTest target = chooseMergeTarget(candidates);
            Instant newStart = candidates.stream()
                    .map(LoadTest::start)
                    .filter(candidateStart -> candidateStart.isBefore(start))
                    .min(Instant::compareTo)
                    .orElse(start);
            Instant newEnd = candidates.stream()
                    .map(LoadTest::end)
                    .filter(candidateEnd -> candidateEnd.isAfter(end))
                    .max(Instant::compareTo)
                    .orElse(end);
            deleteMergeDuplicates(candidates, target.id());
            return update(target.id(), newStart, newEnd, type);
        }
        return insert(start, end, type);
    }

    private LoadTest chooseMergeTarget(List<LoadTest> candidates) {
        return candidates.stream()
                .filter(this::hasUserData)
                .findFirst()
                .orElse(candidates.get(0));
    }

    private boolean hasUserData(LoadTest test) {
        return hasText(test.comment()) || hasText(test.reportStatus()) || hasText(test.reportSummary());
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private void deleteMergeDuplicates(List<LoadTest> candidates, UUID targetId) {
        for (LoadTest candidate : candidates) {
            if (!candidate.id().equals(targetId)) {
                jdbcTemplate.update(DELETE_BY_ID_SQL, candidate.id());
            }
        }
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
