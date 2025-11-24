package com.example.chronicler.service;

import com.example.chronicler.model.LoadTest;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
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

    public LoadTestMonitoringScheduler(
            VictoriaMetricsService victoriaMetricsService,
            KubernetesConfigurationService kubernetesConfigurationService) {
        this.victoriaMetricsService = victoriaMetricsService;
        this.kubernetesConfigurationService = kubernetesConfigurationService;
    }

    @Scheduled(
            fixedDelayString = "${victoriametrics.poll-delay-ms:60000}",
            initialDelayString = "${victoriametrics.poll-initial-delay-ms:10000}")
    public void pollCurrentTests() {
        Instant end = Instant.now();
        Instant start = end.minus(POLL_RANGE);
        try {
            List<LoadTest> tests = victoriaMetricsService.detectAndPersistTests(start, end);
            tests.forEach(test -> kubernetesConfigurationService.captureCurrentConfigurationIfAbsent(test.id()));
        } catch (Exception ex) {
            log.error("Не удалось обновить информацию о текущих нагрузочных тестах", ex);
        }
    }
}
