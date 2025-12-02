package com.example.chronicler.service;

import com.example.chronicler.dto.dropapp.DropAppConfigurationItemDto;
import com.example.chronicler.dto.dropapp.DropAppPodConfiguration;
import com.example.chronicler.model.KubernetesConfiguration;
import com.example.chronicler.repository.KubernetesConfigurationRepository;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class KubernetesConfigurationService {

    private static final Logger log = LoggerFactory.getLogger(KubernetesConfigurationService.class);

    private final KubernetesConfigurationRepository repository;
    private final DropAppService dropAppService;

    public KubernetesConfigurationService(
            KubernetesConfigurationRepository repository, DropAppService dropAppService) {
        this.repository = repository;
        this.dropAppService = dropAppService;
    }

    /** Возвращает сохраненную конфигурацию для указанного теста в формате DTO. */
    public List<DropAppConfigurationItemDto> getConfigurationItems(UUID testUuid) {
        return repository.findByTest(testUuid).stream()
                .map(this::toDto)
                .collect(Collectors.toList());
    }

    /** Возвращает сохраненную конфигурацию Kubernetes по идентификатору теста. */
    public List<KubernetesConfiguration> getConfigurations(UUID testUuid) {
        return repository.findByTest(testUuid);
    }

    /**
     * Загружает текущую конфигурацию DropApp и сохраняет ее для теста, если ранее конфигурация не
     * была зафиксирована.
     */
    public void captureCurrentConfigurationIfAbsent(UUID testUuid) {
        if (testUuid == null) {
            return;
        }
        if (repository.existsForTest(testUuid)) {
            return;
        }
        List<DropAppPodConfiguration> currentConfig = dropAppService.loadCurrentConfiguration();
        if (currentConfig == null || currentConfig.isEmpty()) {
            log.info("Конфигурация DropApp пуста для теста {}", testUuid);
            return;
        }
        Instant now = Instant.now();
        List<KubernetesConfiguration> records = currentConfig.stream()
                .map(item -> toRecord(testUuid, item, now))
                .toList();
        repository.saveAll(testUuid, records);
        log.info("Зафиксирована конфигурация Kubernetes для теста {} ({} записей)", testUuid, records.size());
    }

    private KubernetesConfiguration toRecord(UUID testUuid, DropAppPodConfiguration item, Instant capturedAt) {
        return new KubernetesConfiguration(
                null,
                testUuid,
                item.namespace(),
                item.serviceName(),
                item.podName(),
                item.containerName(),
                item.version(),
                item.release(),
                item.requestCpu(),
                item.limitCpu(),
                item.requestRam(),
                item.limitRam(),
                capturedAt);
    }

    private DropAppConfigurationItemDto toDto(KubernetesConfiguration configuration) {
        return new DropAppConfigurationItemDto(
                configuration.namespace(),
                configuration.serviceName(),
                configuration.podName(),
                configuration.containerName(),
                configuration.version(),
                configuration.release(),
                configuration.requestCpu(),
                configuration.limitCpu(),
                configuration.requestRam(),
                configuration.limitRam());
    }
}
