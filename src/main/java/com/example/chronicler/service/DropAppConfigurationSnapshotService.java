package com.example.chronicler.service;

import com.example.chronicler.dto.dropapp.DropAppConfigurationItemDto;
import com.example.chronicler.dto.dropapp.DropAppConfigurationSnapshotDetailsDto;
import com.example.chronicler.dto.dropapp.DropAppConfigurationSnapshotSaveRequest;
import com.example.chronicler.dto.dropapp.DropAppConfigurationSnapshotSummaryDto;
import com.example.chronicler.dto.dropapp.DropAppConfigurationViewDto;
import com.example.chronicler.dto.dropapp.DropAppNamespaceOptionsDto;
import com.example.chronicler.dto.dropapp.DropAppPodConfiguration;
import com.example.chronicler.model.DropAppConfigurationSnapshot;
import com.example.chronicler.model.DropAppConfigurationSnapshotItem;
import com.example.chronicler.repository.DropAppConfigurationSnapshotRepository;
import com.example.chronicler.util.DateTimeViewUtils;
import com.example.chronicler.util.ReleaseVersionUtils;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.util.StringUtils;

@Service
public class DropAppConfigurationSnapshotService {

    private final DropAppConfigurationSnapshotRepository repository;
    private final DropAppService dropAppService;

    public DropAppConfigurationSnapshotService(
            DropAppConfigurationSnapshotRepository repository, DropAppService dropAppService) {
        this.repository = repository;
        this.dropAppService = dropAppService;
    }

    public DropAppNamespaceOptionsDto getNamespaceOptions() {
        List<String> configuredNamespaces = normalizeNamespaces(dropAppService.getConfiguredNamespaces());
        Set<String> availableNamespaces = new LinkedHashSet<>(normalizeNamespaces(dropAppService.listNamespaces()));
        availableNamespaces.addAll(configuredNamespaces);
        List<String> mergedNamespaces = new ArrayList<>(availableNamespaces);
        mergedNamespaces.sort(String.CASE_INSENSITIVE_ORDER);
        return new DropAppNamespaceOptionsDto(mergedNamespaces, configuredNamespaces);
    }

    public DropAppConfigurationViewDto loadCurrentConfiguration(List<String> namespaces) {
        List<String> resolvedNamespaces = dropAppService.resolveNamespaces(namespaces);
        List<DropAppConfigurationItemDto> items = dropAppService.loadConfiguration(resolvedNamespaces).stream()
                .map(this::toDto)
                .toList();
        return new DropAppConfigurationViewDto(resolvedNamespaces, resolveRelease(items), items);
    }

    public List<DropAppConfigurationSnapshotSummaryDto> listSnapshots() {
        return repository.findAllSnapshots().stream()
                .map(this::toSummaryDto)
                .toList();
    }

    public Optional<DropAppConfigurationSnapshotDetailsDto> findSnapshotDetails(Long snapshotId) {
        if (snapshotId == null) {
            return Optional.empty();
        }
        Optional<DropAppConfigurationSnapshot> snapshotOpt = repository.findSnapshotById(snapshotId);
        if (snapshotOpt.isEmpty()) {
            return Optional.empty();
        }
        List<DropAppConfigurationItemDto> items = repository.findItemsBySnapshotId(snapshotId).stream()
                .map(this::toDto)
                .toList();
        return Optional.of(toDetailsDto(snapshotOpt.get(), items));
    }

    public DropAppConfigurationSnapshotDetailsDto saveSnapshot(DropAppConfigurationSnapshotSaveRequest request) {
        if (request == null || request.items() == null || request.items().isEmpty()) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "Нет данных текущей конфигурации DropApp для сохранения");
        }

        List<DropAppConfigurationItemDto> items = request.items().stream()
                .filter(Objects::nonNull)
                .map(this::normalizeItem)
                .toList();
        if (items.isEmpty()) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "Нет данных текущей конфигурации DropApp для сохранения");
        }

        List<String> namespaces = normalizeNamespaces(request.namespaces());
        if (namespaces.isEmpty()) {
            namespaces = normalizeNamespaces(items.stream()
                    .map(DropAppConfigurationItemDto::namespace)
                    .collect(Collectors.toList()));
        }

        String release = normalizeText(request.release());
        if (release == null) {
            release = resolveRelease(items);
        }
        String comment = normalizeText(request.comment());
        String serializedNamespaces = namespaces.isEmpty() ? null : String.join(", ", namespaces);
        Instant now = Instant.now();

        long snapshotId = repository.insertSnapshot(release, comment, serializedNamespaces, now);
        repository.saveItems(
                snapshotId,
                items.stream()
                        .map(item -> toSnapshotItem(snapshotId, item))
                        .toList());

        return findSnapshotDetails(snapshotId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.INTERNAL_SERVER_ERROR,
                        "Не удалось загрузить сохраненную конфигурацию DropApp"));
    }

    private DropAppConfigurationSnapshotSummaryDto toSummaryDto(DropAppConfigurationSnapshot snapshot) {
        return new DropAppConfigurationSnapshotSummaryDto(
                snapshot.id(),
                DateTimeViewUtils.formatInstant(snapshot.savedAt()),
                normalizeText(snapshot.release()),
                normalizeText(snapshot.comment()));
    }

    private DropAppConfigurationSnapshotDetailsDto toDetailsDto(
            DropAppConfigurationSnapshot snapshot, List<DropAppConfigurationItemDto> items) {
        return new DropAppConfigurationSnapshotDetailsDto(
                snapshot.id(),
                DateTimeViewUtils.formatInstant(snapshot.savedAt()),
                normalizeText(snapshot.release()),
                normalizeText(snapshot.comment()),
                parseNamespaces(snapshot.namespaces()),
                items);
    }

    private DropAppConfigurationItemDto toDto(DropAppPodConfiguration item) {
        return new DropAppConfigurationItemDto(
                item.namespace(),
                item.serviceName(),
                item.podName(),
                item.containerName(),
                item.version(),
                item.release(),
                item.requestCpu(),
                item.limitCpu(),
                item.requestRam(),
                item.limitRam());
    }

    private DropAppConfigurationItemDto toDto(DropAppConfigurationSnapshotItem item) {
        return new DropAppConfigurationItemDto(
                item.namespace(),
                item.serviceName(),
                item.podName(),
                item.containerName(),
                item.version(),
                item.release(),
                item.requestCpu(),
                item.limitCpu(),
                item.requestRam(),
                item.limitRam());
    }

    private DropAppConfigurationItemDto normalizeItem(DropAppConfigurationItemDto item) {
        return new DropAppConfigurationItemDto(
                normalizeText(item.namespace()),
                normalizeText(item.serviceName()),
                normalizeText(item.podName()),
                normalizeText(item.containerName()),
                normalizeText(item.version()),
                normalizeText(item.release()),
                normalizeText(item.requestCpu()),
                normalizeText(item.limitCpu()),
                normalizeText(item.requestRam()),
                normalizeText(item.limitRam()));
    }

    private DropAppConfigurationSnapshotItem toSnapshotItem(long snapshotId, DropAppConfigurationItemDto item) {
        return new DropAppConfigurationSnapshotItem(
                null,
                snapshotId,
                item.namespace(),
                item.serviceName(),
                item.podName(),
                item.containerName(),
                item.version(),
                item.release(),
                item.requestCpu(),
                item.limitCpu(),
                item.requestRam(),
                item.limitRam());
    }

    private String resolveRelease(Collection<DropAppConfigurationItemDto> items) {
        if (items == null || items.isEmpty()) {
            return null;
        }
        return ReleaseVersionUtils.findMaxRelease(items.stream()
                .map(DropAppConfigurationItemDto::release)
                .toList());
    }

    private List<String> parseNamespaces(String namespaces) {
        if (!StringUtils.hasText(namespaces)) {
            return List.of();
        }
        return normalizeNamespaces(List.of(namespaces.split(",")));
    }

    private List<String> normalizeNamespaces(List<String> namespaces) {
        if (namespaces == null || namespaces.isEmpty()) {
            return List.of();
        }
        return namespaces.stream()
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(StringUtils::hasText)
                .distinct()
                .toList();
    }

    private String normalizeText(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        return value.trim();
    }
}
