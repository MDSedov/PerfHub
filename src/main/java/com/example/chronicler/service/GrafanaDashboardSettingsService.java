package com.example.chronicler.service;

import com.example.chronicler.client.GrafanaClient;
import com.example.chronicler.dto.grafana.GrafanaDashboardDefinition;
import com.example.chronicler.dto.settings.GrafanaDashboardDetailsDto;
import com.example.chronicler.dto.settings.GrafanaDashboardPanelDto;
import com.example.chronicler.model.GrafanaDashboard;
import com.example.chronicler.repository.GrafanaDashboardRepository;
import java.util.List;
import java.util.Objects;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class GrafanaDashboardSettingsService {

    private final GrafanaDashboardRepository repository;
    private final GrafanaClient grafanaClient;

    public GrafanaDashboardSettingsService(GrafanaDashboardRepository repository, GrafanaClient grafanaClient) {
        this.repository = repository;
        this.grafanaClient = grafanaClient;
    }

    public List<GrafanaDashboard> listDashboards() {
        return repository.findAll();
    }

    public List<GrafanaDashboardDetailsDto> pullFromGrafana() {
        List<GrafanaDashboard> saved = repository.findAll();
        var savedByUid = saved.stream()
                .collect(Collectors.toMap(
                        item -> normalizeUid(item.uid()),
                        item -> item,
                        (first, second) -> first));
        return grafanaClient.discoverDashboards().stream()
                .map(discovered -> toDetails(discovered, savedByUid))
                .toList();
    }

    public GrafanaDashboard create(String uid, String slug, List<Integer> panels, boolean enabled) {
        return repository.upsert(new GrafanaDashboard(
                normalizeUid(uid),
                normalizeSlug(slug),
                normalizePanels(panels),
                enabled));
    }

    public GrafanaDashboard update(String uid, String slug, List<Integer> panels, boolean enabled) {
        return repository.upsert(new GrafanaDashboard(
                normalizeUid(uid),
                normalizeSlug(slug),
                normalizePanels(panels),
                enabled));
    }

    public void delete(String uid) {
        repository.deleteByUid(normalizeUid(uid));
    }

    private GrafanaDashboardDetailsDto toDetails(
            GrafanaDashboardDefinition discovered, Map<String, GrafanaDashboard> savedByUid) {
        String normalizedUid = normalizeUid(discovered.uid());
        GrafanaDashboard savedDashboard = savedByUid.get(normalizedUid);
        Set<Integer> selectedPanels = savedDashboard == null
                ? Set.of()
                : Set.copyOf(savedDashboard.panels());
        boolean enabled = savedDashboard != null && savedDashboard.enabled();

        List<GrafanaDashboardPanelDto> panels = discovered.panels().stream()
                .map(panel -> new GrafanaDashboardPanelDto(
                        panel.panelId(),
                        panel.title(),
                        selectedPanels.contains(panel.panelId())))
                .toList();

        return new GrafanaDashboardDetailsDto(
                discovered.uid(),
                discovered.slug(),
                StringUtils.hasText(discovered.title()) ? discovered.title() : discovered.slug(),
                enabled,
                panels);
    }

    private String normalizeUid(String uid) {
        String value = StringUtils.trimWhitespace(uid);
        if (!StringUtils.hasText(value)) {
            throw new IllegalArgumentException("Dashboard UID must not be blank");
        }
        return value;
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
