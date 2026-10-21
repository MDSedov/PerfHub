package perfhub.repository;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import perfhub.client.GrafanaRenderCatalogParser;
import perfhub.dto.grafana.GrafanaCatalogSnapshot;
import java.sql.Timestamp;
import java.util.List;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class GrafanaRenderCatalogRepository {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public GrafanaRenderCatalogRepository(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    public String revision(long owner) {
        try {
            jdbc.update("INSERT INTO grafana_render_sources (owner_id, revision) SELECT ?, ? WHERE NOT EXISTS"
                    + " (SELECT 1 FROM grafana_render_sources WHERE owner_id = ?)", owner, UUID.randomUUID().toString(), owner);
        } catch (DuplicateKeyException ex) {
            // Другой запрос того же пользователя уже создал revision.
        }
        return jdbc.queryForObject("SELECT revision FROM grafana_render_sources WHERE owner_id = ?", String.class, owner);
    }

    public void rotate(long owner) {
        jdbc.update("MERGE INTO grafana_render_sources (owner_id, revision) KEY(owner_id) VALUES (?, ?)",
                owner, UUID.randomUUID().toString());
    }

    public List<GrafanaCatalogSnapshot> find(long owner, String revision) {
        return jdbc.query("SELECT snapshot_json, updated_at, stale FROM grafana_render_snapshots"
                + " WHERE owner_id = ? AND revision = ? ORDER BY dashboard_uid, context_key", (rs, row) -> {
                    try {
                        var value = mapper.readValue(rs.getString("snapshot_json"), GrafanaCatalogSnapshot.class);
                        return new GrafanaCatalogSnapshot(value.dashboard(), value.datasource(), value.namespace(),
                                value.variables(), value.deployments(), rs.getTimestamp("updated_at").toInstant(), rs.getBoolean("stale"), owner, revision);
                    } catch (JsonProcessingException ex) {
                        throw new IllegalStateException("Не удалось прочитать локальный каталог Grafana", ex);
                    }
                }, owner, revision);
    }

    @Transactional
    public boolean save(long owner, String revision, GrafanaCatalogSnapshot snapshot) {
        // Короткая блокировка только публикации результата; внешний HTTP завершён до транзакции.
        String active = jdbc.queryForObject("SELECT revision FROM grafana_render_sources WHERE owner_id = ? FOR UPDATE",
                String.class, owner);
        if (!revision.equals(active)) {
            return false;
        }
        try {
            jdbc.update("MERGE INTO grafana_render_snapshots"
                    + " (owner_id, revision, dashboard_uid, context_key, snapshot_json, updated_at, stale)"
                    + " KEY(owner_id, revision, dashboard_uid, context_key) VALUES (?, ?, ?, ?, ?, ?, FALSE)",
                    owner, revision, snapshot.dashboard().uid(), contextKey(snapshot), mapper.writeValueAsString(snapshot),
                    Timestamp.from(snapshot.updatedAt()));
            return true;
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Не удалось сохранить каталог Grafana", ex);
        }
    }

    public void stale(long owner, String revision, String uid) {
        jdbc.update("UPDATE grafana_render_snapshots SET stale = TRUE WHERE owner_id = ? AND revision = ? AND dashboard_uid = ?",
                owner, revision, uid);
    }

    private String contextKey(GrafanaCatalogSnapshot snapshot) {
        return GrafanaRenderCatalogParser.fingerprint(snapshot.datasource() + "\n" + snapshot.namespace());
    }
}
