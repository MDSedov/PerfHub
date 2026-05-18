package com.example.chronicler.service;

import com.example.chronicler.client.GrafanaClient;
import com.example.chronicler.dto.grafana.GrafanaDashboardDefinition;
import com.example.chronicler.dto.grafana.GrafanaPanelDefinition;
import com.example.chronicler.dto.grafana.GrafanaVariableDefinition;
import com.example.chronicler.dto.grafana.GrafanaVariableOption;
import com.example.chronicler.dto.settings.GrafanaDashboardDetailsDto;
import com.example.chronicler.dto.settings.GrafanaDashboardPanelDto;
import com.example.chronicler.dto.settings.GrafanaDashboardPanelUpdateRequest;
import com.example.chronicler.dto.settings.GrafanaDashboardVariableDto;
import com.example.chronicler.dto.settings.GrafanaDashboardVariableUpdateRequest;
import com.example.chronicler.dto.settings.GrafanaVariableOptionDto;
import com.example.chronicler.model.GrafanaDashboard;
import com.example.chronicler.repository.GrafanaDashboardPanelRepository;
import com.example.chronicler.repository.GrafanaDashboardRepository;
import com.example.chronicler.repository.GrafanaDashboardVariableRepository;
import com.example.chronicler.repository.model.GrafanaDashboardPanelRecord;
import com.example.chronicler.repository.model.GrafanaDashboardVariableRecord;
import com.example.chronicler.repository.model.GrafanaVariableOptionRecord;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
public class GrafanaDashboardSettingsService {

    private final GrafanaDashboardRepository repository;
    private final GrafanaDashboardPanelRepository panelRepository;
    private final GrafanaDashboardVariableRepository variableRepository;
    private final GrafanaClient grafanaClient;

    public GrafanaDashboardSettingsService(
            GrafanaDashboardRepository repository,
            GrafanaDashboardPanelRepository panelRepository,
            GrafanaDashboardVariableRepository variableRepository,
            GrafanaClient grafanaClient) {
        this.repository = repository;
        this.panelRepository = panelRepository;
        this.variableRepository = variableRepository;
        this.grafanaClient = grafanaClient;
    }

    public List<GrafanaDashboardDetailsDto> listDashboards() {
        Map<String, List<GrafanaDashboardPanelRecord>> panelsByUid = panelRepository.findAllGroupedByUid();
        Map<String, List<GrafanaDashboardVariableRecord>> variablesByUid =
                variableRepository.findAllGroupedByUid();
        return repository.findAll().stream()
                .map(saved -> toDetailsFromCache(
                        saved,
                        panelsByUid.get(normalizeUid(saved.uid())),
                        variablesByUid.get(normalizeUid(saved.uid()))))
                .toList();
    }

    public List<GrafanaDashboardDetailsDto> pullFromGrafana() {
        List<GrafanaDashboard> saved = repository.findAll();
        Map<String, List<GrafanaDashboardPanelRecord>> cachedPanelsByUid = panelRepository.findAllGroupedByUid();
        Map<String, List<GrafanaDashboardVariableRecord>> cachedVariablesByUid =
                variableRepository.findAllGroupedByUid();
        var savedByUid = saved.stream()
                .collect(Collectors.toMap(
                        item -> normalizeUid(item.uid()),
                        item -> item,
                        (first, second) -> first));
        List<GrafanaDashboardDetailsDto> discovered = grafanaClient.discoverDashboards().stream()
                .map(discoveredDashboard ->
                        syncWithDb(discoveredDashboard, savedByUid, cachedPanelsByUid, cachedVariablesByUid))
                .toList();

        Set<String> discoveredUids = discovered.stream()
                .map(GrafanaDashboardDetailsDto::uid)
                .map(this::normalizeUid)
                .collect(Collectors.toSet());
        saved.stream()
                .map(GrafanaDashboard::uid)
                .filter(uid -> !discoveredUids.contains(normalizeUid(uid)))
                .forEach(uid -> {
                    repository.deleteByUid(uid);
                    panelRepository.deleteByDashboardUid(uid);
                    variableRepository.deleteByDashboardUid(uid);
                });
        panelRepository.deleteMissingDashboards(discoveredUids);
        variableRepository.deleteMissingDashboards(discoveredUids);
        return discovered;
    }

    public GrafanaDashboard create(String uid, String slug, List<Integer> panels, Boolean enabled) {
        return repository.upsert(new GrafanaDashboard(
                normalizeUid(uid),
                normalizeSlug(slug),
                normalizePanels(panels),
                resolveEnabled(enabled)));
    }

    @Transactional
    public GrafanaDashboard update(
            String uid,
            String slug,
            String title,
            List<Integer> panels,
            Boolean enabled,
            List<GrafanaDashboardPanelUpdateRequest> panelSettings,
            List<GrafanaDashboardVariableUpdateRequest> variables) {
        String normalizedUid = normalizeUid(uid);
        String normalizedSlug = normalizeSlug(slug);
        String normalizedTitle = StringUtils.hasText(title) ? title.trim() : null;
        List<Integer> selectedPanels = panelSettings == null || panelSettings.isEmpty()
                ? normalizePanels(panels)
                : panelSettings.stream()
                        .filter(req -> Boolean.TRUE.equals(req.selected()))
                        .map(GrafanaDashboardPanelUpdateRequest::panelId)
                        .filter(Objects::nonNull)
                        .toList();

        if (panelSettings != null && !panelSettings.isEmpty()) {
            long testQuerySelections = panelSettings.stream()
                    .filter(req -> Boolean.TRUE.equals(req.testQuery()))
                    .count();
            if (testQuerySelections > 1) {
                throw new IllegalArgumentException("Можно выбрать только один запрос для поиска тестов");
            }

            List<GrafanaDashboardPanelRecord> cachedPanels = panelRepository.findByDashboardUid(normalizedUid);
            Map<Integer, GrafanaDashboardPanelRecord> cachedById = cachedPanels.stream()
                    .collect(Collectors.toMap(
                            GrafanaDashboardPanelRecord::panelId,
                            item -> item,
                            (first, second) -> first));

            String dashboardTitle = normalizedTitle != null
                    ? normalizedTitle
                    : cachedPanels.stream()
                            .map(GrafanaDashboardPanelRecord::dashboardTitle)
                            .filter(StringUtils::hasText)
                            .findFirst()
                            .orElse(normalizedSlug);

            if (testQuerySelections == 1) {
                panelRepository.clearTestQueryFlag();
            }

            List<GrafanaDashboardPanelRecord> updatedPanels = panelSettings.stream()
                    .filter(item -> item.panelId() != null)
                    .map(setting -> {
                        GrafanaDashboardPanelRecord cached = cachedById.get(setting.panelId());
                        String panelTitle = cached != null && StringUtils.hasText(cached.panelTitle())
                                ? cached.panelTitle()
                                : (StringUtils.hasText(setting.title())
                                        ? setting.title().trim()
                                        : ("График " + setting.panelId()));
                        String query = cached != null ? cached.panelQuery() : null;
                        Double threshold = cached != null ? cached.panelThreshold() : null;
                        boolean testQuery = testQuerySelections == 1
                                ? Boolean.TRUE.equals(setting.testQuery())
                                : (cached != null && Boolean.TRUE.equals(cached.testQuery()));
                        return new GrafanaDashboardPanelRecord(
                                normalizedUid,
                                normalizedSlug,
                                dashboardTitle,
                                setting.panelId(),
                                panelTitle,
                                query,
                                threshold,
                                testQuery);
                    })
                    .toList();
            panelRepository.replaceForDashboard(normalizedUid, normalizedSlug, dashboardTitle, updatedPanels);
        }

        if (variables != null) {
            Map<String, GrafanaDashboardVariableUpdateRequest> variableValues = variables.stream()
                    .filter(item -> StringUtils.hasText(item.name()))
                    .collect(Collectors.toMap(
                            GrafanaDashboardVariableUpdateRequest::name,
                            item -> item,
                            (first, second) -> second));
            List<GrafanaDashboardVariableRecord> cachedVariables =
                    variableRepository.findByDashboardUid(normalizedUid);
            Map<String, GrafanaDashboardVariableRecord> cachedByName = cachedVariables.stream()
                    .collect(Collectors.toMap(
                            GrafanaDashboardVariableRecord::name,
                            item -> item,
                            (first, second) -> first));

            List<GrafanaDashboardVariableRecord> updatedVariables = new ArrayList<>();
            for (GrafanaDashboardVariableRecord variable : cachedVariables) {
                GrafanaDashboardVariableUpdateRequest requested = variableValues.get(variable.name());
                String newValue = requested != null
                        ? StringUtils.trimWhitespace(requested.value())
                        : variable.value();
                String newLabel = requested != null && StringUtils.hasText(requested.label())
                        ? requested.label().trim()
                        : variable.label();
                String newType = requested != null && StringUtils.hasText(requested.type())
                        ? requested.type().trim()
                        : variable.type();
                String newDefaultValue = requested != null && requested.defaultValue() != null
                        ? StringUtils.trimWhitespace(requested.defaultValue())
                        : variable.defaultValue();
                List<GrafanaVariableOptionRecord> newOptions =
                        requested != null && requested.options() != null
                                ? toOptionRecordsFromDto(requested.options())
                                : variable.options();
                updatedVariables.add(new GrafanaDashboardVariableRecord(
                        normalizedUid,
                        variable.name(),
                        newLabel,
                        newType,
                        newDefaultValue,
                        newValue,
                        newOptions));
            }

            variableValues.keySet().stream()
                    .filter(name -> !cachedByName.containsKey(name))
                    .forEach(name -> {
                        GrafanaDashboardVariableUpdateRequest requested = variableValues.get(name);
                        updatedVariables.add(new GrafanaDashboardVariableRecord(
                                normalizedUid,
                                name,
                                StringUtils.hasText(requested.label()) ? requested.label().trim() : name,
                                StringUtils.hasText(requested.type()) ? requested.type().trim() : null,
                                requested.defaultValue() != null ? StringUtils.trimWhitespace(requested.defaultValue()) : null,
                                StringUtils.trimWhitespace(requested.value()),
                                requested.options() == null ? List.of() : toOptionRecordsFromDto(requested.options())));
                    });

            variableRepository.replaceForDashboard(normalizedUid, updatedVariables);
        }

        return repository.upsert(new GrafanaDashboard(
                normalizedUid,
                normalizedSlug,
                normalizePanels(selectedPanels),
                resolveEnabled(enabled)));
    }

    public void delete(String uid) {
        repository.deleteByUid(normalizeUid(uid));
    }

    private GrafanaDashboardDetailsDto syncWithDb(
            GrafanaDashboardDefinition discovered,
            Map<String, GrafanaDashboard> savedByUid,
            Map<String, List<GrafanaDashboardPanelRecord>> cachedPanelsByUid,
            Map<String, List<GrafanaDashboardVariableRecord>> cachedVariablesByUid) {
        String normalizedUid = normalizeUid(discovered.uid());
        Set<Integer> availablePanels = discovered.panels().stream()
                .map(GrafanaPanelDefinition::panelId)
                .collect(Collectors.toSet());

        GrafanaDashboard savedDashboard = savedByUid.get(normalizedUid);
        Set<Integer> selectedPanels = savedDashboard == null
                ? Set.of()
                : savedDashboard.panels().stream()
                        .filter(availablePanels::contains)
                        .collect(Collectors.toSet());
        boolean enabled = resolveEnabled(savedDashboard != null ? savedDashboard.enabled() : null);

        repository.upsert(new GrafanaDashboard(
                normalizedUid,
                normalizeSlug(discovered.slug()),
                List.copyOf(selectedPanels),
                enabled));

        Map<Integer, GrafanaDashboardPanelRecord> cachedPanels = cachedPanelsByUid.getOrDefault(normalizedUid, List.of())
                .stream()
                .collect(Collectors.toMap(
                        GrafanaDashboardPanelRecord::panelId,
                        item -> item,
                        (first, second) -> first));

        List<GrafanaDashboardPanelRecord> panelRecords = discovered.panels().stream()
                .map(panel -> {
                    GrafanaDashboardPanelRecord cached = cachedPanels.get(panel.panelId());
                    return new GrafanaDashboardPanelRecord(
                            normalizedUid,
                            normalizeSlug(discovered.slug()),
                            resolveTitle(discovered),
                            panel.panelId(),
                            panel.title(),
                            panel.query(),
                            panel.threshold(),
                            Boolean.TRUE.equals(Optional.ofNullable(cached)
                                    .map(GrafanaDashboardPanelRecord::testQuery)
                                    .orElse(false)));
                })
                .toList();
        panelRepository.replaceForDashboard(
                normalizedUid,
                normalizeSlug(discovered.slug()),
                resolveTitle(discovered),
                panelRecords);

        Map<String, GrafanaDashboardVariableRecord> cachedVariables = cachedVariablesByUid
                .getOrDefault(normalizedUid, List.of())
                .stream()
                .collect(Collectors.toMap(
                        GrafanaDashboardVariableRecord::name,
                        item -> item,
                        (first, second) -> first));

        List<GrafanaVariableDefinition> discoveredVariables =
                discovered.variables() == null ? List.of() : discovered.variables();

        List<GrafanaDashboardVariableRecord> variableRecords = discoveredVariables.stream()
                .filter(variable -> StringUtils.hasText(variable.name()))
                .map(variable -> {
                    GrafanaDashboardVariableRecord cached = cachedVariables.get(variable.name());
                    String label = StringUtils.hasText(variable.label()) ? variable.label() : variable.name();
                    String value = cached != null && StringUtils.hasText(cached.value())
                            ? cached.value()
                            : (StringUtils.hasText(variable.currentValue())
                                    ? variable.currentValue()
                                    : variable.defaultValue());
                    List<GrafanaVariableOptionRecord> options = toOptionRecords(
                            variable.options(),
                            cached != null ? cached.options() : List.of());
                    return new GrafanaDashboardVariableRecord(
                            normalizedUid,
                            variable.name(),
                            label,
                            variable.type(),
                            variable.defaultValue(),
                            value,
                            options);
                })
                .toList();
        variableRepository.replaceForDashboard(normalizedUid, variableRecords);

        List<GrafanaDashboardPanelDto> panels = discovered.panels().stream()
                .map(panel -> {
                    GrafanaDashboardPanelRecord cached = cachedPanels.get(panel.panelId());
                    return new GrafanaDashboardPanelDto(
                            panel.panelId(),
                            panel.title(),
                            selectedPanels.contains(panel.panelId()),
                            panel.query(),
                            panel.threshold(),
                            Boolean.TRUE.equals(Optional.ofNullable(cached)
                                    .map(GrafanaDashboardPanelRecord::testQuery)
                                    .orElse(false)));
                })
                .toList();

        List<GrafanaDashboardVariableDto> variables = variableRecords.stream()
                .map(this::toVariableDto)
                .toList();

        return new GrafanaDashboardDetailsDto(
                discovered.uid(),
                discovered.slug(),
                resolveTitle(discovered),
                enabled,
                panels,
                variables);
    }

    private GrafanaDashboardDetailsDto toDetailsFromCache(
            GrafanaDashboard savedDashboard,
            List<GrafanaDashboardPanelRecord> cachedPanels,
            List<GrafanaDashboardVariableRecord> cachedVariables) {
        String normalizedUid = normalizeUid(savedDashboard.uid());
        Set<Integer> selectedPanels = Set.copyOf(savedDashboard.panels());

        List<GrafanaDashboardPanelDto> panels;
        String title;
        if (cachedPanels == null || cachedPanels.isEmpty()) {
            panels = selectedPanels.stream()
                    .map(panelId -> new GrafanaDashboardPanelDto(panelId, "График " + panelId, true, null, null, false))
                    .toList();
            title = savedDashboard.slug();
        } else {
            panels = cachedPanels.stream()
                    .map(panel -> new GrafanaDashboardPanelDto(
                            panel.panelId(),
                            panel.panelTitle(),
                            selectedPanels.contains(panel.panelId()),
                            panel.panelQuery(),
                            panel.panelThreshold(),
                            Boolean.TRUE.equals(panel.testQuery())))
                    .toList();
            title = cachedPanels.stream()
                    .map(GrafanaDashboardPanelRecord::dashboardTitle)
                    .filter(StringUtils::hasText)
                    .findFirst()
                    .orElse(savedDashboard.slug());
        }

        List<GrafanaDashboardVariableDto> variables = cachedVariables == null
                ? List.of()
                : cachedVariables.stream()
                        .map(this::toVariableDto)
                        .toList();

        return new GrafanaDashboardDetailsDto(
                normalizedUid,
                savedDashboard.slug(),
                title,
                resolveEnabled(savedDashboard.enabled()),
                panels,
                variables);
    }

    private List<GrafanaVariableOptionRecord> toOptionRecords(
            List<GrafanaVariableOption> options,
            List<GrafanaVariableOptionRecord> cachedOptions) {
        if (options != null && !options.isEmpty()) {
            return options.stream()
                    .map(option -> new GrafanaVariableOptionRecord(option.value(), option.text(), option.selected()))
                    .toList();
        }
        if (cachedOptions == null || cachedOptions.isEmpty()) {
            return List.of();
        }
        return cachedOptions.stream().toList();
    }

    private List<GrafanaVariableOptionRecord> toOptionRecordsFromDto(List<GrafanaVariableOptionDto> options) {
        if (options == null || options.isEmpty()) {
            return List.of();
        }
        return options.stream()
                .filter(Objects::nonNull)
                .map(option -> new GrafanaVariableOptionRecord(option.value(), option.text(), option.selected()))
                .toList();
    }

    private GrafanaDashboardVariableDto toVariableDto(GrafanaDashboardVariableRecord record) {
        List<GrafanaVariableOptionDto> options = record.options() == null
                ? List.of()
                : record.options().stream()
                        .map(option -> new GrafanaVariableOptionDto(option.value(), option.text(), option.selected()))
                        .toList();
        return new GrafanaDashboardVariableDto(
                record.name(),
                record.label(),
                record.type(),
                record.value(),
                record.defaultValue(),
                options);
    }

    private String normalizeUid(String uid) {
        String value = StringUtils.trimWhitespace(uid);
        if (!StringUtils.hasText(value)) {
            throw new IllegalArgumentException("Dashboard UID must not be blank");
        }
        return value;
    }

    private String resolveTitle(GrafanaDashboardDefinition discovered) {
        return StringUtils.hasText(discovered.title()) ? discovered.title() : discovered.slug();
    }

    private boolean resolveEnabled(Boolean enabled) {
        return Boolean.TRUE.equals(enabled);
    }

    private String normalizeSlug(String slug) {
        String value = StringUtils.trimWhitespace(slug);
        if (!StringUtils.hasText(value)) {
            throw new IllegalArgumentException("Dashboard slug must not be blank");
        }
        return value;
    }

    private List<Integer> normalizePanels(List<Integer> panels) {
        if (panels == null) {
            return List.of();
        }
        List<Integer> sanitized = panels.stream()
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
        if (sanitized.isEmpty()) {
            return List.of();
        }
        return List.copyOf(sanitized);
    }
}
