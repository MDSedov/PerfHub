package com.example.chronicler.registry;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.chronicler.repository.VictoriaMetricsQueryRepository;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class VmQueryRegistryTest {

    @Test
    void loadsQueriesFromRepository() {
        Map<String, String> storedQueries = new LinkedHashMap<>();
        storedQueries.put("intensity", "jmeterall.a.count");
        storedQueries.put("response_time_99", "jmeterall.a.pct99");
        VictoriaMetricsQueryRepository repository = new InMemoryVictoriaMetricsQueryRepository(storedQueries);

        VmQueryRegistry registry = new VmQueryRegistry(repository);

        assertThat(registry.getAll()).containsEntry("intensity", "jmeterall.a.count");
        assertThat(registry.findQuery("response_time_99")).contains("jmeterall.a.pct99");
    }

    private static final class InMemoryVictoriaMetricsQueryRepository extends VictoriaMetricsQueryRepository {

        private final Map<String, String> queries;

        private InMemoryVictoriaMetricsQueryRepository(Map<String, String> queries) {
            super(new JdbcTemplate());
            this.queries = queries;
        }

        @Override
        public Map<String, String> findAll() {
            return new LinkedHashMap<>(queries);
        }
    }
}
