package com.example.chronicler.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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
import com.example.chronicler.service.TemplateLoader;
import java.util.Base64;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ConfluenceServiceTest {

    @Mock
    private ConfluenceClient confluenceClient;

    @Mock
    private GrafanaRenderCache grafanaRenderCache;

    @Mock
    private TemplateLoader templateLoader;

    private ConfluenceService confluenceService;

    @BeforeEach
    void setUp() {
        confluenceService = new ConfluenceService(confluenceClient, grafanaRenderCache, templateLoader);
    }

    @Test
    void publishLatestGrafanaRenderUploadsAttachmentAndUpdatesPage() {
        byte[] imageBytes = new byte[] {1, 2, 3};
        String encodedImage = Base64.getEncoder().encodeToString(imageBytes);
        GrafanaRenderedPanel panel = new GrafanaRenderedPanel(42, encodedImage);
        GrafanaRenderedDashboard dashboard = new GrafanaRenderedDashboard("uid", "slug", List.of(panel));
        GrafanaRenderResponse cachedResponse = new GrafanaRenderResponse("from", "to", List.of(dashboard));

        when(grafanaRenderCache.getLatest()).thenReturn(Optional.of(cachedResponse));
        ConfluencePage page = new ConfluencePage("123", "page", "Title", 7, "<p>Existing</p>", "SPACE");
        ConfluenceCredentials credentials = new ConfluenceCredentials("user", "token");
        when(confluenceClient.getPage(credentials, "123")).thenReturn(page);
        when(confluenceClient.uploadAttachment(eq(credentials), eq("123"), any(), eq(imageBytes)))
                .thenReturn("attachment.png");

        ConfluenceReportResponse response = confluenceService.publishLatestGrafanaRender(
                credentials, new ConfluenceReportRequest("123"));

        assertThat(response.pageId()).isEqualTo("123");
        assertThat(response.attachmentName()).contains("grafana-panel-42-");

        verify(confluenceClient).getPage(credentials, "123");
        verify(confluenceClient).uploadAttachment(eq(credentials), eq("123"), any(), eq(imageBytes));
        ArgumentCaptor<String> bodyCaptor = ArgumentCaptor.forClass(String.class);
        verify(confluenceClient)
                .updatePage(eq(credentials), eq(page), bodyCaptor.capture());
        assertThat(bodyCaptor.getValue()).contains("<ac:image>");
    }

    @Test
    void publishLatestGrafanaRenderFailsWhenCacheEmpty() {
        when(grafanaRenderCache.getLatest()).thenReturn(Optional.empty());

        assertThatThrownBy(() -> confluenceService.publishLatestGrafanaRender(
                        new ConfluenceCredentials("login", "token"), new ConfluenceReportRequest("123")))
                .isInstanceOf(GrafanaRenderNotCachedException.class)
                .hasMessage("Grafana render result is not cached yet");
    }

    @Test
    void publishLatestGrafanaRenderFailsWhenCredentialsMissing() {
        when(grafanaRenderCache.getLatest()).thenReturn(Optional.of(
                new GrafanaRenderResponse("from", "to", List.of(new GrafanaRenderedDashboard("u", "s", List.of())))));

        assertThatThrownBy(() -> confluenceService.publishLatestGrafanaRender(
                        new ConfluenceCredentials("", "token"), new ConfluenceReportRequest("123")))
                .isInstanceOf(ConfluenceOperationException.class);
    }

    @Test
    void createReportHierarchyCreatesParentAndChildrenUsingTemplates() {
        ConfluenceCredentials credentials = new ConfluenceCredentials("user", "token");
        ConfluencePage parentPage = new ConfluencePage("321", "page", "Parent", 3, "", "SPACE");
        when(confluenceClient.getPage(credentials, "321")).thenReturn(parentPage);

        when(templateLoader.loadTemplate("overall-report")).thenReturn("<p>Overview</p>");
        when(templateLoader.loadTemplate("load-report")).thenReturn("<p>Load</p>");
        when(templateLoader.loadTemplate("stress-report")).thenReturn("<p>Stress</p>");
        when(templateLoader.loadTemplate("soak")).thenReturn("<p>Soak</p>");

        ConfluencePage overviewPage = new ConfluencePage("111", "page", "Automated Test Report", 1, "", "SPACE");
        ConfluencePage loadPage = new ConfluencePage("222", "page", "Load", 1, "", "SPACE");
        ConfluencePage stressPage = new ConfluencePage("333", "page", "Stress", 1, "", "SPACE");
        ConfluencePage soakPage = new ConfluencePage("444", "page", "Soak", 1, "", "SPACE");

        when(confluenceClient.createChildPage(credentials, "321", "SPACE", "Automated Test Report", "<p>Overview</p>"))
                .thenReturn(overviewPage);
        when(confluenceClient.createChildPage(credentials, "111", "SPACE", "Load Test Summary", "<p>Load</p>"))
                .thenReturn(loadPage);
        when(confluenceClient.createChildPage(credentials, "111", "SPACE", "Stress Test Summary", "<p>Stress</p>"))
                .thenReturn(stressPage);
        when(confluenceClient.createChildPage(credentials, "111", "SPACE", "Soak Test Summary", "<p>Soak</p>"))
                .thenReturn(soakPage);

        ConfluenceReportHierarchyResponse response =
                confluenceService.createReportHierarchy(credentials, "321");

        assertThat(response.reportPageId()).isEqualTo("111");
        assertThat(response.childPageIds()).containsExactly("222", "333", "444");

        InOrder inOrder = org.mockito.Mockito.inOrder(confluenceClient);
        inOrder.verify(confluenceClient).getPage(credentials, "321");
        inOrder.verify(confluenceClient)
                .createChildPage(credentials, "321", "SPACE", "Automated Test Report", "<p>Overview</p>");
        inOrder.verify(confluenceClient)
                .createChildPage(credentials, "111", "SPACE", "Load Test Summary", "<p>Load</p>");
        inOrder.verify(confluenceClient)
                .createChildPage(credentials, "111", "SPACE", "Stress Test Summary", "<p>Stress</p>");
        inOrder.verify(confluenceClient)
                .createChildPage(credentials, "111", "SPACE", "Soak Test Summary", "<p>Soak</p>");
    }

    @Test
    void createReportHierarchyThrowsWhenParentHasNoSpace() {
        ConfluenceCredentials credentials = new ConfluenceCredentials("user", "token");
        ConfluencePage parentPage = new ConfluencePage("321", "page", "Parent", 3, "", null);
        when(confluenceClient.getPage(credentials, "321")).thenReturn(parentPage);

        assertThatThrownBy(() -> confluenceService.createReportHierarchy(credentials, "321"))
                .isInstanceOf(ConfluenceOperationException.class)
                .hasMessage("Parent page does not contain space key information");
    }
}
