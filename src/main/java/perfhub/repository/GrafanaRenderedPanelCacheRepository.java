package com.example.chronicler.repository;

import com.example.chronicler.model.GrafanaRenderedPanelCacheEntry;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
public class GrafanaRenderedPanelCacheRepository {

    private static final String SELECT_MATCHING_SQL = """
            SELECT id,
                   test_uuid,
                   template_id,
                   dashboard_uid,
                   dashboard_slug,
                   panel_id,
                   from_time,
                   to_time,
                   variables_hash,
                   image_width,
                   image_height,
                   timezone,
                   title,
                   description,
                   file_path,
                   content_type,
                   image_sha256,
                   rendered_at
              FROM grafana_rendered_panels
             WHERE test_uuid = ?
               AND template_id = ?
               AND dashboard_uid = ?
               AND panel_id = ?
               AND from_time = ?
               AND to_time = ?
               AND variables_hash = ?
               AND image_width = ?
               AND image_height = ?
               AND timezone = ?
             ORDER BY rendered_at DESC, id DESC
             LIMIT 1
            """;

    private static final String DELETE_MATCHING_SQL = """
            DELETE FROM grafana_rendered_panels
             WHERE test_uuid = ?
               AND template_id = ?
               AND dashboard_uid = ?
               AND panel_id = ?
               AND from_time = ?
               AND to_time = ?
               AND variables_hash = ?
               AND image_width = ?
               AND image_height = ?
               AND timezone = ?
            """;

    private static final String DELETE_BY_ID_SQL = """
            DELETE FROM grafana_rendered_panels
             WHERE id = ?
            """;

    private static final String INSERT_SQL = """
            INSERT INTO grafana_rendered_panels
                (test_uuid, template_id, dashboard_uid, dashboard_slug, panel_id, from_time, to_time,
                 variables_hash, image_width, image_height, timezone, title, description, file_path,
                 content_type, image_sha256, rendered_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;

    private final JdbcTemplate jdbcTemplate;
    private final RowMapper<GrafanaRenderedPanelCacheEntry> rowMapper =
            GrafanaRenderedPanelCacheRepository::mapRow;

    public GrafanaRenderedPanelCacheRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public Optional<GrafanaRenderedPanelCacheEntry> findLatestMatching(
            UUID testUuid,
            int templateId,
            String dashboardUid,
            int panelId,
            Instant from,
            Instant to,
            String variablesHash,
            int imageWidth,
            int imageHeight,
            String timezone) {
        return jdbcTemplate.query(
                        SELECT_MATCHING_SQL,
                        ps -> {
                            ps.setObject(1, testUuid);
                            ps.setInt(2, templateId);
                            ps.setString(3, dashboardUid);
                            ps.setInt(4, panelId);
                            ps.setTimestamp(5, Timestamp.from(from));
                            ps.setTimestamp(6, Timestamp.from(to));
                            ps.setString(7, variablesHash);
                            ps.setInt(8, imageWidth);
                            ps.setInt(9, imageHeight);
                            ps.setString(10, timezone);
                        },
                        rowMapper)
                .stream()
                .findFirst();
    }

    public void replace(GrafanaRenderedPanelCacheEntry entry) {
        jdbcTemplate.update(
                DELETE_MATCHING_SQL,
                ps -> {
                    ps.setObject(1, entry.testUuid());
                    ps.setInt(2, entry.templateId());
                    ps.setString(3, entry.dashboardUid());
                    ps.setInt(4, entry.panelId());
                    ps.setTimestamp(5, Timestamp.from(entry.from()));
                    ps.setTimestamp(6, Timestamp.from(entry.to()));
                    ps.setString(7, entry.variablesHash());
                    ps.setInt(8, entry.imageWidth());
                    ps.setInt(9, entry.imageHeight());
                    ps.setString(10, entry.timezone());
                });
        jdbcTemplate.update(
                INSERT_SQL,
                ps -> {
                    ps.setObject(1, entry.testUuid());
                    ps.setInt(2, entry.templateId());
                    ps.setString(3, entry.dashboardUid());
                    ps.setString(4, entry.dashboardSlug());
                    ps.setInt(5, entry.panelId());
                    ps.setTimestamp(6, Timestamp.from(entry.from()));
                    ps.setTimestamp(7, Timestamp.from(entry.to()));
                    ps.setString(8, entry.variablesHash());
                    ps.setInt(9, entry.imageWidth());
                    ps.setInt(10, entry.imageHeight());
                    ps.setString(11, entry.timezone());
                    ps.setString(12, entry.title());
                    ps.setString(13, entry.description());
                    ps.setString(14, entry.filePath());
                    ps.setString(15, entry.contentType());
                    ps.setString(16, entry.imageSha256());
                    ps.setTimestamp(17, Timestamp.from(entry.renderedAt()));
                });
    }

    public void deleteById(long id) {
        jdbcTemplate.update(DELETE_BY_ID_SQL, id);
    }

    private static GrafanaRenderedPanelCacheEntry mapRow(ResultSet rs, int rowNum) throws SQLException {
        Number id = (Number) rs.getObject("id");
        Number templateId = (Number) rs.getObject("template_id");
        return new GrafanaRenderedPanelCacheEntry(
                id == null ? null : id.longValue(),
                rs.getObject("test_uuid", UUID.class),
                templateId == null ? null : templateId.intValue(),
                rs.getString("dashboard_uid"),
                rs.getString("dashboard_slug"),
                rs.getInt("panel_id"),
                rs.getTimestamp("from_time").toInstant(),
                rs.getTimestamp("to_time").toInstant(),
                rs.getString("variables_hash"),
                rs.getInt("image_width"),
                rs.getInt("image_height"),
                rs.getString("timezone"),
                rs.getString("title"),
                rs.getString("description"),
                rs.getString("file_path"),
                rs.getString("content_type"),
                rs.getString("image_sha256"),
                rs.getTimestamp("rendered_at").toInstant());
    }
}
