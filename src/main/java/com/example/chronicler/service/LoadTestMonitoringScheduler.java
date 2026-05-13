package com.example.chronicler.service;

import com.example.chronicler.model.LoadTest;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class LoadTestMonitoringScheduler {

    private static final Logger log = LoggerFactory.getLogger(LoadTestMonitoringScheduler.class);
    private static final Duration POLL_RANGE = Duration.ofMinutes(15);
    private static final Duration ACTIVE_TEST_GRACE_PERIOD = Duration.ofMinutes(3);

    private final VictoriaMetricsService victoriaMetricsService;
    private final KubernetesConfigurationService kubernetesConfigurationService;
    private final BackgroundErrorService backgroundErrorService;

    public LoadTestMonitoringScheduler(
            VictoriaMetricsService victoriaMetricsService,
            KubernetesConfigurationService kubernetesConfigurationService,
            BackgroundErrorService backgroundErrorService) {
        this.victoriaMetricsService = victoriaMetricsService;
        this.kubernetesConfigurationService = kubernetesConfigurationService;
        this.backgroundErrorService = backgroundErrorService;
    }

    @Scheduled(
            fixedDelayString = "${victoriametrics.poll-delay-ms:60000}",
            initialDelayString = "${victoriametrics.poll-initial-delay-ms:10000}")
    public void pollCurrentTests() {
        Instant end = Instant.now();
        Instant start = end.minus(POLL_RANGE);
        try {
            List<LoadTest> detected =
                    victoriaMetricsService.detectAndPersistActiveTests(start, end, ACTIVE_TEST_GRACE_PERIOD);
            List<UUID> testIds = detected.stream().map(LoadTest::id).collect(Collectors.toList());
            kubernetesConfigurationService.captureConfigurationsIfMissing(testIds);
        } catch (Exception ex) {
            String message = "Не удалось обновить информацию о текущих нагрузочных тестах";
            log.error(message, ex);
            backgroundErrorService.publish(
                    BackgroundErrorService.LOAD_TESTS_SCOPE,
                    message + ": " + ex.getMessage());
        }
    }
}
