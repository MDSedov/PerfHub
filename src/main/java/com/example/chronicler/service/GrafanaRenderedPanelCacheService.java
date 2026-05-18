package com.example.chronicler.service;

import com.example.chronicler.dto.grafana.GrafanaRenderOptions;
import com.example.chronicler.model.GrafanaRenderedPanelCacheEntry;
import com.example.chronicler.repository.GrafanaRenderedPanelCacheRepository;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class GrafanaRenderedPanelCacheService {

    private static final Logger log = LoggerFactory.getLogger(GrafanaRenderedPanelCacheService.class);
    private static final String CONTENT_TYPE = "image/png";
    private static final String DEFAULT_VARIABLES_HASH = "no-variables";

    private final GrafanaRenderedPanelCacheRepository repository;
    private final Path cacheRoot;

    public GrafanaRenderedPanelCacheService(
            GrafanaRenderedPanelCacheRepository repository,
            @Value("${chronicler.grafana-render-cache.directory:grafana-render-cache}") String cacheDirectory) {
        this.repository = repository;
        String resolvedDirectory = StringUtils.hasText(cacheDirectory) ? cacheDirectory.trim() : "grafana-render-cache";
        this.cacheRoot = Paths.get(resolvedDirectory).toAbsolutePath().normalize();
    }

    public Optional<CachedPanelImage> find(
            UUID testUuid,
            int templateId,
            String dashboardUid,
            int panelId,
            Instant from,
            Instant to,
            Map<String, String> variables,
            GrafanaRenderOptions options) {
        if (testUuid == null || options == null) {
            return Optional.empty();
        }
        String variablesHash = variablesHash(variables);
        Optional<GrafanaRenderedPanelCacheEntry> entry = repository.findLatestMatching(
                testUuid,
                templateId,
                dashboardUid,
                panelId,
                from,
                to,
                variablesHash,
                options.width(),
                options.height(),
                options.timezone());
        if (entry.isEmpty()) {
            return Optional.empty();
        }

        GrafanaRenderedPanelCacheEntry cached = entry.get();
        try {
            Path imagePath = resolveStoredPath(cached.filePath());
            if (!Files.isRegularFile(imagePath)) {
                deleteBrokenEntry(cached);
                return Optional.empty();
            }
            return Optional.of(new CachedPanelImage(
                    cached.dashboardSlug(),
                    cached.title(),
                    cached.description(),
                    Files.readAllBytes(imagePath)));
        } catch (IOException | IllegalArgumentException ex) {
            log.warn("Failed to read cached Grafana panel image '{}': {}", cached.filePath(), ex.getMessage());
            deleteBrokenEntry(cached);
            return Optional.empty();
        }
    }

    public void store(
            UUID testUuid,
            int templateId,
            String dashboardUid,
            String dashboardSlug,
            int panelId,
            Instant from,
            Instant to,
            Map<String, String> variables,
            GrafanaRenderOptions options,
            String title,
            String description,
            byte[] imageBytes) {
        if (testUuid == null || options == null || imageBytes == null || imageBytes.length == 0) {
            return;
        }

        String variablesHash = variablesHash(variables);
        String lookupHash = sha256Hex(
                "%s|%s|%s|%d|%s|%s|%s|%d|%d|%s"
                        .formatted(
                                testUuid,
                                templateId,
                                dashboardUid,
                                panelId,
                                from,
                                to,
                                variablesHash,
                                options.width(),
                                options.height(),
                                options.timezone())
                        .getBytes(StandardCharsets.UTF_8));
        String relativePath = buildRelativePath(testUuid, templateId, dashboardUid, panelId, lookupHash);

        try {
            Path imagePath = resolveStoredPath(relativePath);
            Files.createDirectories(imagePath.getParent());
            Files.write(
                    imagePath,
                    imageBytes,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE);
            repository.replace(new GrafanaRenderedPanelCacheEntry(
                    null,
                    testUuid,
                    templateId,
                    dashboardUid,
                    dashboardSlug,
                    panelId,
                    from,
                    to,
                    variablesHash,
                    options.width(),
                    options.height(),
                    options.timezone(),
                    title,
                    description,
                    relativePath,
                    CONTENT_TYPE,
                    sha256Hex(imageBytes),
                    Instant.now()));
        } catch (IOException | IllegalArgumentException ex) {
            log.warn(
                    "Failed to store Grafana panel image for test '{}' dashboard '{}' panel {}: {}",
                    testUuid,
                    dashboardUid,
                    panelId,
                    ex.getMessage());
        }
    }

    private String variablesHash(Map<String, String> variables) {
        if (variables == null || variables.isEmpty()) {
            return DEFAULT_VARIABLES_HASH;
        }
        String normalized = variables.entrySet().stream()
                .filter(entry -> StringUtils.hasText(entry.getKey()) && StringUtils.hasText(entry.getValue()))
                .sorted(Comparator.comparing(Map.Entry::getKey))
                .map(entry -> entry.getKey().trim() + "=" + entry.getValue().trim())
                .reduce((left, right) -> left + "\n" + right)
                .orElse("");
        if (!StringUtils.hasText(normalized)) {
            return DEFAULT_VARIABLES_HASH;
        }
        return sha256Hex(normalized.getBytes(StandardCharsets.UTF_8));
    }

    private String buildRelativePath(UUID testUuid, int templateId, String dashboardUid, int panelId, String lookupHash) {
        String safeDashboardUid = sanitizePathSegment(dashboardUid);
        String shortHash = lookupHash.substring(0, 16);
        return Path.of(
                        "tests",
                        testUuid.toString(),
                        "template-" + templateId,
                        safeDashboardUid,
                        "panel-" + panelId + "-" + shortHash + ".png")
                .toString();
    }

    private Path resolveStoredPath(String relativePath) {
        if (!StringUtils.hasText(relativePath)) {
            throw new IllegalArgumentException("Cached file path is empty");
        }
        Path resolved = cacheRoot.resolve(relativePath).normalize();
        if (!resolved.startsWith(cacheRoot)) {
            throw new IllegalArgumentException("Cached file path points outside cache directory");
        }
        return resolved;
    }

    private String sanitizePathSegment(String value) {
        String normalized = StringUtils.hasText(value) ? value.trim() : "unknown";
        return normalized.replaceAll("[^a-zA-Z0-9._-]", "_");
    }

    private void deleteBrokenEntry(GrafanaRenderedPanelCacheEntry cached) {
        if (cached.id() != null) {
            repository.deleteById(cached.id());
        }
    }

    private String sha256Hex(byte[] data) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(data));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is not available", ex);
        }
    }

    public record CachedPanelImage(String dashboardSlug, String title, String description, byte[] imageBytes) {
    }
}
