package com.example.chronicler.service;

import com.example.chronicler.client.ConfluenceClient;
import com.example.chronicler.dto.confluence.ConfluenceCredentials;
import com.example.chronicler.dto.confluence.ConfluencePage;
import com.example.chronicler.dto.confluence.ConfluenceReportHierarchyResponse;
import com.example.chronicler.dto.confluence.ConfluenceReportRequest;
import com.example.chronicler.dto.confluence.ConfluenceReportResponse;
import com.example.chronicler.dto.grafana.GrafanaRenderResponse;
import com.example.chronicler.dto.grafana.GrafanaRenderedDashboard;
import com.example.chronicler.dto.grafana.GrafanaRenderedPanel;
import com.example.chronicler.exception.ConfluenceOperationException;
import com.example.chronicler.exception.GrafanaRenderNotCachedException;
import java.util.ArrayList;
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
    private final TemplateLoader templateLoader;

    public ConfluenceService(
            ConfluenceClient confluenceClient, GrafanaRenderCache grafanaRenderCache, TemplateLoader templateLoader) {
        this.confluenceClient = confluenceClient;
        this.grafanaRenderCache = grafanaRenderCache;
        this.templateLoader = templateLoader;
    }

    private static final String OVERVIEW_TEMPLATE_NAME = "overall-report";
    private static final String OVERVIEW_PAGE_TITLE = "Automated Test Report";
    private static final List<SubReportTemplate> SUB_REPORT_TEMPLATES = List.of(
            new SubReportTemplate("load-report", "Load Test Summary"),
            new SubReportTemplate("stress-report", "Stress Test Summary"),
            new SubReportTemplate("soak", "Soak Test Summary"));

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

    public ConfluenceReportHierarchyResponse createReportHierarchy(
            ConfluenceCredentials credentials, String parentPageId) {
        Objects.requireNonNull(credentials, "Confluence credentials must not be null");
        if (!StringUtils.hasText(parentPageId)) {
            throw new ConfluenceOperationException("parentPageId is required");
        }

        ConfluencePage parentPage = confluenceClient.getPage(credentials, parentPageId);
        if (!StringUtils.hasText(parentPage.spaceKey())) {
            throw new ConfluenceOperationException(
                    "Parent page does not contain space key information");
        }

        String overviewBody = templateLoader.loadTemplate(OVERVIEW_TEMPLATE_NAME);
        ConfluencePage overviewPage = confluenceClient.createChildPage(
                credentials, parentPageId, parentPage.spaceKey(), OVERVIEW_PAGE_TITLE, overviewBody);

        List<String> childPageIds = new ArrayList<>();
        for (SubReportTemplate template : SUB_REPORT_TEMPLATES) {
            String body = templateLoader.loadTemplate(template.templateName());
            ConfluencePage childPage = confluenceClient.createChildPage(
                    credentials, overviewPage.id(), parentPage.spaceKey(), template.pageTitle(), body);
            childPageIds.add(childPage.id());
        }

        return new ConfluenceReportHierarchyResponse(overviewPage.id(), List.copyOf(childPageIds));
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
    private record SubReportTemplate(String templateName, String pageTitle) {}
}
