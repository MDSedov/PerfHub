package perfhub.repository;

import perfhub.model.ConfluenceTemplateChart;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
public class ConfluenceTemplateChartRepository {

    private static final String BASE_SELECT = """
            SELECT id,
                   template_id,
                   chart_title,
                   dashboard_uid,
                   panel_id,
                   sort_order
              FROM confluence_report_template_charts
            """;
    private static final String SELECT_ALL_SQL = BASE_SELECT + " ORDER BY template_id, sort_order, id";
    private static final String SELECT_BY_TEMPLATE_ID_SQL =
            BASE_SELECT + " WHERE template_id = ? ORDER BY sort_order, id";
    private static final String DELETE_BY_TEMPLATE_ID_SQL =
            "DELETE FROM confluence_report_template_charts WHERE template_id = ?";
    private static final String INSERT_SQL = """
            INSERT INTO confluence_report_template_charts
                (template_id, chart_title, dashboard_uid, panel_id, sort_order)
            VALUES (?, ?, ?, ?, ?)
            """;

    private final JdbcTemplate jdbcTemplate;
    private final RowMapper<ConfluenceTemplateChart> rowMapper = (rs, rowNum) -> new ConfluenceTemplateChart(
            rs.getLong("id"),
            rs.getInt("template_id"),
            rs.getString("chart_title"),
            rs.getString("dashboard_uid"),
            rs.getInt("panel_id"),
            rs.getInt("sort_order"));

    public ConfluenceTemplateChartRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public Map<Integer, List<ConfluenceTemplateChart>> findAllGroupedByTemplateId() {
        return jdbcTemplate.query(SELECT_ALL_SQL, rowMapper).stream()
                .collect(Collectors.groupingBy(ConfluenceTemplateChart::templateId));
    }

    public List<ConfluenceTemplateChart> findByTemplateId(int templateId) {
        return jdbcTemplate.query(SELECT_BY_TEMPLATE_ID_SQL, rowMapper, templateId);
    }

    public void replaceForTemplate(int templateId, Collection<ConfluenceTemplateChart> charts) {
        jdbcTemplate.update(DELETE_BY_TEMPLATE_ID_SQL, templateId);
        if (charts == null || charts.isEmpty()) {
            return;
        }
        List<ConfluenceTemplateChart> sanitized = charts.stream().toList();
        jdbcTemplate.batchUpdate(INSERT_SQL, new BatchPreparedStatementSetter() {
            @Override
            public void setValues(PreparedStatement ps, int i) throws SQLException {
                ConfluenceTemplateChart chart = sanitized.get(i);
                ps.setInt(1, templateId);
                ps.setString(2, chart.title());
                ps.setString(3, chart.dashboardUid());
                ps.setInt(4, chart.panelId());
                ps.setInt(5, chart.sortOrder());
            }

            @Override
            public int getBatchSize() {
                return sanitized.size();
            }
        });
    }
}
