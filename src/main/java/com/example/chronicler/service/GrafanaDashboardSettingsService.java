package com.example.chronicler.service;

import com.example.chronicler.client.GrafanaClient;
import com.example.chronicler.dto.grafana.GrafanaDashboardDefinition;
import com.example.chronicler.dto.grafana.GrafanaPanelDefinition;
import com.example.chronicler.dto.settings.GrafanaDashboardDetailsDto;
import com.example.chronicler.dto.settings.GrafanaDashboardPanelDto;
import com.example.chronicler.model.GrafanaDashboard;
import com.example.chronicler.repository.GrafanaDashboardPanelRepository;
import com.example.chronicler.repository.GrafanaDashboardRepository;
import com.example.chronicler.repository.model.GrafanaDashboardPanelRecord;
import com.example.chronicler.dto.settings.GrafanaDashboardPanelUpdateRequest;
import java.util.List;
import java.util.Objects;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
public class GrafanaDashboardSettingsService {

    private final GrafanaDashboardRepository repository;
    private final GrafanaDashboardPanelRepository panelRepository;
    private final GrafanaClient grafanaClient;

    public GrafanaDashboardSettingsService(
            GrafanaDashboardRepository repository,
            GrafanaDashboardPanelRepository panelRepository,
            GrafanaClient grafanaClient) {
        this.repository = repository;
        this.panelRepository = panelRepository;
        this.grafanaClient = grafanaClient;
    }

    public List<GrafanaDashboardDetailsDto> listDashboards() {
        Map<String, List<GrafanaDashboardPanelRecord>> panelsByUid = panelRepository.findAllGroupedByUid();
        return repository.findAll().stream()
                .map(saved -> toDetailsFromCache(saved, panelsByUid.get(normalizeUid(saved.uid()))))
                .toList();
    }

    public List<GrafanaDashboardDetailsDto> pullFromGrafana() {
        List<GrafanaDashboard> saved = repository.findAll();
        var savedByUid = saved.stream()
                .collect(Collectors.toMap(
                        item -> normalizeUid(item.uid()),
                        item -> item,
                        (first, second) -> first));
        List<GrafanaDashboardDetailsDto> discovered = grafanaClient.discoverDashboards().stream()
                .map(discoveredDashboard -> syncWithDb(discoveredDashboard, savedByUid))
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
                });
        panelRepository.deleteMissingDashboards(discoveredUids);
        return discovered;
    }

    public GrafanaDashboard create(String uid, String slug, List<Integer> panels, boolean enabled) {
        return repository.upsert(new GrafanaDashboard(
                normalizeUid(uid),
                normalizeSlug(slug),
                normalizePanels(panels),
                enabled));
    }

    @Transactional
    public GrafanaDashboard update(
            String uid,
            String slug,
            List<Integer> panels,
            boolean enabled,
            List<GrafanaDashboardPanelUpdateRequest> panelSettings) {
        String normalizedUid = normalizeUid(uid);
        String normalizedSlug = normalizeSlug(slug);
        List<Integer> selectedPanels = panelSettings == null || panelSettings.isEmpty()
                ? normalizePanels(panels)
                : panelSettings.stream()
                        .filter(req -> Boolean.TRUE.equals(req.selected()))
                        .map(GrafanaDashboardPanelUpdateRequest::panelId)
                        .filter(Objects::nonNull)
                        .toList();

        if (panelSettings != null && !panelSettings.isEmpty()) {
            List<GrafanaDashboardPanelRecord> cachedPanels = panelRepository.findByDashboardUid(normalizedUid);
            Map<Integer, GrafanaDashboardPanelRecord> cachedById = cachedPanels.stream()
                    .collect(Collectors.toMap(
                            GrafanaDashboardPanelRecord::panelId,
                            item -> item,
                            (first, second) -> first));

            String dashboardTitle = cachedPanels.stream()
                    .map(GrafanaDashboardPanelRecord::dashboardTitle)
                    .filter(StringUtils::hasText)
                    .findFirst()
                    .orElse(normalizedSlug);

            List<GrafanaDashboardPanelRecord> updatedPanels = panelSettings.stream()
                    .filter(item -> item.panelId() != null)
                    .map(setting -> {
                        GrafanaDashboardPanelRecord cached = cachedById.get(setting.panelId());
                        String title = cached != null && StringUtils.hasText(cached.panelTitle())
                                ? cached.panelTitle()
                                : ("График " + setting.panelId());
                        String query = setting.query() != null ? setting.query() : (cached != null ? cached.panelQuery() : null);
                        Double threshold = setting.threshold() != null
                                ? setting.threshold()
                                : (cached != null ? cached.panelThreshold() : null);
                        return new GrafanaDashboardPanelRecord(
                                normalizedUid,
                                normalizedSlug,
                                dashboardTitle,
                                setting.panelId(),
                                title,
                                query,
                                threshold);
                    })
                    .toList();
            panelRepository.replaceForDashboard(normalizedUid, normalizedSlug, dashboardTitle, updatedPanels);
        }

        return repository.upsert(new GrafanaDashboard(
                normalizedUid,
                normalizedSlug,
                normalizePanels(selectedPanels),
                enabled));
    }

    public void delete(String uid) {
        repository.deleteByUid(normalizeUid(uid));
    }

    private GrafanaDashboardDetailsDto syncWithDb(
            GrafanaDashboardDefinition discovered, Map<String, GrafanaDashboard> savedByUid) {
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
        boolean enabled = savedDashboard != null && savedDashboard.enabled();

        repository.upsert(new GrafanaDashboard(
                normalizedUid,
                normalizeSlug(discovered.slug()),
                List.copyOf(selectedPanels),
                enabled));

        List<GrafanaDashboardPanelRecord> panelRecords = discovered.panels().stream()
                .map(panel -> new GrafanaDashboardPanelRecord(
                        normalizedUid,
                        normalizeSlug(discovered.slug()),
                        resolveTitle(discovered),
                        panel.panelId(),
                        panel.title(),
                        panel.query(),
                        panel.threshold()))
                .toList();
        panelRepository.replaceForDashboard(
                normalizedUid,
                normalizeSlug(discovered.slug()),
                resolveTitle(discovered),
                panelRecords);

        List<GrafanaDashboardPanelDto> panels = discovered.panels().stream()
                .map(panel -> new GrafanaDashboardPanelDto(
                        panel.panelId(),
                        panel.title(),
                        selectedPanels.contains(panel.panelId()),
                        panel.query(),
                        panel.threshold()))
                .toList();

        return new GrafanaDashboardDetailsDto(
                discovered.uid(),
                discovered.slug(),
                resolveTitle(discovered),
                enabled,
                panels);
    }

    private GrafanaDashboardDetailsDto toDetailsFromCache(
            GrafanaDashboard savedDashboard,
            List<GrafanaDashboardPanelRecord> cachedPanels) {
        String normalizedUid = normalizeUid(savedDashboard.uid());
        Set<Integer> selectedPanels = Set.copyOf(savedDashboard.panels());

        List<GrafanaDashboardPanelDto> panels;
        String title;
        if (cachedPanels == null || cachedPanels.isEmpty()) {
            panels = selectedPanels.stream()
                    .map(panelId -> new GrafanaDashboardPanelDto(panelId, "График " + panelId, true, null, null))
                    .toList();
            title = savedDashboard.slug();
        } else {
            panels = cachedPanels.stream()
                    .map(panel -> new GrafanaDashboardPanelDto(
                            panel.panelId(),
                            panel.panelTitle(),
                            selectedPanels.contains(panel.panelId()),
                            panel.panelQuery(),
                            panel.panelThreshold()))
                    .toList();
            title = cachedPanels.stream()
                    .map(GrafanaDashboardPanelRecord::dashboardTitle)
                    .filter(StringUtils::hasText)
                    .findFirst()
                    .orElse(savedDashboard.slug());
        }

        return new GrafanaDashboardDetailsDto(
                normalizedUid,
                savedDashboard.slug(),
                title,
                savedDashboard.enabled(),
                panels);
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
