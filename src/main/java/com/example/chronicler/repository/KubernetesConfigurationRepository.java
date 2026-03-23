package com.example.chronicler.repository;

import com.example.chronicler.model.KubernetesConfiguration;
import com.example.chronicler.util.ReleaseVersionUtils;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import org.springframework.util.CollectionUtils;

@Repository
public class KubernetesConfigurationRepository {

    private static final String SELECT_BY_TEST_SQL = """
            SELECT id, test_uuid, namespace, service_name, pod_name, container_name, version, release,
                   request_cpu, limit_cpu, request_ram, limit_ram, captured_at
            FROM kubernetes_configurations
            WHERE test_uuid = ?
            ORDER BY namespace NULLS FIRST, pod_name NULLS FIRST, container_name NULLS FIRST
            """;

    private static final String EXISTS_SQL =
            "SELECT 1 FROM kubernetes_configurations WHERE test_uuid = ? LIMIT 1";

    private static final String INSERT_SQL = """
            INSERT INTO kubernetes_configurations
                (test_uuid, namespace, service_name, pod_name, container_name, version, release,
                 request_cpu, limit_cpu, request_ram, limit_ram, captured_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;

    private final JdbcTemplate jdbcTemplate;
    private final RowMapper<KubernetesConfiguration> rowMapper = KubernetesConfigurationRepository::mapRow;

    public KubernetesConfigurationRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public List<KubernetesConfiguration> findByTest(UUID testUuid) {
        return jdbcTemplate.query(SELECT_BY_TEST_SQL, ps -> ps.setObject(1, testUuid), rowMapper);
    }

    public boolean existsForTest(UUID testUuid) {
        List<Integer> result = jdbcTemplate.query(EXISTS_SQL, ps -> ps.setObject(1, testUuid), (rs, rowNum) -> 1);
        return !result.isEmpty();
    }

    public Set<UUID> findConfiguredTestIds(Collection<UUID> testUuids) {
        if (CollectionUtils.isEmpty(testUuids)) {
            return Collections.emptySet();
        }
        String placeholders = String.join(",", Collections.nCopies(testUuids.size(), "?"));
        String sql = """
                SELECT DISTINCT test_uuid
                FROM kubernetes_configurations
                WHERE test_uuid IN (%s)
                """.formatted(placeholders);
        return new LinkedHashSet<>(jdbcTemplate.query(
                sql,
                testUuids.toArray(),
                (rs, rowNum) -> rs.getObject("test_uuid", UUID.class)));
    }

    public Map<UUID, String> findMaxReleases(Collection<UUID> testUuids) {
        if (CollectionUtils.isEmpty(testUuids)) {
            return Collections.emptyMap();
        }
        String placeholders = String.join(",", Collections.nCopies(testUuids.size(), "?"));
        String sql = """
                SELECT test_uuid, release
                FROM kubernetes_configurations
                WHERE test_uuid IN (%s) AND release IS NOT NULL AND release <> ''
                """.formatted(placeholders);
        Map<UUID, String> result = new LinkedHashMap<>();
        jdbcTemplate.query(
                sql,
                testUuids.toArray(),
                rs -> {
                    UUID testUuid = rs.getObject("test_uuid", UUID.class);
                    String release = ReleaseVersionUtils.normalize(rs.getString("release"));
                    if (release == null) {
                        return;
                    }
                    result.merge(
                            testUuid,
                            release,
                            (current, candidate) ->
                                    ReleaseVersionUtils.compare(current, candidate) >= 0 ? current : candidate);
                });
        return result;
    }

    public String findMaxRelease(UUID testUuid) {
        if (testUuid == null) {
            return null;
        }
        List<String> releases = jdbcTemplate.query(
                """
                        SELECT release
                        FROM kubernetes_configurations
                        WHERE test_uuid = ? AND release IS NOT NULL AND release <> ''
                        """,
                ps -> ps.setObject(1, testUuid),
                (rs, rowNum) -> rs.getString("release"));
        return ReleaseVersionUtils.findMaxRelease(releases);
    }

    public void saveAll(UUID testUuid, List<KubernetesConfiguration> configurations) {
        if (CollectionUtils.isEmpty(configurations)) {
            return;
        }
        jdbcTemplate.batchUpdate(
                INSERT_SQL,
                configurations,
                configurations.size(),
                (ps, config) -> {
                    ps.setObject(1, testUuid);
                    ps.setString(2, config.namespace());
                    ps.setString(3, config.serviceName());
                    ps.setString(4, config.podName());
                    ps.setString(5, config.containerName());
                    ps.setString(6, config.version());
                    ps.setString(7, config.release());
                    ps.setString(8, config.requestCpu());
                    ps.setString(9, config.limitCpu());
                    ps.setString(10, config.requestRam());
                    ps.setString(11, config.limitRam());
                    ps.setTimestamp(12, toTimestamp(config.capturedAt()));
                });
    }

    private static Timestamp toTimestamp(Instant instant) {
        return instant != null ? Timestamp.from(instant) : null;
    }

    private static KubernetesConfiguration mapRow(ResultSet rs, int rowNum) throws SQLException {
        Timestamp captured = rs.getTimestamp("captured_at");
        Instant capturedAt = captured != null ? captured.toInstant() : null;
        return new KubernetesConfiguration(
                rs.getObject("id", Long.class),
                rs.getObject("test_uuid", UUID.class),
                rs.getString("namespace"),
                rs.getString("service_name"),
                rs.getString("pod_name"),
                rs.getString("container_name"),
                rs.getString("version"),
                rs.getString("release"),
                rs.getString("request_cpu"),
                rs.getString("limit_cpu"),
                rs.getString("request_ram"),
                rs.getString("limit_ram"),
                capturedAt);
    }
}
