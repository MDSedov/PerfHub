package com.example.chronicler.service;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.chronicler.model.LoadTest;
import com.example.chronicler.model.LoadTestType;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class LoadTestMonitoringSchedulerTest {

    @Mock
    private VictoriaMetricsService victoriaMetricsService;

    @Mock
    private KubernetesConfigurationService kubernetesConfigurationService;

    @Mock
    private BackgroundErrorService backgroundErrorService;

    @Mock
    private ConfigurationProvider configurationProvider;

    private LoadTestMonitoringScheduler scheduler;

    @BeforeEach
    void setUp() {
        scheduler = new LoadTestMonitoringScheduler(
                victoriaMetricsService,
                kubernetesConfigurationService,
                backgroundErrorService,
                configurationProvider);
    }

    @Test
    void pollCurrentTestsSkipsWhenGlobalVictoriaMetricsConfigurationIsMissing() {
        when(configurationProvider.findValue("victoria-metrics", "base-url"))
                .thenReturn(Optional.empty());

        scheduler.pollCurrentTests();

        verify(victoriaMetricsService, never())
                .detectAndPersistActiveTests(any(Instant.class), any(Instant.class), any(Duration.class));
        verify(backgroundErrorService, never()).publish(any(), any());
    }

    @Test
    void pollCurrentTestsCapturesConfigurationForDetectedTestsWhenConfigured() {
        mockCompleteVictoriaMetricsConfiguration();
        UUID testId = UUID.fromString("aaaaaaaa-1111-2222-3333-bbbbbbbbbbbb");
        when(victoriaMetricsService.detectAndPersistActiveTests(
                        any(Instant.class),
                        any(Instant.class),
                        eq(Duration.ofMinutes(3))))
                .thenReturn(List.of(new LoadTest(
                        testId,
                        Instant.parse("2024-05-01T10:00:00Z"),
                        Instant.parse("2024-05-01T11:00:00Z"),
                        LoadTestType.MAX,
                        null)));

        scheduler.pollCurrentTests();

        verify(kubernetesConfigurationService).captureConfigurationsIfMissing(List.of(testId));
        verify(backgroundErrorService, never()).publish(any(), any());
    }

    @Test
    void pollCurrentTestsPublishesOnlyDistinctFailures() {
        mockCompleteVictoriaMetricsConfiguration();
        when(victoriaMetricsService.detectAndPersistActiveTests(
                        any(Instant.class),
                        any(Instant.class),
                        any(Duration.class)))
                .thenThrow(new IllegalStateException("Victoria Metrics timeout"));

        scheduler.pollCurrentTests();
        scheduler.pollCurrentTests();

        verify(backgroundErrorService).publish(
                BackgroundErrorService.LOAD_TESTS_SCOPE,
                "Не удалось обновить информацию о текущих нагрузочных тестах: Victoria Metrics timeout");
        verify(kubernetesConfigurationService, never()).captureConfigurationsIfMissing(anyCollection());
    }

    private void mockCompleteVictoriaMetricsConfiguration() {
        when(configurationProvider.findValue("victoria-metrics", "base-url"))
                .thenReturn(Optional.of("http://localhost:8428"));
        when(configurationProvider.findValue("victoria-metrics", "query-range-path"))
                .thenReturn(Optional.of("/api/v1/query_range"));
        when(configurationProvider.findValue("victoria-metrics", "default-step-seconds"))
                .thenReturn(Optional.of("60"));
        when(configurationProvider.findValue("victoria-metrics", "tests-search-query"))
                .thenReturn(Optional.of("sum(rate(http_requests_total[1m]))"));
    }
}
