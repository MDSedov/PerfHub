package perfhub.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GrafanaRenderCatalogParserTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final GrafanaRenderCatalogParser parser = new GrafanaRenderCatalogParser();

    // Обезличенные структурные примеры по фото; это не восстановленный полный dashboard.
    @Test
    void classicFlatRepeatKeepsBasePanelsAndVariableDependency() throws Exception {
        var dashboard = parser.parse("demo", mapper.readTree("""
                {"meta":{"slug":"dropapp"},"dashboard":{"title":"dropapp","panels":[
                  {"id":1,"type":"row","title":"ALL","panels":[{"id":42,"title":"ordinary"}]},
                  {"id":135,"type":"row","repeat":"deployment"},
                  {"id":106,"title":"CPU","targets":[{"expr":"${pod:regex}"}]},
                  {"id":122,"title":"Request","targets":[{"expr":"${deployment:regex}"}]},
                  {"id":900,"repeatPanelId":106,"title":"dynamic copy"},
                  {"id":136,"type":"row","title":"NETWORK"},
                  {"id":129,"title":"Network","targets":[{"expr":"$namespace"}]}],
                  "templating":{"list":[
                    {"name":"deployment","type":"query","multi":true,"includeAll":true,
                     "regex":"","query":{"qryType":1,"query":"label_values(metric, deployment)"}},
                    {"name":"pod","type":"query","query":{"query":"${deployment:regex}"}}]}}}
                """));
        assertThat(dashboard.panels()).extracting(p -> p.id()).containsExactly(42,106,122,129);
        assertThat(dashboard.panels()).extracting(p -> p.deploymentScoped()).containsExactly(false,true,true,false);
        assertThat(dashboard.panels().get(1).elementRef()).isEqualTo("panel-106");
        assertThat(dashboard.variables().get("deployment").queryType()).isEqualTo(1);
        assertThat(dashboard.variables().get("deployment").allValue()).isNull();
    }

    @Test
    void rowsLayoutUsesElementsAndDoesNotScopeUnfilteredPanel() throws Exception {
        var dashboard = parser.parse("demo", mapper.readTree("""
                {"meta":{"slug":"dropapp"},"dashboard":{"title":"dropapp",
                "elements":{"panel-106":{"kind":"Panel","spec":{"id":106,"title":"CPU",
                  "data":{"spec":{"queries":[{"spec":{"query":{"spec":{"expr":"$deployment"}}}}]}}}},
                  "panel-129":{"kind":"Panel","spec":{"id":129,"title":"Network"}}},
                "variables":[{"kind":"QueryVariable","spec":{"name":"deployment","query":{
                  "kind":"DataQuery","spec":{"qryType":1,"query":"label_values(metric, deployment)"}}}}],
                "layout":{"kind":"RowsLayout","spec":{"rows":[{"kind":"RowsLayoutRow","spec":{
                  "repeat":{"mode":"variable","value":"deployment"},"layout":{"kind":"GridLayout",
                  "spec":{"items":[{"spec":{"element":{"name":"panel-106"}}},
                  {"spec":{"element":{"name":"panel-129"}}}]}}}}]}}}}
                """));
        assertThat(dashboard.panels()).extracting(p -> p.deploymentScoped()).containsExactly(true,false);
    }

    @Test
    void rejectsUnknownEnvelopeRatherThanPublishingEmptyCatalog() throws Exception {
        var json = mapper.readTree("{\"dashboard\":{\"unknown\":[]}}");
        assertThatThrownBy(() -> parser.parse("demo",json)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void refusesToInventReferenceForNestedRepeat() throws Exception {
        var json = mapper.readTree("""
                {"meta":{"slug":"dropapp"},"dashboard":{"panels":[
                {"id":135,"type":"row","repeat":"deployment"},
                {"id":106,"type":"timeseries","repeat":"pod","targets":[{"expr":"$deployment"}]}]}}
                """);
        assertThatThrownBy(() -> parser.parse("demo",json)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Вложенное повторение");
    }
}
