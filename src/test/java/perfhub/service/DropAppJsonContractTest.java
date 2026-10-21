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
}
