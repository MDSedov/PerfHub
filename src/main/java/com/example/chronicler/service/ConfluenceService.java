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
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.util.HtmlUtils;

@Service
public class ConfluenceService {

    private final ConfluenceClient confluenceClient;
    private final GrafanaRenderCache grafanaRenderCache;
    private final TemplateLoader templateLoader;

    /**
     * Создаёт сервис для работы с Confluence, кешем рендеров Grafana и загрузкой шаблонов.
     */
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

    /**
     * Публикует все закешированные панели Grafana на страницу Confluence, добавляя для каждой заголовок,
     * описание и изображение.
     */
    public ConfluenceReportResponse publishLatestGrafanaRender(
            ConfluenceCredentials credentials, ConfluenceReportRequest request) {
        Objects.requireNonNull(credentials, "Confluence credentials must not be null");
        Objects.requireNonNull(request, "Confluence report request must not be null");

        validateCredentials(credentials);
        if (!StringUtils.hasText(request.pageId())) {
            throw new ConfluenceOperationException("pageId is required");
        }

        GrafanaRenderResponse cachedResponse = grafanaRenderCache
                .getLatest()
                .orElseThrow(() -> new GrafanaRenderNotCachedException("Grafana render result is not cached yet"));

        List<GrafanaRenderedPanel> panels = findAllPanels(cachedResponse);
        if (panels.isEmpty()) {
            throw new ConfluenceOperationException("Cached Grafana render does not contain any panels");
        }

        ConfluencePage page = confluenceClient.getPage(credentials, request.pageId());
        StringBuilder bodyBuilder = new StringBuilder(getSafeBody(page.body()));
        List<String> attachmentNames = new ArrayList<>();

        for (GrafanaRenderedPanel panel : panels) {
            byte[] imageBytes = decodePanelImage(panel);
            String attachmentName = generateAttachmentName(panel);
            confluenceClient.uploadAttachment(credentials, page.id(), attachmentName, imageBytes);
            attachmentNames.add(attachmentName);
            appendPanelToBody(bodyBuilder, panel, attachmentName);
        }

        confluenceClient.updatePage(credentials, page, bodyBuilder.toString());

        return new ConfluenceReportResponse(page.id(), List.copyOf(attachmentNames));
    }

    /**
     * Создаёт иерархию страниц отчёта в Confluence, используя шаблоны для обзорной и дочерних страниц.
     */
    public ConfluenceReportHierarchyResponse createReportHierarchy(
            ConfluenceCredentials credentials, String parentPageId) {
        Objects.requireNonNull(credentials, "Confluence credentials must not be null");
        validateCredentials(credentials);
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

    /**
     * Извлекает все панели из ответа Grafana, отбрасывая пустые значения.
     */
    private List<GrafanaRenderedPanel> findAllPanels(GrafanaRenderResponse response) {
        List<GrafanaRenderedPanel> panels = new ArrayList<>();
        if (response.dashboards() == null) {
            return panels;
        }
        for (GrafanaRenderedDashboard dashboard : response.dashboards()) {
            if (dashboard == null || dashboard.panels() == null) {
                continue;
            }
            for (GrafanaRenderedPanel panel : dashboard.panels()) {
                if (panel != null) {
                    panels.add(panel);
                }
            }
        }
        return panels;
    }

    /**
     * Декодирует изображение панели из Base64 и валидирует наличие данных.
     */
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

    /**
     * Формирует уникальное имя вложения для изображения панели.
     */
    private String generateAttachmentName(GrafanaRenderedPanel panel) {
        return "grafana-panel-" + panel.panelId() + "-" + UUID.randomUUID() + ".png";
    }

    /**
     * Добавляет в тело страницы блок с заголовком, описанием и изображением панели.
     */
    private void appendPanelToBody(StringBuilder bodyBuilder, GrafanaRenderedPanel panel, String attachmentName) {
        String title = StringUtils.hasText(panel.title()) ? panel.title() : "Панель без названия";
        bodyBuilder
                .append("<h3>")
                .append(HtmlUtils.htmlEscape(title))
                .append("</h3>");

        if (StringUtils.hasText(panel.description())) {
            bodyBuilder
                    .append("<p>")
                    .append(HtmlUtils.htmlEscape(panel.description()))
                    .append("</p>");
        }

        bodyBuilder
                .append("<p><ac:image><ri:attachment ri:filename=\"")
                .append(HtmlUtils.htmlEscape(attachmentName))
                .append("\" /></ac:image></p>");
    }

    /**
     * Возвращает существующее тело страницы или пустую строку, если тело отсутствует.
     */
    private String getSafeBody(String existingBody) {
        return existingBody != null ? existingBody : "";
    }

    /**
     * Проверяет корректность переданных учётных данных Confluence.
     */
    private void validateCredentials(ConfluenceCredentials credentials) {
        if (!StringUtils.hasText(credentials.accessToken())) {
            throw new ConfluenceOperationException("Confluence access token header must not be blank");
        }
    }
    private record SubReportTemplate(String templateName, String pageTitle) {}
}
