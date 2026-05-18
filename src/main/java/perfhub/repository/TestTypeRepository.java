package perfhub.repository;

import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.util.StringUtils;

@Repository
public class TestTypeRepository {

    private static final String SELECT_ID_BY_VALUE_SQL =
            "SELECT id FROM test_types WHERE lower(test_type) = lower(?)";

    private final JdbcTemplate jdbcTemplate;

    public TestTypeRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public Optional<Integer> findIdByValue(String value) {
        if (!StringUtils.hasText(value)) {
            return Optional.empty();
        }
        return jdbcTemplate.query(
                        SELECT_ID_BY_VALUE_SQL,
                        ps -> ps.setString(1, value),
                        (rs, rowNum) -> rs.getInt("id"))
                .stream()
                .findFirst();
    }
}
