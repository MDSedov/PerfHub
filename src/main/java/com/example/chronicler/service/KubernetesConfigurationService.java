package com.example.chronicler.service;

import com.example.chronicler.dto.dropapp.DropAppConfigurationItemDto;
import com.example.chronicler.dto.dropapp.DropAppConfigurationSnapshotDetailsDto;
import com.example.chronicler.exception.DropAppClientException;
import com.example.chronicler.model.KubernetesConfiguration;
import com.example.chronicler.repository.KubernetesConfigurationRepository;
import com.example.chronicler.util.DateTimeViewUtils;
import com.example.chronicler.util.DropAppConfigurationUtils;
import com.example.chronicler.util.ReleaseVersionUtils;
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
import org.springframework.web.server.ResponseStatusException;

@Service
public class KubernetesConfigurationService {

    private static final Logger log = LoggerFactory.getLogger(KubernetesConfigurationService.class);

    private final KubernetesConfigurationRepository repository;
    private final DropAppConfigurationSnapshotService snapshotService;

    public KubernetesConfigurationService(
            KubernetesConfigurationRepository repository,
            DropAppConfigurationSnapshotService snapshotService) {
        this.repository = repository;
        this.snapshotService = snapshotService;
    }

    /** Возвращает сохраненную конфигурацию для указанного теста в формате DTO. */
    public List<DropAppConfigurationItemDto> getConfigurationItems(UUID testUuid) {
        return getConfigurationDetails(testUuid).items();
    }

    /** Возвращает детальное представление конфигурации стенда для карточки теста. */
    public DropAppConfigurationSnapshotDetailsDto getConfigurationDetails(UUID testUuid) {
        return snapshotService.findLatestTestSnapshotDetails(testUuid)
                .orElseGet(() -> buildLegacyDetails(testUuid));
    }

    public Map<UUID, String> findMaxReleases(Collection<UUID> testUuids) {
        return repository.findMaxReleases(testUuids);
    }

    public Set<UUID> findConfiguredTestIds(Collection<UUID> testUuids) {
        return repository.findConfiguredTestIds(testUuids);
    }

    public Map<UUID, String> findJiraReleaseIssueUrls(Collection<UUID> testUuids) {
        if (testUuids == null || testUuids.isEmpty()) {
            return Map.of();
        }
        return snapshotService.findLatestJiraReleaseIssueUrlsByTestUuids(testUuids.stream().toList());
    }

    public String findMaxRelease(UUID testUuid) {
        return repository.findMaxRelease(testUuid);
    }

    public boolean hasConfiguration(UUID testUuid) {
        return repository.existsForTest(testUuid);
    }

    /**
     * Запрашивает актуальную конфигурацию стенда и сохраняет её для теста.
     * Возвращает сохранённые (или уже существующие) элементы в формате DTO.
     */
    @Transactional
    public List<DropAppConfigurationItemDto> captureAndGetConfiguration(UUID testUuid) {
        return captureAndGetConfigurationDetails(testUuid).items();
    }

    @Transactional
    public DropAppConfigurationSnapshotDetailsDto captureAndGetConfigurationDetails(UUID testUuid) {
        if (testUuid == null) {
            throw new DropAppClientException("testUuid must not be null");
        }
        DropAppConfigurationSnapshotDetailsDto details = snapshotService.captureTestConfiguration(testUuid);
        Instant now = Instant.now();
        List<KubernetesConfiguration> records = details.items().stream()
                .map(item -> toRecord(testUuid, item, now))
                .toList();
        repository.deleteByTest(testUuid);
        repository.saveAll(testUuid, records);
        return details;
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
     * Загружает текущую конфигурацию стенда и сохраняет ее для теста, если ранее конфигурация не
     * была зафиксирована.
     */
    @Transactional
    public void captureCurrentConfigurationIfAbsent(UUID testUuid) {
        if (testUuid == null) {
            return;
        }
        if (repository.existsForTest(testUuid)) {
            return;
        }
        try {
            DropAppConfigurationSnapshotDetailsDto details = snapshotService.captureTestConfiguration(testUuid);
            Instant now = Instant.now();
            List<KubernetesConfiguration> records = details.items().stream()
                    .map(item -> toRecord(testUuid, item, now))
                    .toList();
            repository.saveAll(testUuid, records);
            log.info("Зафиксирована конфигурация Kubernetes для теста {} ({} записей)", testUuid, records.size());
        } catch (ResponseStatusException ex) {
            log.info("Конфигурация стенда пуста для теста {}", testUuid);
        }
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
                log.warn("Не удалось сохранить конфигурацию стенда для теста {}", testUuid, ex);
            }
        }
    }

    public DropAppConfigurationSnapshotService.SnapshotArchive createConfigurationArchive(UUID testUuid) {
        return snapshotService.createTestSnapshotArchive(testUuid);
    }

    private DropAppConfigurationSnapshotDetailsDto buildLegacyDetails(UUID testUuid) {
        List<KubernetesConfiguration> configurations = getConfigurations(testUuid);
        List<DropAppConfigurationItemDto> items = configurations.stream()
                .map(this::toDto)
                .collect(Collectors.toList());
        Instant capturedAt = configurations.stream()
                .map(KubernetesConfiguration::capturedAt)
                .filter(java.util.Objects::nonNull)
                .findFirst()
                .orElse(null);
        List<String> namespaces = items.stream()
                .map(DropAppConfigurationItemDto::namespace)
                .filter(java.util.Objects::nonNull)
                .map(String::trim)
                .filter(value -> !value.isEmpty())
                .distinct()
                .toList();
        String release = ReleaseVersionUtils.findMaxRelease(items.stream()
                .map(DropAppConfigurationItemDto::release)
                .toList());
        return new DropAppConfigurationSnapshotDetailsDto(
                null,
                DateTimeViewUtils.formatInstant(capturedAt),
                release,
                null,
                null,
                null,
                namespaces,
                List.of(),
                items,
                List.of());
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
