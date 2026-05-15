package com.example.chronicler.service;

import com.example.chronicler.client.JiraClient;
import com.example.chronicler.dto.dropapp.DropAppConfigurationCaptureRequest;
import com.example.chronicler.dto.dropapp.DropAppConfigurationCaptureResponse;
import com.example.chronicler.dto.dropapp.DropAppConfigurationDocumentDto;
import com.example.chronicler.dto.dropapp.DropAppConfigurationItemDto;
import com.example.chronicler.dto.dropapp.DropAppConfigurationSnapshotDetailsDto;
import com.example.chronicler.dto.dropapp.DropAppConfigurationSnapshotSaveRequest;
import com.example.chronicler.dto.dropapp.DropAppConfigurationSnapshotSummaryDto;
import com.example.chronicler.dto.dropapp.DropAppConfigurationSnapshotUpdateRequest;
import com.example.chronicler.dto.dropapp.DropAppConfigurationViewDto;
import com.example.chronicler.dto.dropapp.DropAppNamespaceOptionsDto;
import com.example.chronicler.dto.dropapp.DropAppPodConfiguration;
import com.example.chronicler.dto.dropapp.DropAppServiceOptionDto;
import com.example.chronicler.dto.dropapp.DropAppServiceSelectionDto;
import com.example.chronicler.dto.dropapp.JiraReleaseDistributionDto;
import com.example.chronicler.dto.jira.JiraReleaseDistribution;
import com.example.chronicler.dto.jira.JiraReleaseIssue;
import com.example.chronicler.exception.DropAppClientException;
import com.example.chronicler.exception.JiraClientException;
import com.example.chronicler.model.DropAppConfigurationComponent;
import com.example.chronicler.model.DropAppConfigurationSnapshot;
import com.example.chronicler.model.DropAppConfigurationSnapshotDocument;
import com.example.chronicler.model.DropAppConfigurationSnapshotDistribution;
import com.example.chronicler.model.DropAppConfigurationSnapshotItem;
import com.example.chronicler.repository.DropAppConfigurationSnapshotRepository;
import com.example.chronicler.util.DateTimeViewUtils;
import com.example.chronicler.util.DropAppConfigurationUtils;
import com.example.chronicler.util.ReleaseVersionUtils;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ResponseStatusException;

@Service
public class DropAppConfigurationSnapshotService {

    private static final Logger log = LoggerFactory.getLogger(DropAppConfigurationSnapshotService.class);
    private static final String ARCHIVE_MEDIA_TYPE = "application/zip";

    private final DropAppConfigurationSnapshotRepository repository;
    private final DropAppService dropAppService;
    private final StandConfigurationSettingsService standConfigurationSettingsService;
    private final JiraClient jiraClient;

    public DropAppConfigurationSnapshotService(
            DropAppConfigurationSnapshotRepository repository,
            DropAppService dropAppService,
            StandConfigurationSettingsService standConfigurationSettingsService,
            JiraClient jiraClient) {
        this.repository = repository;
        this.dropAppService = dropAppService;
        this.standConfigurationSettingsService = standConfigurationSettingsService;
        this.jiraClient = jiraClient;
    }

    public DropAppNamespaceOptionsDto getNamespaceOptions() {
        List<String> configuredNamespaces = normalizeNamespaces(dropAppService.getConfiguredNamespaces());
        Set<String> availableNamespaces;
        try {
            availableNamespaces = new LinkedHashSet<>(normalizeNamespaces(dropAppService.listNamespaces()));
        } catch (DropAppClientException ex) {
            if (configuredNamespaces.isEmpty()) {
                throw ex;
            }
            log.warn(
                    "Не удалось получить список namespace из DropApp. Используем namespace из настроек подключения. Причина: {}",
                    ex.getMessage());
            availableNamespaces = new LinkedHashSet<>(configuredNamespaces);
        }
        availableNamespaces.addAll(configuredNamespaces);
        List<String> mergedNamespaces = new ArrayList<>(availableNamespaces);
        mergedNamespaces.sort(String.CASE_INSENSITIVE_ORDER);
        return new DropAppNamespaceOptionsDto(mergedNamespaces, configuredNamespaces);
    }

    public List<DropAppServiceOptionDto> getServiceOptions(List<String> namespaces) {
        return dropAppService.listServiceOptions(namespaces);
    }

    public DropAppConfigurationViewDto loadCurrentConfiguration(List<String> namespaces) {
        List<String> resolvedNamespaces = dropAppService.resolveNamespaces(namespaces);
        List<DropAppConfigurationItemDto> items = DropAppConfigurationUtils.groupPodItems(
                dropAppService.loadConfiguration(resolvedNamespaces).stream()
                        .map(this::toDto)
                        .toList());
        return new DropAppConfigurationViewDto(resolvedNamespaces, resolveRelease(items), items);
    }

    @Transactional
    public DropAppConfigurationCaptureResponse captureCurrentConfiguration(DropAppConfigurationCaptureRequest request) {
        List<String> requestedNamespaces = request != null ? request.namespaces() : List.of();
        List<String> requestedComponents = request != null ? request.components() : List.of();
        List<DropAppServiceSelectionDto> requestedServices = request != null ? request.services() : List.of();
        String comment = request != null ? request.comment() : null;

        List<DropAppConfigurationComponent> resolvedComponents = DropAppConfigurationComponent.resolve(
                requestedComponents,
                standConfigurationSettingsService.resolveComponents());
        List<String> resolvedNamespaces = dropAppService.resolveNamespaces(
                requestedNamespaces == null || requestedNamespaces.isEmpty()
                        ? standConfigurationSettingsService.resolveNamespaces()
                        : requestedNamespaces);
        List<DropAppServiceSelectionDto> resolvedServices = requestedServices == null || requestedServices.isEmpty()
                ? standConfigurationSettingsService.resolveServices(resolvedNamespaces)
                : requestedServices;
        List<DropAppConfigurationItemDto> items = DropAppConfigurationUtils.groupPodItems(
                dropAppService.loadConfiguration(resolvedNamespaces, resolvedComponents, resolvedServices).stream()
                        .map(this::toDto)
                        .toList());
        if (items.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Конфигурация стенда пуста");
        }
        List<DropAppConfigurationSnapshotDocument> documents =
                dropAppService.loadConfigurationDocuments(resolvedNamespaces, resolvedComponents, resolvedServices);

        DropAppConfigurationSnapshotDetailsDto savedSnapshot = persistSnapshot(
                null,
                resolvedNamespaces,
                resolveRelease(items),
                comment,
                resolvedComponents.stream().map(DropAppConfigurationComponent::code).toList(),
                items,
                documents);

        return new DropAppConfigurationCaptureResponse(
                savedSnapshot.id(),
                savedSnapshot.savedAt(),
                savedSnapshot.release(),
                savedSnapshot.jiraReleaseIssueKey(),
                savedSnapshot.jiraReleaseIssueUrl(),
                savedSnapshot.comment(),
                savedSnapshot.namespaces(),
                savedSnapshot.components(),
                savedSnapshot.jiraReleaseDistributions(),
                savedSnapshot.items());
    }

    @Transactional
    public DropAppConfigurationSnapshotDetailsDto captureTestConfiguration(UUID testUuid) {
        if (testUuid == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Идентификатор теста не задан");
        }
        List<String> resolvedNamespaces = dropAppService.resolveNamespaces(
                standConfigurationSettingsService.resolveNamespaces());
        List<DropAppConfigurationComponent> resolvedComponents = standConfigurationSettingsService.resolveComponents();
        List<DropAppServiceSelectionDto> resolvedServices =
                standConfigurationSettingsService.resolveServices(resolvedNamespaces);
        List<DropAppConfigurationItemDto> items = DropAppConfigurationUtils.groupPodItems(
                dropAppService.loadConfiguration(resolvedNamespaces, resolvedComponents, resolvedServices).stream()
                        .map(this::toDto)
                        .toList());
        if (items.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Конфигурация стенда пуста");
        }
        List<DropAppConfigurationSnapshotDocument> documents =
                dropAppService.loadConfigurationDocuments(resolvedNamespaces, resolvedComponents, resolvedServices);

        repository.deleteSnapshotsByTestUuid(testUuid);
        return persistSnapshot(
                testUuid,
                resolvedNamespaces,
                resolveRelease(items),
                null,
                resolvedComponents.stream().map(DropAppConfigurationComponent::code).toList(),
                items,
                documents);
    }

    public List<DropAppConfigurationSnapshotSummaryDto> listSnapshots() {
        return repository.findAllSnapshots().stream()
                .map(this::toSummaryDto)
                .toList();
    }

    public Map<UUID, String> findLatestJiraReleaseIssueUrlsByTestUuids(List<UUID> testUuids) {
        return repository.findLatestJiraReleaseIssueUrlsByTestUuids(testUuids);
    }

    public Optional<DropAppConfigurationSnapshotDetailsDto> findSnapshotDetails(Long snapshotId) {
        if (snapshotId == null) {
            return Optional.empty();
        }
        Optional<DropAppConfigurationSnapshot> snapshotOpt = repository.findSnapshotById(snapshotId);
        if (snapshotOpt.isEmpty()) {
            return Optional.empty();
        }
        List<DropAppConfigurationItemDto> items = DropAppConfigurationUtils.groupPodItems(
                repository.findItemsBySnapshotId(snapshotId).stream()
                        .map(this::toDto)
                        .toList());
        List<DropAppConfigurationDocumentDto> documents = repository.findDocumentsBySnapshotId(snapshotId).stream()
                .map(this::toDocumentDto)
                .toList();
        List<JiraReleaseDistributionDto> distributions = repository.findDistributionsBySnapshotId(snapshotId).stream()
                .map(this::toDto)
                .toList();
        return Optional.of(toDetailsDto(snapshotOpt.get(), items, distributions, documents));
    }

    public Optional<DropAppConfigurationSnapshotDetailsDto> findLatestTestSnapshotDetails(UUID testUuid) {
        if (testUuid == null) {
            return Optional.empty();
        }
        return repository.findLatestSnapshotByTestUuid(testUuid)
                .flatMap(snapshot -> findSnapshotDetails(snapshot.id()));
    }

    public DropAppConfigurationSnapshotDetailsDto saveSnapshot(DropAppConfigurationSnapshotSaveRequest request) {
        if (request == null) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "Нет данных текущей конфигурации DropApp для сохранения");
        }
        return persistSnapshot(
                null,
                request.namespaces(),
                request.release(),
                request.comment(),
                List.of(),
                request.items(),
                List.of());
    }

    public DropAppConfigurationSnapshotDetailsDto updateSnapshot(
            Long snapshotId,
            DropAppConfigurationSnapshotUpdateRequest request) {
        if (snapshotId == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Идентификатор снапшота DropApp не задан");
        }
        DropAppConfigurationSnapshot snapshot = repository.findSnapshotById(snapshotId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "Сохраненная конфигурация DropApp не найдена"));

        String currentRelease = normalizeText(snapshot.release());
        String release = normalizeText(request != null ? request.release() : snapshot.release());
        String comment = normalizeText(request != null ? request.comment() : snapshot.comment());
        String jiraReleaseIssueKey = normalizeText(snapshot.jiraReleaseIssueKey());
        String jiraReleaseIssueUrl = normalizeText(snapshot.jiraReleaseIssueUrl());
        List<DropAppConfigurationSnapshotDistribution> currentDistributions =
                repository.findDistributionsBySnapshotId(snapshotId);
        List<JiraReleaseDistributionDto> jiraReleaseDistributions = null;
        if (!Objects.equals(currentRelease, release)
                || (StringUtils.hasText(release) && (!StringUtils.hasText(jiraReleaseIssueUrl)
                || currentDistributions.isEmpty()
                || hasLegacyMisparsedDistributions(currentDistributions)))) {
            Optional<JiraReleaseIssue> jiraReleaseIssue = resolveJiraReleaseIssue(release);
            jiraReleaseIssueKey = jiraReleaseIssue.map(JiraReleaseIssue::key).orElse(null);
            jiraReleaseIssueUrl = jiraReleaseIssue.map(JiraReleaseIssue::url).orElse(null);
            jiraReleaseDistributions = resolveJiraReleaseDistributions(release);
        }

        repository.updateSnapshot(
                snapshotId,
                release,
                jiraReleaseIssueKey,
                jiraReleaseIssueUrl,
                comment);
        if (jiraReleaseDistributions != null) {
            repository.deleteDistributionsBySnapshotId(snapshotId);
            repository.saveDistributions(
                    snapshotId,
                    jiraReleaseDistributions.stream()
                            .map(distribution -> toSnapshotDistribution(snapshotId, distribution))
                            .toList());
        }

        return findSnapshotDetails(snapshotId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.INTERNAL_SERVER_ERROR,
                        "Не удалось загрузить обновленную конфигурацию DropApp"));
    }

    public void deleteSnapshot(Long snapshotId) {
        if (snapshotId == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Идентификатор снапшота DropApp не задан");
        }
        int deletedRows = repository.deleteSnapshot(snapshotId);
        if (deletedRows == 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Сохраненная конфигурация DropApp не найдена");
        }
    }

    public SnapshotArchive createSnapshotArchive(Long snapshotId) {
        DropAppConfigurationSnapshotDetailsDto details = findSnapshotDetails(snapshotId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "Сохраненная конфигурация DropApp не найдена"));
        List<DropAppConfigurationSnapshotDocument> documents = repository.findDocumentsBySnapshotId(snapshotId);

        try (ByteArrayOutputStream buffer = new ByteArrayOutputStream();
                ZipOutputStream zip = new ZipOutputStream(buffer, StandardCharsets.UTF_8)) {
            writeZipEntry(zip, "snapshot-info.txt", buildSnapshotInfo(details));
            writeZipEntry(zip, "resources.csv", buildResourcesCsv(details.items()));
            if (details.jiraReleaseDistributions() != null && !details.jiraReleaseDistributions().isEmpty()) {
                writeZipEntry(zip, "jira-release-distributions.csv",
                        buildJiraReleaseDistributionsCsv(details.jiraReleaseDistributions()));
            }
            for (DropAppConfigurationSnapshotDocument document : documents) {
                writeZipEntry(
                        zip,
                        buildDocumentArchivePath(document.namespace(), document.componentCode(), document.resourceName()),
                        document.content());
            }
            zip.finish();
            return new SnapshotArchive(buildArchiveFileName(details), buffer.toByteArray(), ARCHIVE_MEDIA_TYPE);
        } catch (IOException ex) {
            throw new ResponseStatusException(
                    HttpStatus.INTERNAL_SERVER_ERROR,
                    "Не удалось сформировать ZIP-архив конфигурации DropApp",
                    ex);
        }
    }

    public SnapshotArchive createTestSnapshotArchive(UUID testUuid) {
        DropAppConfigurationSnapshotDetailsDto details = findLatestTestSnapshotDetails(testUuid)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "Конфигурация стенда для теста не найдена"));
        return createSnapshotArchive(details.id());
    }

    private DropAppConfigurationSnapshotDetailsDto persistSnapshot(
            UUID testUuid,
            List<String> requestedNamespaces,
            String requestedRelease,
            String requestedComment,
            List<String> requestedComponents,
            List<DropAppConfigurationItemDto> requestedItems,
            List<DropAppConfigurationSnapshotDocument> requestedDocuments) {
        if (requestedItems == null || requestedItems.isEmpty()) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "Нет данных текущей конфигурации DropApp для сохранения");
        }

        List<DropAppConfigurationItemDto> items = DropAppConfigurationUtils.groupPodItems(requestedItems.stream()
                .filter(Objects::nonNull)
                .map(this::normalizeItem)
                .toList());
        if (items.isEmpty()) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "Нет данных текущей конфигурации DropApp для сохранения");
        }

        List<String> namespaces = normalizeNamespaces(requestedNamespaces);
        if (namespaces.isEmpty()) {
            namespaces = normalizeNamespaces(items.stream()
                    .map(DropAppConfigurationItemDto::namespace)
                    .collect(Collectors.toList()));
        }

        String release = normalizeText(requestedRelease);
        if (release == null) {
            release = resolveRelease(items);
        }
        String comment = normalizeText(requestedComment);
        String serializedNamespaces = namespaces.isEmpty() ? null : String.join(", ", namespaces);
        List<String> componentCodes = normalizeComponents(requestedComponents);
        String serializedComponents = componentCodes.isEmpty() ? null : String.join(", ", componentCodes);
        Optional<JiraReleaseIssue> jiraReleaseIssue = resolveJiraReleaseIssue(release);
        List<JiraReleaseDistributionDto> jiraReleaseDistributions = resolveJiraReleaseDistributions(release);
        Instant now = Instant.now();

        long snapshotId = repository.insertSnapshot(
                testUuid,
                release,
                jiraReleaseIssue.map(JiraReleaseIssue::key).orElse(null),
                jiraReleaseIssue.map(JiraReleaseIssue::url).orElse(null),
                comment,
                serializedNamespaces,
                serializedComponents,
                now);
        repository.saveItems(
                snapshotId,
                items.stream()
                        .map(item -> toSnapshotItem(snapshotId, item))
                        .toList());
        repository.saveDocuments(
                snapshotId,
                normalizeDocuments(requestedDocuments).stream()
                        .map(document -> withSnapshotId(snapshotId, document))
                        .toList());
        repository.saveDistributions(
                snapshotId,
                jiraReleaseDistributions.stream()
                        .map(distribution -> toSnapshotDistribution(snapshotId, distribution))
                        .toList());

        return findSnapshotDetails(snapshotId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.INTERNAL_SERVER_ERROR,
                        "Не удалось загрузить сохраненную конфигурацию DropApp"));
    }

    private List<DropAppConfigurationSnapshotDocument> normalizeDocuments(
            List<DropAppConfigurationSnapshotDocument> documents) {
        if (documents == null || documents.isEmpty()) {
            return List.of();
        }
        return documents.stream()
                .filter(Objects::nonNull)
                .map(document -> new DropAppConfigurationSnapshotDocument(
                        null,
                        null,
                        normalizeText(document.namespace()),
                        normalizeComponent(document.componentCode()),
                        normalizeText(document.resourceName()),
                        normalizeDocumentContent(document.content())))
                .filter(document -> StringUtils.hasText(document.componentCode())
                        && StringUtils.hasText(document.resourceName())
                        && StringUtils.hasText(document.content()))
                .toList();
    }

    private DropAppConfigurationSnapshotDocument withSnapshotId(
            long snapshotId,
            DropAppConfigurationSnapshotDocument document) {
        return new DropAppConfigurationSnapshotDocument(
                null,
                snapshotId,
                document.namespace(),
                document.componentCode(),
                document.resourceName(),
                document.content());
    }

    private DropAppConfigurationSnapshotSummaryDto toSummaryDto(DropAppConfigurationSnapshot snapshot) {
        return new DropAppConfigurationSnapshotSummaryDto(
                snapshot.id(),
                DateTimeViewUtils.formatInstant(snapshot.savedAt()),
                normalizeText(snapshot.release()),
                normalizeText(snapshot.jiraReleaseIssueKey()),
                normalizeText(snapshot.jiraReleaseIssueUrl()),
                normalizeText(snapshot.comment()));
    }

    private DropAppConfigurationSnapshotDetailsDto toDetailsDto(
            DropAppConfigurationSnapshot snapshot,
            List<DropAppConfigurationItemDto> items,
            List<JiraReleaseDistributionDto> distributions,
            List<DropAppConfigurationDocumentDto> documents) {
        return new DropAppConfigurationSnapshotDetailsDto(
                snapshot.id(),
                DateTimeViewUtils.formatInstant(snapshot.savedAt()),
                normalizeText(snapshot.release()),
                normalizeText(snapshot.jiraReleaseIssueKey()),
                normalizeText(snapshot.jiraReleaseIssueUrl()),
                normalizeText(snapshot.comment()),
                parseCommaSeparatedValues(snapshot.namespaces()),
                parseComponents(snapshot.components()),
                items,
                distributions != null ? distributions : List.of(),
                documents);
    }

    private DropAppConfigurationItemDto toDto(DropAppPodConfiguration item) {
        return new DropAppConfigurationItemDto(
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
                item.limitRam());
    }

    private DropAppConfigurationItemDto toDto(DropAppConfigurationSnapshotItem item) {
        return new DropAppConfigurationItemDto(
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
                item.limitRam());
    }

    private DropAppConfigurationDocumentDto toDocumentDto(DropAppConfigurationSnapshotDocument document) {
        return new DropAppConfigurationDocumentDto(
                document.namespace(),
                document.componentCode(),
                document.resourceName(),
                buildDocumentFileName(document.namespace(), document.componentCode(), document.resourceName()));
    }

    private JiraReleaseDistributionDto toDto(DropAppConfigurationSnapshotDistribution distribution) {
        return new JiraReleaseDistributionDto(
                distribution.serviceName(),
                distribution.serviceUrl(),
                distribution.distributionName(),
                distribution.distributionUrl());
    }

    private JiraReleaseDistributionDto toDto(JiraReleaseDistribution distribution) {
        return new JiraReleaseDistributionDto(
                distribution.serviceName(),
                distribution.serviceUrl(),
                distribution.distributionName(),
                distribution.distributionUrl());
    }

    private DropAppConfigurationItemDto normalizeItem(DropAppConfigurationItemDto item) {
        return new DropAppConfigurationItemDto(
                normalizeText(item.namespace()),
                normalizeText(item.serviceName()),
                normalizeText(item.podName()),
                item.podCount() != null && item.podCount() > 0 ? item.podCount() : null,
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
                item.podCount(),
                item.containerName(),
                item.version(),
                item.release(),
                item.requestCpu(),
                item.limitCpu(),
                item.requestRam(),
                item.limitRam());
    }

    private DropAppConfigurationSnapshotDistribution toSnapshotDistribution(
            long snapshotId,
            JiraReleaseDistributionDto distribution) {
        return new DropAppConfigurationSnapshotDistribution(
                null,
                snapshotId,
                normalizeText(distribution.serviceName()),
                normalizeText(distribution.serviceUrl()),
                normalizeText(distribution.distributionName()),
                normalizeText(distribution.distributionUrl()));
    }

    private String resolveRelease(Collection<DropAppConfigurationItemDto> items) {
        if (items == null || items.isEmpty()) {
            return null;
        }
        return ReleaseVersionUtils.findMaxRelease(items.stream()
                .map(DropAppConfigurationItemDto::release)
                .toList());
    }

    private Optional<JiraReleaseIssue> resolveJiraReleaseIssue(String release) {
        if (!StringUtils.hasText(release)) {
            return Optional.empty();
        }
        if (!jiraClient.isConfiguredForReleaseLookup()) {
            log.info("Jira lookup для релиза {} пропущен: не заполнены базовый URL, токен или название проекта", release);
            return Optional.empty();
        }
        try {
            Optional<JiraReleaseIssue> issue = jiraClient.findReleaseIssueByRelease(release);
            if (issue.isEmpty()) {
                log.info("Задача релиза {} в Jira не найдена", release);
            }
            return issue;
        } catch (JiraClientException ex) {
            log.warn("Не удалось найти задачу релиза {} в Jira: {}", release, ex.getMessage());
            return Optional.empty();
        }
    }

    private List<JiraReleaseDistributionDto> resolveJiraReleaseDistributions(String release) {
        if (!StringUtils.hasText(release)) {
            return List.of();
        }
        if (!jiraClient.isConfiguredForReleaseLookup()) {
            return List.of();
        }
        try {
            List<JiraReleaseDistributionDto> distributions = jiraClient.findReleaseDistributionsByRelease(release).stream()
                    .map(this::toDto)
                    .filter(distribution -> StringUtils.hasText(distribution.serviceName())
                            && StringUtils.hasText(distribution.distributionName()))
                    .toList();
            if (distributions.isEmpty()) {
                log.info("Дистрибутивы релиза {} в инфраструктурной задаче Jira не найдены", release);
            }
            return distributions;
        } catch (JiraClientException ex) {
            log.warn("Не удалось найти дистрибутивы релиза {} в Jira: {}", release, ex.getMessage());
            return List.of();
        }
    }

    private boolean hasLegacyMisparsedDistributions(
            List<DropAppConfigurationSnapshotDistribution> distributions) {
        if (distributions == null || distributions.isEmpty()) {
            return false;
        }
        return distributions.stream().anyMatch(distribution ->
                !StringUtils.hasText(distribution.serviceUrl())
                        && isReadyStatus(distribution.serviceName())
                        && containsIgnoreCase(distribution.distributionUrl(), "dpm"));
    }

    private boolean isReadyStatus(String value) {
        String normalized = normalizeText(value);
        return normalized != null
                && (normalized.equalsIgnoreCase("готов") || normalized.equalsIgnoreCase("готово"));
    }

    private boolean containsIgnoreCase(String value, String search) {
        return value != null && search != null
                && value.toLowerCase(Locale.ROOT).contains(search.toLowerCase(Locale.ROOT));
    }

    private List<String> parseComponents(String components) {
        return parseCommaSeparatedValues(components).stream()
                .map(this::normalizeComponent)
                .filter(StringUtils::hasText)
                .toList();
    }

    private List<String> parseCommaSeparatedValues(String value) {
        if (!StringUtils.hasText(value)) {
            return List.of();
        }
        return normalizeNamespaces(List.of(value.split(",")));
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

    private List<String> normalizeComponents(List<String> components) {
        if (components == null || components.isEmpty()) {
            return List.of();
        }
        return components.stream()
                .map(this::normalizeComponent)
                .filter(StringUtils::hasText)
                .distinct()
                .toList();
    }

    private String normalizeComponent(String component) {
        String normalized = normalizeText(component);
        if (normalized == null) {
            return null;
        }
        try {
            return DropAppConfigurationComponent.fromCode(normalized).code();
        } catch (IllegalArgumentException ignored) {
            return normalized.toLowerCase(Locale.ROOT);
        }
    }

    private String normalizeText(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        return value.trim();
    }

    private String normalizeDocumentContent(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        return value.replace("\r\n", "\n");
    }

    private String buildSnapshotInfo(DropAppConfigurationSnapshotDetailsDto details) {
        List<String> lines = new ArrayList<>();
        lines.add("Snapshot ID: " + valueOrDash(details.id()));
        lines.add("Saved At: " + valueOrDash(details.savedAt()));
        lines.add("Release: " + valueOrDash(details.release()));
        lines.add("Jira Release Issue: " + valueOrDash(details.jiraReleaseIssueKey()));
        lines.add("Jira Release Issue URL: " + valueOrDash(details.jiraReleaseIssueUrl()));
        lines.add("Jira Release Distributions: "
                + (details.jiraReleaseDistributions() != null ? details.jiraReleaseDistributions().size() : 0));
        lines.add("Comment: " + valueOrDash(details.comment()));
        lines.add("Namespaces: " + joinOrDash(details.namespaces()));
        lines.add("Components: " + joinOrDash(details.components()));
        lines.add("YAML Documents: " + (details.documents() != null ? details.documents().size() : 0));
        return String.join("\n", lines) + "\n";
    }

    private String buildResourcesCsv(List<DropAppConfigurationItemDto> items) {
        StringBuilder builder = new StringBuilder();
        builder.append("Namespace,Service,Pod Count,Release,Container,Version,Request CPU,Limit CPU,Request RAM,Limit RAM\n");
        for (DropAppConfigurationItemDto item : items) {
            builder.append(csv(item.namespace())).append(',')
                    .append(csv(item.serviceName())).append(',')
                    .append(csv(item.podCount() != null ? item.podCount().toString() : "")).append(',')
                    .append(csv(item.release())).append(',')
                    .append(csv(item.containerName())).append(',')
                    .append(csv(item.version())).append(',')
                    .append(csv(item.requestCpu())).append(',')
                    .append(csv(item.limitCpu())).append(',')
                    .append(csv(item.requestRam())).append(',')
                    .append(csv(item.limitRam())).append('\n');
        }
        return builder.toString();
    }

    private String buildJiraReleaseDistributionsCsv(List<JiraReleaseDistributionDto> distributions) {
        StringBuilder builder = new StringBuilder();
        builder.append("Service in DPM,DPM URL,Distribution in Nexus,Nexus URL\n");
        for (JiraReleaseDistributionDto distribution : distributions) {
            builder.append(csv(distribution.serviceName())).append(',')
                    .append(csv(distribution.serviceUrl())).append(',')
                    .append(csv(distribution.distributionName())).append(',')
                    .append(csv(distribution.distributionUrl())).append('\n');
        }
        return builder.toString();
    }

    private String csv(String value) {
        String normalized = value != null ? value : "";
        return "\"" + normalized.replace("\"", "\"\"") + "\"";
    }

    private void writeZipEntry(ZipOutputStream zip, String path, String content) throws IOException {
        ZipEntry entry = new ZipEntry(path);
        zip.putNextEntry(entry);
        if (content != null) {
            zip.write(content.getBytes(StandardCharsets.UTF_8));
        }
        zip.closeEntry();
    }

    private String buildArchiveFileName(DropAppConfigurationSnapshotDetailsDto details) {
        String releasePart = sanitizeFileSegment(details.release());
        if (!StringUtils.hasText(releasePart)) {
            releasePart = "snapshot";
        }
        return "dropapp-configuration-%d-%s.zip".formatted(details.id(), releasePart);
    }

    private String buildDocumentArchivePath(String namespace, String componentCode, String resourceName) {
        String namespacePart = sanitizeFileSegment(namespace);
        String componentPart = sanitizeFileSegment(componentCode);
        String resourcePart = sanitizeFileSegment(resourceName);
        return "yaml/%s/%s/%s.yaml".formatted(
                StringUtils.hasText(namespacePart) ? namespacePart : "cluster",
                StringUtils.hasText(componentPart) ? componentPart : "resource",
                StringUtils.hasText(resourcePart) ? resourcePart : "document");
    }

    private String buildDocumentFileName(String namespace, String componentCode, String resourceName) {
        return buildDocumentArchivePath(namespace, componentCode, resourceName);
    }

    private String sanitizeFileSegment(String value) {
        String normalized = normalizeText(value);
        if (normalized == null) {
            return null;
        }
        return normalized
                .replace('\\', '-')
                .replace('/', '-')
                .replace(':', '-')
                .replace('*', '-')
                .replace('?', '-')
                .replace('"', '-')
                .replace('<', '-')
                .replace('>', '-')
                .replace('|', '-')
                .replaceAll("\\s+", "-");
    }

    private String joinOrDash(List<String> values) {
        if (values == null || values.isEmpty()) {
            return "—";
        }
        return values.stream()
                .filter(StringUtils::hasText)
                .collect(Collectors.joining(", "));
    }

    private String valueOrDash(Object value) {
        if (value == null) {
            return "—";
        }
        String text = value.toString().trim();
        return text.isEmpty() ? "—" : text;
    }

    public record SnapshotArchive(String fileName, byte[] content, String contentType) {}
}
