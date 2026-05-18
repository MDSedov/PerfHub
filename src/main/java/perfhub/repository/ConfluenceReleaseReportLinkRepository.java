package perfhub.repository;

import perfhub.model.ConfluenceReleaseReportLink;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
public class ConfluenceReleaseReportLinkRepository {

    private static final String INSERT_LINK_SQL = """
            INSERT INTO confluence_release_report_links (
                release_page_id,
                test_uuid,
                report_page_id,
                report_page_title,
                report_page_url,
                test_number,
                test_title,
                test_title_short,
                test_type,
                test_period,
                test_status,
                test_summary)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;

    private static final String SELECT_BY_RELEASE_SQL = """
            SELECT id,
                   release_page_id,
                   test_uuid,
                   report_page_id,
                   report_page_title,
                   report_page_url,
                   test_number,
                   test_title,
                   test_title_short,
                   test_type,
                   test_period,
                   test_status,
                   test_summary,
                   created_at
              FROM confluence_release_report_links
             WHERE release_page_id = ?
             ORDER BY created_at, id
            """;

    private static final String SELECT_BLOCK_TEMPLATE_SQL = """
            SELECT item_template
              FROM confluence_release_report_blocks
             WHERE release_page_id = ?
            """;

    private static final String UPDATE_BLOCK_TEMPLATE_SQL = """
            UPDATE confluence_release_report_blocks
               SET item_template = ?,
                   updated_at = CURRENT_TIMESTAMP
             WHERE release_page_id = ?
            """;

    private static final String INSERT_BLOCK_TEMPLATE_SQL = """
            INSERT INTO confluence_release_report_blocks (release_page_id, item_template)
            VALUES (?, ?)
            """;

    private final JdbcTemplate jdbcTemplate;
    private final RowMapper<ConfluenceReleaseReportLink> rowMapper = this::mapRow;

    public ConfluenceReleaseReportLinkRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void saveAll(Collection<ConfluenceReleaseReportLink> links) {
        if (links == null || links.isEmpty()) {
            return;
        }
        List<ConfluenceReleaseReportLink> sanitized = links.stream().toList();
        jdbcTemplate.batchUpdate(INSERT_LINK_SQL, new BatchPreparedStatementSetter() {
            @Override
            public void setValues(java.sql.PreparedStatement ps, int i) throws SQLException {
                ConfluenceReleaseReportLink link = sanitized.get(i);
                ps.setString(1, link.releasePageId());
                ps.setObject(2, link.testUuid());
                ps.setString(3, link.reportPageId());
                ps.setString(4, link.reportPageTitle());
                ps.setString(5, link.reportPageUrl());
                ps.setString(6, link.testNumber());
                ps.setString(7, link.testTitle());
                ps.setString(8, link.testTitleShort());
                ps.setString(9, link.testType());
                ps.setString(10, link.testPeriod());
                ps.setString(11, link.testStatus());
                ps.setString(12, link.testSummary());
            }

            @Override
            public int getBatchSize() {
                return sanitized.size();
            }
        });
    }

    public List<ConfluenceReleaseReportLink> findByReleasePageId(String releasePageId) {
        return jdbcTemplate.query(SELECT_BY_RELEASE_SQL, rowMapper, releasePageId);
    }

    public Optional<String> findBlockTemplate(String releasePageId) {
        return jdbcTemplate.query(
                        SELECT_BLOCK_TEMPLATE_SQL,
                        (rs, rowNum) -> rs.getString("item_template"),
                        releasePageId)
                .stream()
                .findFirst();
    }

    public void saveBlockTemplate(String releasePageId, String itemTemplate) {
        int updated = jdbcTemplate.update(UPDATE_BLOCK_TEMPLATE_SQL, itemTemplate, releasePageId);
        if (updated == 0) {
            jdbcTemplate.update(INSERT_BLOCK_TEMPLATE_SQL, releasePageId, itemTemplate);
        }
    }

    private ConfluenceReleaseReportLink mapRow(ResultSet rs, int rowNum) throws SQLException {
        Timestamp createdAt = rs.getTimestamp("created_at");
        return new ConfluenceReleaseReportLink(
                rs.getLong("id"),
                rs.getString("release_page_id"),
                rs.getObject("test_uuid", UUID.class),
                rs.getString("report_page_id"),
                rs.getString("report_page_title"),
                rs.getString("report_page_url"),
                rs.getString("test_number"),
                rs.getString("test_title"),
                rs.getString("test_title_short"),
                rs.getString("test_type"),
                rs.getString("test_period"),
                rs.getString("test_status"),
                rs.getString("test_summary"),
                createdAt != null ? createdAt.toInstant() : Instant.EPOCH);
    }
}
