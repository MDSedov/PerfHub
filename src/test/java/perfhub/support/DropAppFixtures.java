package perfhub.support;

import perfhub.dto.grafana.GrafanaCatalogSnapshot;
import perfhub.dto.grafana.GrafanaRenderDashboard;
import perfhub.dto.grafana.GrafanaRenderDashboard.Variable;
import perfhub.model.ConfluenceTemplate;
import perfhub.model.ConfluenceTemplateChart;
import java.time.Instant;
import java.util.List;
import java.util.Map;

public final class DropAppFixtures {
    public static final String SCOPE = "DROPAPP_DEPLOYMENT";
    public static final String QUERY = "label_values({\"kube_deployment_status_replicas{namespace=~\"$namespace\"}\"},deployment)";
    public static final byte[] PNG = {(byte) 0x89,0x50,0x4e,0x47,0x0d,0x0a,0x1a,0x0a};

    private DropAppFixtures() {}

    public static Variable variable(String name, String query, String current, boolean all) {
        return new Variable(name, "query", "${datasource}", query, name.equals("deployment") ? 1 : 3,
                "", "value", all, all, null, current);
    }

    public static GrafanaCatalogSnapshot snapshot() {
        var variables = Map.of("datasource", variable("datasource", "prometheus", "demo-source", false),
                "namespace", variable("namespace", "label_values(namespace)", "demo", false),
                "deployment", variable("deployment", QUERY, "dashboard-current", true),
                "pod", variable("pod", "query_result(kube_pod_labels{pod=~\"^(${deployment:regex})-[^-]+-[^-]+$\"})", "single-pod", true),
                "container", variable("container", "query_result(kube_pod_container_info{pod=~\"${pod:regex}\"})", "single-container", true));
        var dashboard = new GrafanaRenderDashboard("demo", "dropapp", "DropApp", List.of(
                new GrafanaRenderDashboard.Panel(106,"panel-106","CPU","",true,"cpu-v1"),
                new GrafanaRenderDashboard.Panel(78,"panel-78","RAM","",true,"ram-v1")), variables);
        return new GrafanaCatalogSnapshot(dashboard, "demo-source", "demo", Map.of("datasource","demo-source","namespace","demo"),
                List.of(new GrafanaRenderDashboard.Option("Other","other"),new GrafanaRenderDashboard.Option("Profile","profile")), Instant.now(),false);
    }

    public static ConfluenceTemplateChart chart(long id, int panel, String deployment, int order) {
        return new ConfluenceTemplateChart(id,1,panel == 106 ? "CPU" : "RAM","demo",panel,order,SCOPE,deployment);
    }

    public static ConfluenceTemplate template(List<ConfluenceTemplateChart> charts) {
        return new ConfluenceTemplate(1,"max_template","Test","Test","", "",charts);
    }
}
