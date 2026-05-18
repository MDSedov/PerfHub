package perfhub.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;

import perfhub.model.ConfigurationEntry;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabase;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;

class ConfigurationRepositoryTest {

    private EmbeddedDatabase database;
    private JdbcTemplate jdbcTemplate;
    private ConfigurationRepository repository;

    @BeforeEach
    void setUp() {
        database = new EmbeddedDatabaseBuilder()
                .setType(EmbeddedDatabaseType.H2)
                .addScript("schema.sql")
                .build();
        jdbcTemplate = new JdbcTemplate(database);
        repository = new ConfigurationRepository(jdbcTemplate);
    }

    @AfterEach
    void tearDown() {
        database.shutdown();
    }

    @Test
    void connectionValuesAreScopedByUser() {
        long aliceId = createUser("alice");
        long bobId = createUser("bob");

        repository.upsert("grafana", "base-url", "http://global-grafana");
        repository.upsert("grafana", "base-url", "http://alice-grafana", aliceId);

        assertEquals(
                "http://alice-grafana",
                repository.findValue("grafana", "base-url", aliceId).orElseThrow());
        assertEquals("", repository.findValue("grafana", "base-url", bobId).orElseThrow());
        assertEquals("http://global-grafana", repository.findValue("grafana", "base-url").orElseThrow());

        List<ConfigurationEntry> aliceEntries = repository.findAllOrdered(aliceId);
        ConfigurationEntry aliceGrafanaBaseUrl = aliceEntries.stream()
                .filter(entry -> entry.service().equals("grafana"))
                .filter(entry -> entry.key().equals("base-url"))
                .findFirst()
                .orElseThrow();
        assertEquals("http://alice-grafana", aliceGrafanaBaseUrl.value());

        List<ConfigurationEntry> bobEntries = repository.findAllOrdered(bobId);
        ConfigurationEntry bobGrafanaBaseUrl = bobEntries.stream()
                .filter(entry -> entry.service().equals("grafana"))
                .filter(entry -> entry.key().equals("base-url"))
                .findFirst()
                .orElseThrow();
        assertEquals("", bobGrafanaBaseUrl.value());
    }

    @Test
    void globalConnectionValuesAreCopiedOnlyToTargetUser() {
        long aliceId = createUser("alice");
        long bobId = createUser("bob");

        repository.upsert("dropapp", "base-url", "https://global-dropapp");
        repository.upsert("dropapp", "base-url", "https://alice-dropapp", aliceId);

        repository.copyGlobalConnectionValuesToUser(bobId);

        assertEquals(
                "https://alice-dropapp",
                repository.findValue("dropapp", "base-url", aliceId).orElseThrow());
        assertEquals(
                "https://global-dropapp",
                repository.findValue("dropapp", "base-url", bobId).orElseThrow());
    }

    @Test
    void jiraConnectionSettingsHaveExpectedLabelsAndDefaultApiPath() {
        List<ConfigurationEntry> entries = repository.findAllOrdered();

        assertConfiguration(entries, "jira", "base-url", "", "Базовый URL");
        assertConfiguration(entries, "jira", "api-path", "/rest/api/2", "Путь до REST API");
        assertConfiguration(entries, "jira", "personal-access-token", "", "Персональный токен доступа");
        assertConfiguration(entries, "jira", "project-name", "", "Название проекта");
    }

    private void assertConfiguration(
            List<ConfigurationEntry> entries,
            String service,
            String key,
            String value,
            String description) {
        ConfigurationEntry entry = entries.stream()
                .filter(candidate -> candidate.service().equals(service))
                .filter(candidate -> candidate.key().equals(key))
                .findFirst()
                .orElseThrow();
        assertEquals(value, entry.value());
        assertEquals(description, entry.description());
    }

    private long createUser(String username) {
        jdbcTemplate.update("""
                INSERT INTO app_users (username, first_name, last_name, role, password_hash)
                VALUES (?, ?, ?, 'USER', 'hash')
                """, username, username, "Test");
        return jdbcTemplate.queryForObject(
                "SELECT id FROM app_users WHERE username = ?",
                Long.class,
                username);
    }
}
