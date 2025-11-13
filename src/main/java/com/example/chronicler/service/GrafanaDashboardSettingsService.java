package com.example.chronicler.service;

import com.example.chronicler.model.GrafanaDashboard;
import com.example.chronicler.repository.GrafanaDashboardRepository;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class GrafanaDashboardSettingsService {

    private final GrafanaDashboardRepository repository;

    public GrafanaDashboardSettingsService(GrafanaDashboardRepository repository) {
        this.repository = repository;
    }

    public List<GrafanaDashboard> listDashboards() {
        return repository.findAll();
    }

    public GrafanaDashboard create(String uid, String slug, List<Integer> panels) {
        return repository.upsert(new GrafanaDashboard(
                normalizeUid(uid),
                normalizeSlug(slug),
                normalizePanels(panels)));
    }

    public GrafanaDashboard update(String uid, String slug, List<Integer> panels) {
        return repository.upsert(new GrafanaDashboard(
                normalizeUid(uid),
                normalizeSlug(slug),
                normalizePanels(panels)));
    }

    public void delete(String uid) {
        repository.deleteByUid(normalizeUid(uid));
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
