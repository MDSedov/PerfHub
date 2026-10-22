package perfhub.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import perfhub.dto.configuration.ConnectionTestResponse;
import perfhub.dto.settings.ConfluenceTemplateSaveRequest;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DropAppJsonContractTest {
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    @Test
    void scopeAndDeploymentRoundTripButArrayIsNotCoercedIntoString() throws Exception {
        String body = "{\"reportTitle\":\"Report\",\"templateType\":\"TEST\",\"charts\":[{\"title\":\"CPU\",\"dashboardUid\":\"demo\",\"panelId\":106,\"renderScope\":\"DROPAPP_DEPLOYMENT\",\"deployment\":\"profile\"}]}";
        var value = mapper.readValue(body,ConfluenceTemplateSaveRequest.class);
        assertThat(mapper.readValue(mapper.writeValueAsString(value),ConfluenceTemplateSaveRequest.class)).isEqualTo(value);
        assertThatThrownBy(() -> mapper.readValue(body.replace("\"deployment\":\"profile\"","\"deployment\":[\"profile\"]"),ConfluenceTemplateSaveRequest.class))
                .isInstanceOf(com.fasterxml.jackson.core.JsonProcessingException.class);
        var legacy = mapper.readValue(body.replace(",\"renderScope\":\"DROPAPP_DEPLOYMENT\",\"deployment\":\"profile\"",""),ConfluenceTemplateSaveRequest.class);
        assertThat(legacy.charts().get(0).renderScope()).isNull();
    }

    @Test
    void otherConnectionResponsesKeepThreeFields() throws Exception {
        var json = mapper.readTree(mapper.writeValueAsString(new ConnectionTestResponse("jira",true,"ok")));
        assertThat(json.size()).isEqualTo(3);
        assertThat(json.has("catalog")).isFalse();
    }

    @Test
    void panelOptionsExposeAdditiveApplicabilityWithoutChangingSavedChartShape() throws Exception {
        var service = org.mockito.Mockito.mock(ConfluenceTemplateSettingsService.class);
        org.mockito.Mockito.when(service.listPanelOptions()).thenReturn(java.util.List.of(
                new ConfluenceTemplateSettingsService.PanelOption("legacy", 7, "Legacy -> CPU", "Legacy", "CPU")));
        var controller = new perfhub.controller.settings.ConfluenceTemplateSettingsController(service);
        var json = mapper.readTree(mapper.writeValueAsString(controller.listPanelOptions()));
        var option = json.get(0);
        assertThat(option.size()).isEqualTo(10);
        assertThat(option.get("dashboardUid").asText()).isEqualTo("legacy");
        assertThat(option.get("panelId").asInt()).isEqualTo(7);
        assertThat(option.get("code").asText()).isEqualTo("Legacy -> CPU");
        assertThat(option.get("dashboardTitle").asText()).isEqualTo("Legacy");
        assertThat(option.get("panelTitle").asText()).isEqualTo("CPU");
        assertThat(option.get("renderScope").isNull()).isTrue();
        assertThat(option.get("deployments").isEmpty()).isTrue();
        assertThat(option.get("catalogStatus").isNull()).isTrue();
        assertThat(option.get("catalogUpdatedAt").isNull()).isTrue();
        assertThat(option.get("deploymentApplicability").asText()).isEqualTo("UNKNOWN");
        var chart = new perfhub.dto.settings.ConfluenceTemplateChartDto("CPU", "demo", 106, "CPU", 1L,
                "DROPAPP_DEPLOYMENT", "profile");
        var saved = mapper.readTree(mapper.writeValueAsString(chart));
        assertThat(saved.has("deploymentApplicability")).isFalse();
        assertThat(saved.get("deployment").asText()).isEqualTo("profile");
    }
}
