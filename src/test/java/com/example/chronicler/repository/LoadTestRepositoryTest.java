package com.example.chronicler.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.chronicler.model.LoadTest;
import com.example.chronicler.model.LoadTestType;
import java.time.Instant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabase;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;

class LoadTestRepositoryTest {

    private EmbeddedDatabase database;
    private LoadTestRepository repository;

    @BeforeEach
    void setUp() {
        database = new EmbeddedDatabaseBuilder()
                .setType(EmbeddedDatabaseType.H2)
                .addScript("schema.sql")
                .build();
        JdbcTemplate jdbcTemplate = new JdbcTemplate(database);
        repository = new LoadTestRepository(jdbcTemplate, new TestTypeRepository(jdbcTemplate));
    }

    @AfterEach
    void tearDown() {
        database.shutdown();
    }

    @Test
    void saveOrUpdateMergesIntervalsSplitByMidnightBoundary() {
        LoadTest firstPart = repository.saveOrUpdate(
                Instant.parse("2024-05-01T20:00:00Z"),
                Instant.parse("2024-05-01T20:59:00Z"),
                LoadTestType.STABILITY);

        LoadTest merged = repository.saveOrUpdate(
                Instant.parse("2024-05-01T21:00:00Z"),
                Instant.parse("2024-05-01T22:00:00Z"),
                LoadTestType.STABILITY);

        assertThat(merged.id()).isEqualTo(firstPart.id());
        assertThat(merged.start()).isEqualTo(Instant.parse("2024-05-01T20:00:00Z"));
        assertThat(merged.end()).isEqualTo(Instant.parse("2024-05-01T22:00:00Z"));
        assertThat(repository.findAllOrderByStartDesc()).singleElement()
                .satisfies(test -> {
                    assertThat(test.id()).isEqualTo(firstPart.id());
                    assertThat(test.start()).isEqualTo(Instant.parse("2024-05-01T20:00:00Z"));
                    assertThat(test.end()).isEqualTo(Instant.parse("2024-05-01T22:00:00Z"));
                });
    }

    @Test
    void saveOrUpdateKeepsSeparateIntervalsWhenGapIsLargerThanMergeThreshold() {
        repository.saveOrUpdate(
                Instant.parse("2024-05-01T20:00:00Z"),
                Instant.parse("2024-05-01T20:55:00Z"),
                LoadTestType.STABILITY);

        repository.saveOrUpdate(
                Instant.parse("2024-05-01T21:00:00Z"),
                Instant.parse("2024-05-01T22:00:00Z"),
                LoadTestType.STABILITY);

        assertThat(repository.findAllOrderByStartDesc()).hasSize(2);
    }

    @Test
    void saveOrUpdateCollapsesMultipleAdjacentExistingIntervals() {
        LoadTest first = repository.saveOrUpdate(
                Instant.parse("2024-05-01T20:00:00Z"),
                Instant.parse("2024-05-01T20:50:00Z"),
                LoadTestType.STABILITY);
        LoadTest second = repository.saveOrUpdate(
                Instant.parse("2024-05-01T21:10:00Z"),
                Instant.parse("2024-05-01T22:00:00Z"),
                LoadTestType.STABILITY);
        assertThat(second.id()).isNotEqualTo(first.id());

        LoadTest merged = repository.saveOrUpdate(
                Instant.parse("2024-05-01T20:52:00Z"),
                Instant.parse("2024-05-01T21:08:00Z"),
                LoadTestType.STABILITY);

        assertThat(merged.id()).isEqualTo(first.id());
        assertThat(merged.start()).isEqualTo(Instant.parse("2024-05-01T20:00:00Z"));
        assertThat(merged.end()).isEqualTo(Instant.parse("2024-05-01T22:00:00Z"));
        assertThat(repository.findAllOrderByStartDesc()).singleElement();
    }
}
