package com.example.chronicler.repository;

import com.example.chronicler.model.LoadTest;
import com.example.chronicler.model.LoadTestType;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
public class LoadTestRepository {

    private static final String SELECT_OVERLAP_SQL =
            "SELECT test_uuid, start_time, end_time, test_type FROM tests "
                    + "WHERE start_time <= ? AND end_time >= ? ORDER BY start_time LIMIT 1";
    private static final String SELECT_BY_ID_SQL =
            "SELECT test_uuid, start_time, end_time, test_type FROM tests WHERE test_uuid = ?";
    private static final String SELECT_ALL_ORDERED_SQL =
            "SELECT test_uuid, start_time, end_time, test_type FROM tests ORDER BY start_time DESC";
    private static final String INSERT_RETURNING_SQL =
            "INSERT INTO tests (start_time, end_time, test_type) VALUES (?, ?, ?) "
                    + "RETURNING test_uuid, start_time, end_time, test_type";
    private static final String UPDATE_RETURNING_SQL =
            "UPDATE tests SET start_time = ?, end_time = ?, test_type = ? WHERE test_uuid = ? "
                    + "RETURNING test_uuid, start_time, end_time, test_type";

    private final JdbcTemplate jdbcTemplate;
    private final RowMapper<LoadTest> rowMapper =
            (rs, rowNum) -> new LoadTest(
                    rs.getObject("test_uuid", UUID.class),
                    rs.getTimestamp("start_time").toInstant(),
                    rs.getTimestamp("end_time").toInstant(),
                    LoadTestType.fromDatabaseValue(rs.getString("test_type")));

    public LoadTestRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
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
        return jdbcTemplate.queryForObject(
                INSERT_RETURNING_SQL,
                (rs, rowNum) -> rowMapper.mapRow(rs, rowNum),
                Timestamp.from(start),
                Timestamp.from(end),
                type.value());
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
        return jdbcTemplate.queryForObject(
                UPDATE_RETURNING_SQL,
                (rs, rowNum) -> rowMapper.mapRow(rs, rowNum),
                Timestamp.from(start),
                Timestamp.from(end),
                type.value(),
                id);
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
}
