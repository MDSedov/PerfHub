package com.example.chronicler.service;

import com.example.chronicler.client.GrafanaClient;
import com.example.chronicler.dto.grafana.GrafanaDashboardRequest;
import com.example.chronicler.dto.grafana.GrafanaPanelRequest;
import com.example.chronicler.dto.grafana.GrafanaRenderRequest;
import com.example.chronicler.dto.grafana.GrafanaRenderResponse;
import com.example.chronicler.dto.grafana.GrafanaRenderedDashboard;
import com.example.chronicler.dto.grafana.GrafanaRenderedPanel;
import com.example.chronicler.exception.GrafanaInvalidTimeRangeException;
import com.example.chronicler.exception.GrafanaRenderingException;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Objects;

import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class GrafanaService {

    private final GrafanaClient grafanaClient;
    private final GrafanaRenderCache grafanaRenderCache;

    public GrafanaService(GrafanaClient grafanaClient, GrafanaRenderCache grafanaRenderCache) {
        this.grafanaClient = grafanaClient;
        this.grafanaRenderCache = grafanaRenderCache;
    }

    public GrafanaRenderResponse renderDashboards(GrafanaRenderRequest request) {
        Objects.requireNonNull(request, "Grafana render request must not be null");

        Instant from = parseInstant(request.from(), "from");
        Instant to = parseInstant(request.to(), "to");

        if (!to.isAfter(from)) {
            throw new GrafanaInvalidTimeRangeException("End time must be after start time");
        }

        List<GrafanaRenderedDashboard> dashboards = new ArrayList<>();
        List<GrafanaDashboardRequest> dashboardRequests = request.dashboards();

        if (dashboardRequests == null || dashboardRequests.isEmpty()) {
            throw new GrafanaRenderingException("At least one dashboard must be provided");
        }

        for (GrafanaDashboardRequest dashboardRequest : dashboardRequests) {
            Objects.requireNonNull(dashboardRequest, "Grafana dashboard request must not be null");
            String uid = requireText(dashboardRequest.uid(), "uid");
            String slug = requireText(dashboardRequest.slug(), "slug");

            List<GrafanaPanelRequest> panels = dashboardRequest.panels();
            if (panels == null || panels.isEmpty()) {
                throw new GrafanaRenderingException(
                        "Dashboard '" + slug + "' must contain at least one panel identifier");
            }

            List<GrafanaRenderedPanel> renderedPanels = new ArrayList<>(panels.size());
            for (GrafanaPanelRequest panelRequest : panels) {
                Objects.requireNonNull(panelRequest, "Grafana panel request must not be null");
                Integer panelId = panelRequest.panelId();
                if (panelId == null || panelId <= 0) {
                    throw new GrafanaRenderingException("Panel identifiers must be positive numbers");
                }
                String title = requireText(panelRequest.title(), "title");
                String description = panelRequest.description();
                if (description == null) {
                    description = "";
                }
                byte[] imageBytes = grafanaClient.renderPanel(uid, slug, panelId, from, to);
                String encodedImage = Base64.getEncoder().encodeToString(imageBytes);
                renderedPanels.add(new GrafanaRenderedPanel(panelId, title, description, encodedImage));
            }

            dashboards.add(new GrafanaRenderedDashboard(uid, slug, renderedPanels));
        }

        GrafanaRenderResponse response = new GrafanaRenderResponse(from.toString(), to.toString(), dashboards);
        grafanaRenderCache.store(response);
        return response;
    }

    private Instant parseInstant(String value, String field) {
        if (!StringUtils.hasText(value)) {
            throw new GrafanaInvalidTimeRangeException(field + " is required");
        }
        try {
            return Instant.parse(value);
        } catch (DateTimeParseException ex) {
            throw new GrafanaInvalidTimeRangeException("Unable to parse " + field + " as ISO-8601 instant");
        }
    }

    private String requireText(String value, String field) {
        if (!StringUtils.hasText(value)) {
            throw new GrafanaRenderingException(field + " must not be blank");
        }
        return value;
    }
}
