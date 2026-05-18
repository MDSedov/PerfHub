package perfhub.repository;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class GigachatPromptRepository {

    private static final String SELECT_ALL_SQL = """
            SELECT prompt_key, text
              FROM gigachat_prompts
             ORDER BY prompt_key
            """;

    private static final String SELECT_ONE_SQL =
            "SELECT text FROM gigachat_prompts WHERE prompt_key = ?";

    private final JdbcTemplate jdbcTemplate;

    public GigachatPromptRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public Map<String, String> findAll() {
        return jdbcTemplate.query(
                SELECT_ALL_SQL,
                rs -> {
                    Map<String, String> prompts = new LinkedHashMap<>();
                    while (rs.next()) {
                        prompts.put(rs.getString("prompt_key"), rs.getString("text"));
                    }
                    return prompts;
                });
    }

    public Optional<String> findByKey(String key) {
        return jdbcTemplate.query(
                        SELECT_ONE_SQL,
                        ps -> ps.setString(1, key),
                        (rs, rowNum) -> rs.getString("text"))
                .stream()
                .findFirst();
    }
}
