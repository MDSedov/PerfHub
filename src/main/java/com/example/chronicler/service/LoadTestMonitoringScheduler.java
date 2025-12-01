package com.example.chronicler.service;

import java.time.Duration;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class LoadTestMonitoringScheduler {

    private static final Logger log = LoggerFactory.getLogger(LoadTestMonitoringScheduler.class);
    private static final Duration POLL_RANGE = Duration.ofMinutes(15);

    private final VictoriaMetricsService victoriaMetricsService;

    public LoadTestMonitoringScheduler(VictoriaMetricsService victoriaMetricsService) {
        this.victoriaMetricsService = victoriaMetricsService;
    }

    @Scheduled(
            fixedDelayString = "${victoriametrics.poll-delay-ms:60000}",
            initialDelayString = "${victoriametrics.poll-initial-delay-ms:10000}")
    public void pollCurrentTests() {
        Instant end = Instant.now();
        Instant start = end.minus(POLL_RANGE);
        try {
            victoriaMetricsService.detectAndPersistTests(start, end);
        } catch (Exception ex) {
            log.error("Не удалось обновить информацию о текущих нагрузочных тестах", ex);
        }
    }
}
