package com.example.chronicler.service;

import com.example.chronicler.model.LoadTest;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
public class LoadTestMonitoringScheduler {

    private static final Logger log = LoggerFactory.getLogger(LoadTestMonitoringScheduler.class);
    private static final Duration POLL_RANGE = Duration.ofMinutes(15);
    private static final Duration ACTIVE_TEST_GRACE_PERIOD = Duration.ofMinutes(3);
    private static final String VM_SERVICE = "victoria-metrics";
    private static final String VM_BASE_URL_KEY = "base-url";
    private static final String VM_QUERY_RANGE_PATH_KEY = "query-range-path";
    private static final String VM_DEFAULT_STEP_SECONDS_KEY = "default-step-seconds";
    private static final String VM_TEST_SEARCH_QUERY_KEY = "tests-search-query";

    private final VictoriaMetricsService victoriaMetricsService;
    private final KubernetesConfigurationService kubernetesConfigurationService;
    private final BackgroundErrorService backgroundErrorService;
    private final ConfigurationProvider configurationProvider;
    private boolean missingConfigurationLogged;
    private String lastPublishedFailureMessage;

    public LoadTestMonitoringScheduler(
            VictoriaMetricsService victoriaMetricsService,
            KubernetesConfigurationService kubernetesConfigurationService,
            BackgroundErrorService backgroundErrorService,
            ConfigurationProvider configurationProvider) {
        this.victoriaMetricsService = victoriaMetricsService;
        this.kubernetesConfigurationService = kubernetesConfigurationService;
        this.backgroundErrorService = backgroundErrorService;
        this.configurationProvider = configurationProvider;
    }

    @Scheduled(
            fixedDelayString = "${victoriametrics.poll-delay-ms:60000}",
            initialDelayString = "${victoriametrics.poll-initial-delay-ms:10000}")
    public void pollCurrentTests() {
        Instant end = Instant.now();
        Instant start = end.minus(POLL_RANGE);
        try {
            if (!hasGlobalPollingConfiguration()) {
                logMissingConfigurationOnce();
                return;
            }
            List<LoadTest> detected =
                    victoriaMetricsService.detectAndPersistActiveTests(start, end, ACTIVE_TEST_GRACE_PERIOD);
            List<UUID> testIds = detected.stream().map(LoadTest::id).collect(Collectors.toList());
            kubernetesConfigurationService.captureConfigurationsIfMissing(testIds);
            missingConfigurationLogged = false;
            lastPublishedFailureMessage = null;
        } catch (Exception ex) {
            String message = "Не удалось обновить информацию о текущих нагрузочных тестах";
            log.error(message, ex);
            publishFailureIfChanged(message + ": " + resolveExceptionMessage(ex));
        }
    }

    private boolean hasGlobalPollingConfiguration() {
        return hasText(VM_BASE_URL_KEY)
                && hasText(VM_QUERY_RANGE_PATH_KEY)
                && hasText(VM_DEFAULT_STEP_SECONDS_KEY)
                && hasText(VM_TEST_SEARCH_QUERY_KEY);
    }

    private boolean hasText(String key) {
        Optional<String> value = configurationProvider.findValue(VM_SERVICE, key);
        return value.map(StringUtils::hasText).orElse(false);
    }

    private void logMissingConfigurationOnce() {
        if (missingConfigurationLogged) {
            return;
        }
        log.info(
                "Фоновый мониторинг текущих нагрузочных тестов пропущен: не заполнена глобальная конфигурация {} "
                        + "({}, {}, {}, {}). Ручные запросы пользователя могут работать через пользовательские настройки.",
                VM_SERVICE,
                VM_BASE_URL_KEY,
                VM_QUERY_RANGE_PATH_KEY,
                VM_DEFAULT_STEP_SECONDS_KEY,
                VM_TEST_SEARCH_QUERY_KEY);
        missingConfigurationLogged = true;
        lastPublishedFailureMessage = null;
    }

    private void publishFailureIfChanged(String message) {
        if (message.equals(lastPublishedFailureMessage)) {
            return;
        }
        backgroundErrorService.publish(BackgroundErrorService.LOAD_TESTS_SCOPE, message);
        lastPublishedFailureMessage = message;
    }

    private String resolveExceptionMessage(Exception ex) {
        String message = ex.getMessage();
        return StringUtils.hasText(message) ? message : ex.getClass().getSimpleName();
    }
}
