package com.example.chronicler.repository;

import com.example.chronicler.model.LoadTest;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
public class LoadTestRepository {

    private static final String SELECT_OVERLAP_SQL =
            "SELECT test_uuid, start_time, end_time FROM tests "
                    + "WHERE start_time <= ? AND end_time >= ? ORDER BY start_time LIMIT 1";
    private static final String SELECT_BY_ID_SQL =
            "SELECT test_uuid, start_time, end_time FROM tests WHERE test_uuid = ?";
    private static final String INSERT_RETURNING_SQL =
            "INSERT INTO tests (start_time, end_time) VALUES (?, ?) "
                    + "RETURNING test_uuid, start_time, end_time";
    private static final String UPDATE_RETURNING_SQL =
            "UPDATE tests SET start_time = ?, end_time = ? WHERE test_uuid = ? "
                    + "RETURNING test_uuid, start_time, end_time";

    private final JdbcTemplate jdbcTemplate;
    private final RowMapper<LoadTest> rowMapper =
            (rs, rowNum) -> new LoadTest(
                    rs.getObject("test_uuid", UUID.class),
                    rs.getTimestamp("start_time").toInstant(),
                    rs.getTimestamp("end_time").toInstant());

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

    public LoadTest insert(Instant start, Instant end) {
        return jdbcTemplate.queryForObject(
                INSERT_RETURNING_SQL,
                (rs, rowNum) -> rowMapper.mapRow(rs, rowNum),
                Timestamp.from(start),
                Timestamp.from(end));
    }

    public Optional<LoadTest> findById(UUID id) {
        return jdbcTemplate.query(
                        SELECT_BY_ID_SQL,
                        ps -> ps.setObject(1, id),
                        rowMapper)
                .stream()
                .findFirst();
    }

    public LoadTest update(UUID id, Instant start, Instant end) {
        return jdbcTemplate.queryForObject(
                UPDATE_RETURNING_SQL,
                (rs, rowNum) -> rowMapper.mapRow(rs, rowNum),
                Timestamp.from(start),
                Timestamp.from(end),
                id);
    }

    public LoadTest saveOrUpdate(Instant start, Instant end) {
        Optional<LoadTest> existingOpt = findOverlap(start, end);
        if (existingOpt.isPresent()) {
            LoadTest existing = existingOpt.get();
            Instant newStart = start.isBefore(existing.start()) ? start : existing.start();
            Instant newEnd = end.isAfter(existing.end()) ? end : existing.end();
            return update(existing.id(), newStart, newEnd);
        }
        return insert(start, end);
    }
}
