package com.example.chronicler.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.chronicler.client.ConfluenceClient;
import com.example.chronicler.dto.confluence.ConfluenceCredentials;
import com.example.chronicler.dto.confluence.ConfluencePage;
import com.example.chronicler.dto.confluence.ConfluenceReportHierarchyResponse;
import com.example.chronicler.dto.confluence.ConfluenceReportRequest;
import com.example.chronicler.dto.confluence.ConfluenceReportResponse;
import com.example.chronicler.dto.grafana.GrafanaRenderRequest;
import com.example.chronicler.dto.grafana.GrafanaRenderResponse;
import com.example.chronicler.dto.grafana.GrafanaRenderedDashboard;
import com.example.chronicler.dto.grafana.GrafanaRenderedPanel;
import com.example.chronicler.exception.ConfluenceOperationException;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ConfluenceServiceTest {

    @Mock
    private ConfluenceClient confluenceClient;

    @Mock
    private GrafanaService grafanaService;

    @Mock
    private TemplateLoader templateLoader;

    private ConfluenceService confluenceService;

    @BeforeEach
    void setUp() {
        confluenceService = new ConfluenceService(confluenceClient, grafanaService, templateLoader);
    }

    @Test
    void createReportsCreatesPagesAndUploadsPanels() {
        ConfluenceCredentials credentials = new ConfluenceCredentials("user", "token");
        UUID firstTestUuid = UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee");
        UUID secondTestUuid = UUID.fromString("ffffffff-1111-2222-3333-444444444444");
        ConfluenceReportRequest request = new ConfluenceReportRequest(
                "123",
                List.of(
                        new ConfluenceReportRequest.TestReport(firstTestUuid, ConfluenceReportRequest.TestType.MAX),
                        new ConfluenceReportRequest.TestReport(secondTestUuid, ConfluenceReportRequest.TestType.SOAK)));

        byte[] firstImage = new byte[] {1, 2, 3};
        byte[] secondImage = new byte[] {4, 5, 6};
        GrafanaRenderedPanel firstPanel = new GrafanaRenderedPanel(
                7, "First panel", "First description", Base64.getEncoder().encodeToString(firstImage));
        GrafanaRenderedPanel secondPanel = new GrafanaRenderedPanel(
                9, "Second panel", "Second description", Base64.getEncoder().encodeToString(secondImage));
        GrafanaRenderedDashboard firstDashboard = new GrafanaRenderedDashboard("uid-1", "slug-1", List.of(firstPanel));
        GrafanaRenderedDashboard secondDashboard =
                new GrafanaRenderedDashboard("uid-2", "slug-2", List.of(secondPanel));
        when(grafanaService.renderPanels(any(GrafanaRenderRequest.class)))
                .thenReturn(new GrafanaRenderResponse("from", "to", List.of(firstDashboard)))
                .thenReturn(new GrafanaRenderResponse("from", "to", List.of(secondDashboard)));

        ConfluencePage parentPage = new ConfluencePage("123", "page", "Parent", 4, "", "SPACE");
        when(confluenceClient.getPage(credentials, "123")).thenReturn(parentPage);

        when(templateLoader.loadTemplate("max-template")).thenReturn("<p>Max template</p>");
        when(templateLoader.loadTemplate("soak-template")).thenReturn("<p>Soak template</p>");

        String firstTitle = request.tests().get(0).testType().displayName() + " - " + firstTestUuid;
        String secondTitle = request.tests().get(1).testType().displayName() + " - " + secondTestUuid;
        ConfluencePage firstCreatedPage = new ConfluencePage("456", "page", firstTitle, 1, "<p>Max template</p>", "SPACE");
        ConfluencePage secondCreatedPage =
                new ConfluencePage("789", "page", secondTitle, 1, "<p>Soak template</p>", "SPACE");
        when(confluenceClient.createChildPage(credentials, "123", "SPACE", firstTitle, "<p>Max template</p>"))
                .thenReturn(firstCreatedPage);
        when(confluenceClient.createChildPage(credentials, "123", "SPACE", secondTitle, "<p>Soak template</p>"))
                .thenReturn(secondCreatedPage);

        when(confluenceClient.uploadAttachment(eq(credentials), eq("456"), anyString(), eq(firstImage)))
                .thenReturn("grafana-panel-7.png");
        when(confluenceClient.uploadAttachment(eq(credentials), eq("789"), anyString(), eq(secondImage)))
                .thenReturn("grafana-panel-9.png");

        ConfluenceReportResponse response = confluenceService.createReports(credentials, request);

        assertThat(response.reports()).hasSize(2);
        ConfluenceReportResponse.ReportPage firstReport = response.reports().get(0);
        ConfluenceReportResponse.ReportPage secondReport = response.reports().get(1);
        assertThat(firstReport.testUuid()).isEqualTo(firstTestUuid);
        assertThat(firstReport.pageId()).isEqualTo("456");
        assertThat(firstReport.attachmentNames()).singleElement().contains("grafana-panel-7-");
        assertThat(secondReport.testUuid()).isEqualTo(secondTestUuid);
        assertThat(secondReport.pageId()).isEqualTo("789");
        assertThat(secondReport.attachmentNames()).singleElement().contains("grafana-panel-9-");

        ArgumentCaptor<GrafanaRenderRequest> renderRequestCaptor = ArgumentCaptor.forClass(GrafanaRenderRequest.class);
        verify(grafanaService, times(2)).renderPanels(renderRequestCaptor.capture());
        assertThat(renderRequestCaptor.getAllValues().get(0).testUuid()).isEqualTo(firstTestUuid);
        assertThat(renderRequestCaptor.getAllValues().get(1).testUuid()).isEqualTo(secondTestUuid);

        verify(confluenceClient).createChildPage(credentials, "123", "SPACE", firstTitle, "<p>Max template</p>");
        verify(confluenceClient).createChildPage(credentials, "123", "SPACE", secondTitle, "<p>Soak template</p>");
        verify(confluenceClient).uploadAttachment(eq(credentials), eq("456"), anyString(), eq(firstImage));
        verify(confluenceClient).uploadAttachment(eq(credentials), eq("789"), anyString(), eq(secondImage));

        ArgumentCaptor<String> bodyCaptor = ArgumentCaptor.forClass(String.class);
        verify(confluenceClient).updatePage(eq(credentials), eq(firstCreatedPage), bodyCaptor.capture());
        String firstBody = bodyCaptor.getValue();
        assertThat(firstBody).contains("<p>Max template</p>");
        assertThat(firstBody).contains("<h3>First panel</h3>");

        verify(confluenceClient).updatePage(eq(credentials), eq(secondCreatedPage), bodyCaptor.capture());
        String secondBody = bodyCaptor.getValue();
        assertThat(secondBody).contains("<p>Soak template</p>");
        assertThat(secondBody).contains("<h3>Second panel</h3>");
    }

    @Test
    void createReportsThrowsWhenPanelsMissing() {
        ConfluenceCredentials credentials = new ConfluenceCredentials("user", "token");
        UUID testUuid = UUID.fromString("ffffffff-1111-2222-3333-444444444444");
        ConfluenceReportRequest request =
                new ConfluenceReportRequest(
                        "123", List.of(new ConfluenceReportRequest.TestReport(
                                testUuid, ConfluenceReportRequest.TestType.SOAK)));

        GrafanaRenderResponse renderResponse = new GrafanaRenderResponse("from", "to", List.of());
        when(grafanaService.renderPanels(any(GrafanaRenderRequest.class))).thenReturn(renderResponse);
        ConfluencePage parentPage = new ConfluencePage("123", "page", "Parent", 4, "", "SPACE");
        when(confluenceClient.getPage(credentials, "123")).thenReturn(parentPage);

        assertThatThrownBy(() -> confluenceService.createReports(credentials, request))
                .isInstanceOf(ConfluenceOperationException.class)
                .hasMessage("Grafana render does not contain any panels");
    }

    @Test
    void createReportsThrowsWhenParentHasNoSpace() {
        ConfluenceCredentials credentials = new ConfluenceCredentials("user", "token");
        UUID testUuid = UUID.fromString("aaaaaaaa-0000-bbbb-cccc-dddddddddddd");
        ConfluenceReportRequest request =
                new ConfluenceReportRequest(
                        "parent",
                        List.of(new ConfluenceReportRequest.TestReport(
                                testUuid, ConfluenceReportRequest.TestType.CONFIRM)));

        byte[] imageBytes = new byte[] {9, 8, 7};
        String encodedImage = Base64.getEncoder().encodeToString(imageBytes);
        GrafanaRenderedPanel panel = new GrafanaRenderedPanel(5, "Panel", "", encodedImage);
        GrafanaRenderedDashboard dashboard = new GrafanaRenderedDashboard("uid", "slug", List.of(panel));
        GrafanaRenderResponse renderResponse = new GrafanaRenderResponse("from", "to", List.of(dashboard));
        when(grafanaService.renderPanels(any(GrafanaRenderRequest.class))).thenReturn(renderResponse);

        ConfluencePage parentPage = new ConfluencePage("parent", "page", "Parent", 2, "", null);
        when(confluenceClient.getPage(credentials, "parent")).thenReturn(parentPage);

        assertThatThrownBy(() -> confluenceService.createReports(credentials, request))
                .isInstanceOf(ConfluenceOperationException.class)
                .hasMessage("Parent page does not contain space key information");
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
