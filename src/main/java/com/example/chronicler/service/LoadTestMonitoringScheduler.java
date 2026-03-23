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
            List<LoadTest> detected = victoriaMetricsService.detectAndPersistTests(start, end);
            List<UUID> testIds = detected.stream()
                    .filter(test -> isActiveAt(test, end))
                    .map(LoadTest::id)
                    .collect(Collectors.toList());
            kubernetesConfigurationService.captureConfigurationsIfMissing(testIds);
        } catch (Exception ex) {
            String message = "Не удалось обновить информацию о текущих нагрузочных тестах";
            log.error(message, ex);
            backgroundErrorService.publish(message + ": " + ex.getMessage());
        }
    }

    private boolean isActiveAt(LoadTest test, Instant moment) {
        if (test == null || moment == null) {
            return false;
        }
        Instant start = test.start();
        Instant end = test.end();
        if (start != null && moment.isBefore(start)) {
            return false;
        }
        if (end != null && moment.isAfter(end)) {
            return false;
        }
        return start != null || end != null;
    }
}
