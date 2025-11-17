package com.example.chronicler.service;

import com.example.chronicler.client.ConfluenceClient;
import com.example.chronicler.client.GrafanaClient;
import com.example.chronicler.client.VictoriaMetricsClient;
import com.example.chronicler.dto.configuration.ConnectionTestResponse;
import com.example.chronicler.dto.confluence.ConfluenceCredentials;
import java.time.Duration;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class ConnectionTestService {

    private static final Logger log = LoggerFactory.getLogger(ConnectionTestService.class);
    private static final Duration VM_TEST_RANGE = Duration.ofMinutes(1);
    private static final String CONFLUENCE_SERVICE = "confluence";
    private static final String CONFLUENCE_TOKEN_KEY = "personal-access-token";

    private final VictoriaMetricsClient victoriaMetricsClient;
    private final GigachatService gigachatService;
    private final GrafanaClient grafanaClient;
    private final ConfluenceClient confluenceClient;
    private final DropAppService dropAppService;
    private final ConfigurationProvider configurationProvider;

    public ConnectionTestService(
            VictoriaMetricsClient victoriaMetricsClient,
            GigachatService gigachatService,
            GrafanaClient grafanaClient,
            ConfluenceClient confluenceClient,
            DropAppService dropAppService,
            ConfigurationProvider configurationProvider) {
        this.victoriaMetricsClient = victoriaMetricsClient;
        this.gigachatService = gigachatService;
        this.grafanaClient = grafanaClient;
        this.confluenceClient = confluenceClient;
        this.dropAppService = dropAppService;
        this.configurationProvider = configurationProvider;
    }

    public ConnectionTestResponse testConnection(String rawService) {
        String service = rawService != null ? rawService.trim() : "";
        if (!StringUtils.hasText(service)) {
            throw new IllegalArgumentException("service must not be blank");
        }
        return switch (service) {
            case "victoria-metrics" -> testVictoriaMetrics();
            case "gigachat" -> testGigachat();
            case "grafana" -> testGrafana();
            case "confluence" -> testConfluence();
            case "dropapp" -> testDropApp();
            default -> new ConnectionTestResponse(
                    service, false, "Проверка для сервиса '" + service + "' не поддерживается");
        };
    }

    private ConnectionTestResponse testVictoriaMetrics() {
        String service = "victoria-metrics";
        try {
            Instant now = Instant.now();
            victoriaMetricsClient.queryRange("1", now.minus(VM_TEST_RANGE), now);
            return success(service, "Victoria Metrics отвечает на запросы.");
        } catch (Exception ex) {
            return failure(service, ex);
        }
    }

    private ConnectionTestResponse testGigachat() {
        String service = "gigachat";
        try {
            gigachatService.fetchAccessToken();
            return success(service, "GigaChat выдал токен доступа.");
        } catch (Exception ex) {
            return failure(service, ex);
        }
    }

    private ConnectionTestResponse testGrafana() {
        String service = "grafana";
        try {
            String status = grafanaClient.checkHealth();
            return success(service, "Grafana ответила: " + status);
        } catch (Exception ex) {
            return failure(service, ex);
        }
    }

    private ConnectionTestResponse testConfluence() {
        String service = "confluence";
        try {
            String token = configurationProvider.getRequiredValue(CONFLUENCE_SERVICE, CONFLUENCE_TOKEN_KEY);
            ConfluenceCredentials credentials = new ConfluenceCredentials(token, token);
            String status = confluenceClient.checkConnection(credentials);
            return success(service, "Confluence ответила: " + status);
        } catch (Exception ex) {
            return failure(service, ex);
        }
    }

    private ConnectionTestResponse testDropApp() {
        String service = "dropapp";
        try {
            dropAppService.loadCurrentConfiguration();
            return success(service, "DropApp вернул ответ на запрос конфигурации.");
        } catch (Exception ex) {
            return failure(service, ex);
        }
    }

    private ConnectionTestResponse success(String service, String message) {
        return new ConnectionTestResponse(service, true, message);
    }

    private ConnectionTestResponse failure(String service, Exception ex) {
        String message = ex.getMessage();
        if (!StringUtils.hasText(message)) {
            message = ex.getClass().getSimpleName();
        }
        log.warn("Connection test for {} failed: {}", service, message);
        if (log.isDebugEnabled()) {
            log.debug("Connection test error details", ex);
        }
        return new ConnectionTestResponse(service, false, message);
    }
}
