package perfhub.repository;

import perfhub.model.DropAppConfigurationSnapshot;
import perfhub.model.DropAppConfigurationSnapshotDocument;
import perfhub.model.DropAppConfigurationSnapshotDistribution;
import perfhub.model.DropAppConfigurationSnapshotItem;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;
import org.springframework.util.CollectionUtils;

@Repository
public class DropAppConfigurationSnapshotRepository {

    private static final String SELECT_ALL_SNAPSHOTS_SQL = """
            SELECT id, test_uuid, release, jira_release_issue_key, jira_release_issue_url, comment, namespaces, components, saved_at
            FROM dropapp_configuration_snapshots
            WHERE test_uuid IS NULL
            ORDER BY saved_at DESC, id DESC
            """;

    private static final String SELECT_SNAPSHOT_BY_ID_SQL = """
            SELECT id, test_uuid, release, jira_release_issue_key, jira_release_issue_url, comment, namespaces, components, saved_at
            FROM dropapp_configuration_snapshots
            WHERE id = ?
            """;

    private static final String SELECT_LATEST_SNAPSHOT_BY_TEST_SQL = """
            SELECT id, test_uuid, release, jira_release_issue_key, jira_release_issue_url, comment, namespaces, components, saved_at
            FROM dropapp_configuration_snapshots
            WHERE test_uuid = ?
            ORDER BY saved_at DESC, id DESC
            LIMIT 1
            """;

    private static final String INSERT_SNAPSHOT_SQL = """
            INSERT INTO dropapp_configuration_snapshots
                (test_uuid, release, jira_release_issue_key, jira_release_issue_url, comment, namespaces, components, saved_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            """;

    private static final String UPDATE_SNAPSHOT_SQL = """
            UPDATE dropapp_configuration_snapshots
            SET release = ?, jira_release_issue_key = ?, jira_release_issue_url = ?, comment = ?
            WHERE id = ?
            """;

    private static final String SELECT_RELEASE_ISSUE_URLS_BY_TEST_SQL = """
            SELECT test_uuid, jira_release_issue_url
            FROM (
                SELECT test_uuid,
                       jira_release_issue_url,
                       ROW_NUMBER() OVER (PARTITION BY test_uuid ORDER BY saved_at DESC, id DESC) AS row_number
                FROM dropapp_configuration_snapshots
                WHERE test_uuid IN (%s)
                  AND jira_release_issue_url IS NOT NULL
                  AND LENGTH(jira_release_issue_url) > 0
            ) latest
            WHERE row_number = 1
            """;

    private static final String DELETE_SNAPSHOT_SQL = """
            DELETE FROM dropapp_configuration_snapshots
            WHERE id = ?
            """;

    private static final String DELETE_SNAPSHOTS_BY_TEST_SQL = """
            DELETE FROM dropapp_configuration_snapshots
            WHERE test_uuid = ?
            """;

    private static final String SELECT_ITEMS_BY_SNAPSHOT_SQL = """
            SELECT id, snapshot_id, namespace, service_name, pod_name, pod_count, container_name, version, release,
                   request_cpu, limit_cpu, request_ram, limit_ram
            FROM dropapp_configuration_snapshot_items
            WHERE snapshot_id = ?
            ORDER BY namespace NULLS FIRST, service_name NULLS FIRST, container_name NULLS FIRST, pod_name NULLS FIRST
            """;

    private static final String INSERT_ITEM_SQL = """
            INSERT INTO dropapp_configuration_snapshot_items
                (snapshot_id, namespace, service_name, pod_name, pod_count, container_name, version, release,
                 request_cpu, limit_cpu, request_ram, limit_ram)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;

    private static final String SELECT_DOCUMENTS_BY_SNAPSHOT_SQL = """
            SELECT id, snapshot_id, namespace, component_code, resource_name, content
            FROM dropapp_configuration_snapshot_documents
            WHERE snapshot_id = ?
            ORDER BY namespace NULLS FIRST, component_code, resource_name
            """;

    private static final String INSERT_DOCUMENT_SQL = """
            INSERT INTO dropapp_configuration_snapshot_documents
                (snapshot_id, namespace, component_code, resource_name, content)
            VALUES (?, ?, ?, ?, ?)
            """;

    private static final String SELECT_DISTRIBUTIONS_BY_SNAPSHOT_SQL = """
            SELECT id, snapshot_id, service_name, service_url, distribution_name, distribution_url
            FROM dropapp_configuration_snapshot_distributions
            WHERE snapshot_id = ?
            ORDER BY service_name, distribution_name
            """;

    private static final String INSERT_DISTRIBUTION_SQL = """
            INSERT INTO dropapp_configuration_snapshot_distributions
                (snapshot_id, service_name, service_url, distribution_name, distribution_url)
            VALUES (?, ?, ?, ?, ?)
            """;

    private static final String DELETE_DISTRIBUTIONS_BY_SNAPSHOT_SQL = """
            DELETE FROM dropapp_configuration_snapshot_distributions
            WHERE snapshot_id = ?
            """;

    private final JdbcTemplate jdbcTemplate;
    private final RowMapper<DropAppConfigurationSnapshot> snapshotRowMapper =
            DropAppConfigurationSnapshotRepository::mapSnapshotRow;
    private final RowMapper<DropAppConfigurationSnapshotItem> itemRowMapper =
            DropAppConfigurationSnapshotRepository::mapItemRow;
    private final RowMapper<DropAppConfigurationSnapshotDocument> documentRowMapper =
            DropAppConfigurationSnapshotRepository::mapDocumentRow;
    private final RowMapper<DropAppConfigurationSnapshotDistribution> distributionRowMapper =
            DropAppConfigurationSnapshotRepository::mapDistributionRow;

    public DropAppConfigurationSnapshotRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public List<DropAppConfigurationSnapshot> findAllSnapshots() {
        return jdbcTemplate.query(SELECT_ALL_SNAPSHOTS_SQL, snapshotRowMapper);
    }

    public Optional<DropAppConfigurationSnapshot> findSnapshotById(Long snapshotId) {
        return jdbcTemplate.query(
                        SELECT_SNAPSHOT_BY_ID_SQL,
                        ps -> ps.setLong(1, snapshotId),
                        snapshotRowMapper)
                .stream()
                .findFirst();
    }

    public Optional<DropAppConfigurationSnapshot> findLatestSnapshotByTestUuid(UUID testUuid) {
        return jdbcTemplate.query(
                        SELECT_LATEST_SNAPSHOT_BY_TEST_SQL,
                        ps -> ps.setObject(1, testUuid),
                        snapshotRowMapper)
                .stream()
                .findFirst();
    }

    public List<DropAppConfigurationSnapshotItem> findItemsBySnapshotId(Long snapshotId) {
        return jdbcTemplate.query(
                SELECT_ITEMS_BY_SNAPSHOT_SQL,
                ps -> ps.setLong(1, snapshotId),
                itemRowMapper);
    }

    public List<DropAppConfigurationSnapshotDocument> findDocumentsBySnapshotId(Long snapshotId) {
        return jdbcTemplate.query(
                SELECT_DOCUMENTS_BY_SNAPSHOT_SQL,
                ps -> ps.setLong(1, snapshotId),
                documentRowMapper);
    }

    public List<DropAppConfigurationSnapshotDistribution> findDistributionsBySnapshotId(Long snapshotId) {
        return jdbcTemplate.query(
                SELECT_DISTRIBUTIONS_BY_SNAPSHOT_SQL,
                ps -> ps.setLong(1, snapshotId),
                distributionRowMapper);
    }

    public long insertSnapshot(String release, String comment, String namespaces, String components, Instant savedAt) {
        return insertSnapshot(null, release, null, null, comment, namespaces, components, savedAt);
    }

    public long insertSnapshot(
            UUID testUuid,
            String release,
            String jiraReleaseIssueKey,
            String jiraReleaseIssueUrl,
            String comment,
            String namespaces,
            String components,
            Instant savedAt) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(INSERT_SNAPSHOT_SQL, new String[] {"id"});
            ps.setObject(1, testUuid);
            ps.setString(2, release);
            ps.setString(3, jiraReleaseIssueKey);
            ps.setString(4, jiraReleaseIssueUrl);
            ps.setString(5, comment);
            ps.setString(6, namespaces);
            ps.setString(7, components);
            ps.setTimestamp(8, savedAt != null ? Timestamp.from(savedAt) : Timestamp.from(Instant.now()));
            return ps;
        }, keyHolder);
        Number key = null;
        Map<String, Object> keys = keyHolder.getKeys();
        Object idValue = keys != null ? keys.get("ID") : null;
        if (idValue == null && keys != null) {
            idValue = keys.get("id");
        }
        if (idValue instanceof Number number) {
            key = number;
        } else if (keyHolder.getKeyList().size() == 1) {
            Object firstValue = keyHolder.getKeyList().get(0).values().stream()
                    .findFirst()
                    .orElse(null);
            if (firstValue instanceof Number number) {
                key = number;
            }
        }
        if (key == null) {
            throw new IllegalStateException("Не удалось получить идентификатор сохраненной конфигурации DropApp");
        }
        return key.longValue();
    }

    public Map<UUID, String> findLatestJiraReleaseIssueUrlsByTestUuids(List<UUID> testUuids) {
        if (CollectionUtils.isEmpty(testUuids)) {
            return Map.of();
        }
        String placeholders = String.join(",", java.util.Collections.nCopies(testUuids.size(), "?"));
        List<Object> args = testUuids.stream().map(uuid -> (Object) uuid).toList();
        return jdbcTemplate.query(
                SELECT_RELEASE_ISSUE_URLS_BY_TEST_SQL.formatted(placeholders),
                ps -> {
                    for (int index = 0; index < args.size(); index++) {
                        ps.setObject(index + 1, args.get(index));
                    }
                },
                rs -> {
                    Map<UUID, String> result = new HashMap<>();
                    while (rs.next()) {
                        UUID testUuid = rs.getObject("test_uuid", UUID.class);
                        String url = rs.getString("jira_release_issue_url");
                        if (testUuid != null && url != null && !url.isBlank()) {
                            result.put(testUuid, url);
                        }
                    }
                    return result;
                });
    }

    public void updateSnapshot(
            long snapshotId,
            String release,
            String jiraReleaseIssueKey,
            String jiraReleaseIssueUrl,
            String comment) {
        jdbcTemplate.update(UPDATE_SNAPSHOT_SQL, release, jiraReleaseIssueKey, jiraReleaseIssueUrl, comment, snapshotId);
    }

    public int deleteSnapshot(long snapshotId) {
        return jdbcTemplate.update(DELETE_SNAPSHOT_SQL, snapshotId);
    }

    public int deleteSnapshotsByTestUuid(UUID testUuid) {
        return jdbcTemplate.update(DELETE_SNAPSHOTS_BY_TEST_SQL, ps -> ps.setObject(1, testUuid));
    }

    public void saveItems(long snapshotId, List<DropAppConfigurationSnapshotItem> items) {
        if (CollectionUtils.isEmpty(items)) {
            return;
        }
        jdbcTemplate.batchUpdate(
                INSERT_ITEM_SQL,
                items,
                items.size(),
                (ps, item) -> {
                    ps.setLong(1, snapshotId);
                    ps.setString(2, item.namespace());
                    ps.setString(3, item.serviceName());
                    ps.setString(4, item.podName());
                    if (item.podCount() != null) {
                        ps.setInt(5, item.podCount());
                    } else {
                        ps.setObject(5, null);
                    }
                    ps.setString(6, item.containerName());
                    ps.setString(7, item.version());
                    ps.setString(8, item.release());
                    ps.setString(9, item.requestCpu());
                    ps.setString(10, item.limitCpu());
                    ps.setString(11, item.requestRam());
                    ps.setString(12, item.limitRam());
                });
    }

    public void saveDocuments(long snapshotId, List<DropAppConfigurationSnapshotDocument> documents) {
        if (CollectionUtils.isEmpty(documents)) {
            return;
        }
        jdbcTemplate.batchUpdate(
                INSERT_DOCUMENT_SQL,
                documents,
                documents.size(),
                (ps, document) -> {
                    ps.setLong(1, snapshotId);
                    ps.setString(2, document.namespace());
                    ps.setString(3, document.componentCode());
                    ps.setString(4, document.resourceName());
                    ps.setString(5, document.content());
                });
    }

    public void saveDistributions(long snapshotId, List<DropAppConfigurationSnapshotDistribution> distributions) {
        if (CollectionUtils.isEmpty(distributions)) {
            return;
        }
        jdbcTemplate.batchUpdate(
                INSERT_DISTRIBUTION_SQL,
                distributions,
                distributions.size(),
                (ps, distribution) -> {
                    ps.setLong(1, snapshotId);
                    ps.setString(2, distribution.serviceName());
                    ps.setString(3, distribution.serviceUrl());
                    ps.setString(4, distribution.distributionName());
                    ps.setString(5, distribution.distributionUrl());
                });
    }

    public int deleteDistributionsBySnapshotId(long snapshotId) {
        return jdbcTemplate.update(DELETE_DISTRIBUTIONS_BY_SNAPSHOT_SQL, snapshotId);
    }

    private static DropAppConfigurationSnapshot mapSnapshotRow(ResultSet rs, int rowNum) throws SQLException {
        Timestamp savedAt = rs.getTimestamp("saved_at");
        return new DropAppConfigurationSnapshot(
                rs.getObject("id", Long.class),
                rs.getObject("test_uuid", UUID.class),
                rs.getString("release"),
                rs.getString("jira_release_issue_key"),
                rs.getString("jira_release_issue_url"),
                rs.getString("comment"),
                rs.getString("namespaces"),
                rs.getString("components"),
                savedAt != null ? savedAt.toInstant() : null);
    }

    private static DropAppConfigurationSnapshotItem mapItemRow(ResultSet rs, int rowNum) throws SQLException {
        return new DropAppConfigurationSnapshotItem(
                rs.getObject("id", Long.class),
                rs.getObject("snapshot_id", Long.class),
                rs.getString("namespace"),
                rs.getString("service_name"),
                rs.getString("pod_name"),
                readInteger(rs, "pod_count"),
                rs.getString("container_name"),
                rs.getString("version"),
                rs.getString("release"),
                rs.getString("request_cpu"),
                rs.getString("limit_cpu"),
                rs.getString("request_ram"),
                rs.getString("limit_ram"));
    }

    private static DropAppConfigurationSnapshotDocument mapDocumentRow(ResultSet rs, int rowNum) throws SQLException {
        return new DropAppConfigurationSnapshotDocument(
                rs.getObject("id", Long.class),
                rs.getObject("snapshot_id", Long.class),
                rs.getString("namespace"),
                rs.getString("component_code"),
                rs.getString("resource_name"),
                rs.getString("content"));
    }

    private static DropAppConfigurationSnapshotDistribution mapDistributionRow(ResultSet rs, int rowNum)
            throws SQLException {
        return new DropAppConfigurationSnapshotDistribution(
                rs.getObject("id", Long.class),
                rs.getObject("snapshot_id", Long.class),
                rs.getString("service_name"),
                rs.getString("service_url"),
                rs.getString("distribution_name"),
                rs.getString("distribution_url"));
    }

    private static Integer readInteger(ResultSet rs, String column) throws SQLException {
        Object value = rs.getObject(column);
        return value instanceof Number number ? number.intValue() : null;
    }
}
