package com.example.chronicler.service;

import com.example.chronicler.client.ConfluenceClient;
import com.example.chronicler.dto.confluence.ConfluenceCredentials;
import com.example.chronicler.dto.confluence.ConfluencePage;
import com.example.chronicler.dto.confluence.ConfluenceReportRequest;
import com.example.chronicler.dto.confluence.ConfluenceReportResponse;
import com.example.chronicler.dto.grafana.GrafanaRenderResponse;
import com.example.chronicler.dto.grafana.GrafanaRenderedDashboard;
import com.example.chronicler.dto.grafana.GrafanaRenderedPanel;
import com.example.chronicler.exception.ConfluenceOperationException;
import com.example.chronicler.exception.GrafanaRenderNotCachedException;
import java.util.Base64;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class ConfluenceService {

    private final ConfluenceClient confluenceClient;
    private final GrafanaRenderCache grafanaRenderCache;

    public ConfluenceService(ConfluenceClient confluenceClient, GrafanaRenderCache grafanaRenderCache) {
        this.confluenceClient = confluenceClient;
        this.grafanaRenderCache = grafanaRenderCache;
    }

    public ConfluenceReportResponse publishLatestGrafanaRender(
            ConfluenceCredentials credentials, ConfluenceReportRequest request) {
        Objects.requireNonNull(credentials, "Confluence credentials must not be null");
        Objects.requireNonNull(request, "Confluence report request must not be null");

        if (!StringUtils.hasText(credentials.login())) {
            throw new ConfluenceOperationException("Confluence login header must not be blank");
        }
        if (!StringUtils.hasText(credentials.accessToken())) {
            throw new ConfluenceOperationException("Confluence access token header must not be blank");
        }
        if (!StringUtils.hasText(request.pageId())) {
            throw new ConfluenceOperationException("pageId is required");
        }

        GrafanaRenderResponse cachedResponse = grafanaRenderCache
                .getLatest()
                .orElseThrow(() -> new GrafanaRenderNotCachedException("Grafana render result is not cached yet"));

        GrafanaRenderedPanel panel = findFirstPanel(cachedResponse)
                .orElseThrow(() -> new ConfluenceOperationException("Cached Grafana render does not contain any panels"));

        byte[] imageBytes = decodePanelImage(panel);
        String attachmentName = generateAttachmentName(panel);

        ConfluencePage page = confluenceClient.getPage(credentials, request.pageId());
        confluenceClient.uploadAttachment(credentials, page.id(), attachmentName, imageBytes);

        String appendedBody = appendImageToBody(page.body(), attachmentName);
        confluenceClient.updatePage(credentials, page, appendedBody);

        return new ConfluenceReportResponse(page.id(), attachmentName);
    }

    private Optional<GrafanaRenderedPanel> findFirstPanel(GrafanaRenderResponse response) {
        if (response.dashboards() == null) {
            return Optional.empty();
        }
        return response.dashboards().stream()
                .filter(Objects::nonNull)
                .map(GrafanaRenderedDashboard::panels)
                .filter(Objects::nonNull)
                .flatMap(List::stream)
                .filter(Objects::nonNull)
                .findFirst();
    }

    private byte[] decodePanelImage(GrafanaRenderedPanel panel) {
        if (!StringUtils.hasText(panel.imageBase64())) {
            throw new ConfluenceOperationException("Cached Grafana panel image is empty");
        }
        try {
            return Base64.getDecoder().decode(panel.imageBase64());
        } catch (IllegalArgumentException ex) {
            throw new ConfluenceOperationException("Cached Grafana panel image could not be decoded", ex);
        }
    }

    private String generateAttachmentName(GrafanaRenderedPanel panel) {
        return "grafana-panel-" + panel.panelId() + "-" + UUID.randomUUID() + ".png";
    }

    private String appendImageToBody(String existingBody, String attachmentName) {
        String safeBody = existingBody != null ? existingBody : "";
        String imageMacro = "<p><ac:image><ri:attachment ri:filename=\"" + attachmentName + "\" /></ac:image></p>";
        return safeBody + imageMacro;
    }
}
