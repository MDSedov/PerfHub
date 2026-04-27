package com.example.chronicler.service;

import com.example.chronicler.dto.dropapp.DropAppConfigurationItemDto;
import com.example.chronicler.dto.dropapp.DropAppPodConfiguration;
import com.example.chronicler.dto.dropapp.DropAppServiceSelectionDto;
import com.example.chronicler.exception.DropAppClientException;
import com.example.chronicler.model.DropAppConfigurationComponent;
import com.example.chronicler.model.KubernetesConfiguration;
import com.example.chronicler.repository.KubernetesConfigurationRepository;
import com.example.chronicler.util.DropAppConfigurationUtils;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class KubernetesConfigurationService {

    private static final Logger log = LoggerFactory.getLogger(KubernetesConfigurationService.class);

    private final KubernetesConfigurationRepository repository;
    private final DropAppService dropAppService;
    private final StandConfigurationSettingsService standConfigurationSettingsService;

    public KubernetesConfigurationService(
            KubernetesConfigurationRepository repository,
            DropAppService dropAppService,
            StandConfigurationSettingsService standConfigurationSettingsService) {
        this.repository = repository;
        this.dropAppService = dropAppService;
        this.standConfigurationSettingsService = standConfigurationSettingsService;
    }

    /** Возвращает сохраненную конфигурацию для указанного теста в формате DTO. */
    public List<DropAppConfigurationItemDto> getConfigurationItems(UUID testUuid) {
        return getConfigurations(testUuid).stream()
                .map(this::toDto)
                .collect(Collectors.toList());
    }

    public Map<UUID, String> findMaxReleases(Collection<UUID> testUuids) {
        return repository.findMaxReleases(testUuids);
    }

    public Set<UUID> findConfiguredTestIds(Collection<UUID> testUuids) {
        return repository.findConfiguredTestIds(testUuids);
    }

    public String findMaxRelease(UUID testUuid) {
        return repository.findMaxRelease(testUuid);
    }

    public boolean hasConfiguration(UUID testUuid) {
        return repository.existsForTest(testUuid);
    }

    /**
     * Запрашивает актуальную конфигурацию DropApp и сохраняет её для теста, если записи отсутствуют.
     * Возвращает сохранённые (или уже существующие) элементы в формате DTO.
     */
    @Transactional
    public List<DropAppConfigurationItemDto> captureAndGetConfiguration(UUID testUuid) {
        if (testUuid == null) {
            throw new DropAppClientException("testUuid must not be null");
        }
        List<DropAppPodConfiguration> currentConfig = loadStandConfiguration();
        if (currentConfig == null || currentConfig.isEmpty()) {
            throw new DropAppClientException("Конфигурация стенда пуста");
        }
        Instant now = Instant.now();
        List<KubernetesConfiguration> records = currentConfig.stream()
                .map(item -> toRecord(testUuid, item, now))
                .toList();
        repository.deleteByTest(testUuid);
        repository.saveAll(testUuid, records);
        return records.stream().map(this::toDto).toList();
    }

    /** Возвращает сохраненную конфигурацию Kubernetes по идентификатору теста. */
    public List<KubernetesConfiguration> getConfigurations(UUID testUuid) {
        List<KubernetesConfiguration> configurations = repository.findByTest(testUuid);
        if (configurations == null || configurations.isEmpty()) {
            return List.of();
        }
        Instant capturedAt = configurations.stream()
                .map(KubernetesConfiguration::capturedAt)
                .filter(java.util.Objects::nonNull)
                .findFirst()
                .orElse(null);
        return DropAppConfigurationUtils.groupPodItems(configurations.stream()
                        .map(this::toDto)
                        .toList())
                .stream()
                .map(item -> toRecord(testUuid, item, capturedAt))
                .toList();
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
        List<DropAppPodConfiguration> currentConfig = loadStandConfiguration();
        if (currentConfig == null || currentConfig.isEmpty()) {
            log.info("Конфигурация стенда пуста для теста {}", testUuid);
            return;
        }
        Instant now = Instant.now();
        List<KubernetesConfiguration> records = currentConfig.stream()
                .map(item -> toRecord(testUuid, item, now))
                .toList();
        repository.saveAll(testUuid, records);
        log.info("Зафиксирована конфигурация Kubernetes для теста {} ({} записей)", testUuid, records.size());
    }

    /** Пытается сохранить конфигурацию для каждого теста, у которого она отсутствует. */
    public void captureConfigurationsIfMissing(Collection<UUID> testUuids) {
        if (testUuids == null || testUuids.isEmpty()) {
            return;
        }
        for (UUID testUuid : testUuids) {
            try {
                captureCurrentConfigurationIfAbsent(testUuid);
            } catch (Exception ex) {
                log.warn("Не удалось сохранить конфигурацию DropApp для теста {}", testUuid, ex);
            }
        }
    }

    private List<DropAppPodConfiguration> loadStandConfiguration() {
        List<String> namespaces = standConfigurationSettingsService.resolveNamespaces();
        List<DropAppConfigurationComponent> components = standConfigurationSettingsService.resolveComponents();
        List<DropAppServiceSelectionDto> services = standConfigurationSettingsService.resolveServices(namespaces);
        return dropAppService.loadConfiguration(namespaces, components, services);
    }

    private KubernetesConfiguration toRecord(UUID testUuid, DropAppPodConfiguration item, Instant capturedAt) {
        return new KubernetesConfiguration(
                null,
                testUuid,
                item.namespace(),
                item.serviceName(),
                item.podName(),
                item.podCount(),
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
                configuration.podCount(),
                configuration.containerName(),
                configuration.version(),
                configuration.release(),
                configuration.requestCpu(),
                configuration.limitCpu(),
                configuration.requestRam(),
                configuration.limitRam());
    }

    private KubernetesConfiguration toRecord(UUID testUuid, DropAppConfigurationItemDto item, Instant capturedAt) {
        return new KubernetesConfiguration(
                null,
                testUuid,
                item.namespace(),
                item.serviceName(),
                item.podName(),
                item.podCount(),
                item.containerName(),
                item.version(),
                item.release(),
                item.requestCpu(),
                item.limitCpu(),
                item.requestRam(),
                item.limitRam(),
                capturedAt);
    }
}
