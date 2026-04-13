package com.example.chronicler.repository;

import com.example.chronicler.model.DropAppConfigurationSnapshot;
import com.example.chronicler.model.DropAppConfigurationSnapshotDocument;
import com.example.chronicler.model.DropAppConfigurationSnapshotItem;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;
import org.springframework.util.CollectionUtils;

@Repository
public class DropAppConfigurationSnapshotRepository {

    private static final String SELECT_ALL_SNAPSHOTS_SQL = """
            SELECT id, release, comment, namespaces, components, saved_at
            FROM dropapp_configuration_snapshots
            ORDER BY saved_at DESC, id DESC
            """;

    private static final String SELECT_SNAPSHOT_BY_ID_SQL = """
            SELECT id, release, comment, namespaces, components, saved_at
            FROM dropapp_configuration_snapshots
            WHERE id = ?
            """;

    private static final String INSERT_SNAPSHOT_SQL = """
            INSERT INTO dropapp_configuration_snapshots (release, comment, namespaces, components, saved_at)
            VALUES (?, ?, ?, ?, ?)
            """;

    private static final String UPDATE_SNAPSHOT_SQL = """
            UPDATE dropapp_configuration_snapshots
            SET release = ?, comment = ?
            WHERE id = ?
            """;

    private static final String DELETE_SNAPSHOT_SQL = """
            DELETE FROM dropapp_configuration_snapshots
            WHERE id = ?
            """;

    private static final String SELECT_ITEMS_BY_SNAPSHOT_SQL = """
            SELECT id, snapshot_id, namespace, service_name, pod_name, container_name, version, release,
                   request_cpu, limit_cpu, request_ram, limit_ram
            FROM dropapp_configuration_snapshot_items
            WHERE snapshot_id = ?
            ORDER BY namespace NULLS FIRST, pod_name NULLS FIRST, container_name NULLS FIRST
            """;

    private static final String INSERT_ITEM_SQL = """
            INSERT INTO dropapp_configuration_snapshot_items
                (snapshot_id, namespace, service_name, pod_name, container_name, version, release,
                 request_cpu, limit_cpu, request_ram, limit_ram)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
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

    private final JdbcTemplate jdbcTemplate;
    private final RowMapper<DropAppConfigurationSnapshot> snapshotRowMapper =
            DropAppConfigurationSnapshotRepository::mapSnapshotRow;
    private final RowMapper<DropAppConfigurationSnapshotItem> itemRowMapper =
            DropAppConfigurationSnapshotRepository::mapItemRow;
    private final RowMapper<DropAppConfigurationSnapshotDocument> documentRowMapper =
            DropAppConfigurationSnapshotRepository::mapDocumentRow;

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

    public long insertSnapshot(String release, String comment, String namespaces, String components, Instant savedAt) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(INSERT_SNAPSHOT_SQL, new String[] {"id"});
            ps.setString(1, release);
            ps.setString(2, comment);
            ps.setString(3, namespaces);
            ps.setString(4, components);
            ps.setTimestamp(5, savedAt != null ? Timestamp.from(savedAt) : Timestamp.from(Instant.now()));
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

    public void updateSnapshot(long snapshotId, String release, String comment) {
        jdbcTemplate.update(UPDATE_SNAPSHOT_SQL, release, comment, snapshotId);
    }

    public int deleteSnapshot(long snapshotId) {
        return jdbcTemplate.update(DELETE_SNAPSHOT_SQL, snapshotId);
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
                    ps.setString(5, item.containerName());
                    ps.setString(6, item.version());
                    ps.setString(7, item.release());
                    ps.setString(8, item.requestCpu());
                    ps.setString(9, item.limitCpu());
                    ps.setString(10, item.requestRam());
                    ps.setString(11, item.limitRam());
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

    private static DropAppConfigurationSnapshot mapSnapshotRow(ResultSet rs, int rowNum) throws SQLException {
        Timestamp savedAt = rs.getTimestamp("saved_at");
        return new DropAppConfigurationSnapshot(
                rs.getObject("id", Long.class),
                rs.getString("release"),
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
}
