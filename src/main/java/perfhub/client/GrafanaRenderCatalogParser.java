package perfhub.client;

import com.fasterxml.jackson.databind.JsonNode;
import perfhub.dto.grafana.GrafanaRenderDashboard;
import perfhub.dto.grafana.GrafanaRenderDashboard.Panel;
import perfhub.dto.grafana.GrafanaRenderDashboard.Variable;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/** Метаданные изображений; извлечение query/threshold для НФТ этим компонентом не выполняется. */
@Component
public class GrafanaRenderCatalogParser {
    private static final Pattern VARIABLE = Pattern.compile("\\$(?:\\{([a-zA-Z_][a-zA-Z_0-9]*)(?::[^}]+)?}|([a-zA-Z_][a-zA-Z_0-9]*))");
    public GrafanaRenderDashboard parse(String uid, JsonNode envelope) {
        JsonNode dashboard = envelope.path("dashboard");
        Map<String, Variable> variables = variables(dashboard);
        List<Panel> panels = new ArrayList<>();
        if (dashboard.path("panels").isArray()) {
            classic(dashboard.path("panels"), false, variables, panels);
        } else if (dashboard.path("elements").isObject()
                && "RowsLayout".equals(dashboard.path("layout").path("kind").asText())) {
            for (JsonNode row : dashboard.path("layout").path("spec").path("rows")) {
                JsonNode spec = row.path("spec");
                boolean repeated = "variable".equals(spec.path("repeat").path("mode").asText())
                        && "deployment".equals(spec.path("repeat").path("value").asText());
                if (!"GridLayout".equals(spec.path("layout").path("kind").asText())) {
                    throw new IllegalArgumentException("Неподдержанный layout строки Grafana");
                }
                for (JsonNode item : spec.path("layout").path("spec").path("items")) {
                    if (item.path("spec").hasNonNull("repeat")) {
                        throw new IllegalArgumentException("Вложенное повторение Grafana не поддержано");
                    }
                    String ref = item.path("spec").path("element").path("name").asText();
                    JsonNode element = dashboard.path("elements").path(ref);
                    if (!"Panel".equals(element.path("kind").asText())) {
                        throw new IllegalArgumentException("Неизвестный элемент панели Grafana");
                    }
                    add(element.path("spec"), ref, repeated, variables, panels);
                }
            }
        } else {
            throw new IllegalArgumentException("Неподдержанный формат каталога Grafana");
        }
        String slug = envelope.path("meta").path("slug").asText();
        if (slug.isBlank()) {
            throw new IllegalArgumentException("В каталоге Grafana отсутствует slug");
        }
        return new GrafanaRenderDashboard(uid, slug, dashboard.path("title").asText(),
                List.copyOf(panels), Map.copyOf(variables));
    }

    private void classic(JsonNode nodes, boolean parentRepeat, Map<String, Variable> variables, List<Panel> result) {
        boolean repeated = parentRepeat;
        for (JsonNode panel : nodes) {
            if (panel.hasNonNull("repeatPanelId") || panel.hasNonNull("repeatIteration")) {
                continue;
            }
            if ("row".equals(panel.path("type").asText())) {
                if (parentRepeat && !panel.path("repeat").asText().isBlank()) {
                    throw new IllegalArgumentException("Вложенное повторение Grafana не поддержано");
                }
                repeated = "deployment".equals(panel.path("repeat").asText()) || parentRepeat;
                if (panel.path("panels").isArray()) {
                    classic(panel.path("panels"), repeated, variables, result);
                }
            } else {
                add(panel, "panel-" + panel.path("id").asInt(), repeated, variables, result);
            }
        }
    }

    private void add(JsonNode panel, String ref, boolean repeated, Map<String, Variable> variables, List<Panel> result) {
        if (repeated && !panel.path("repeat").asText().isBlank()) {
            throw new IllegalArgumentException("Вложенное повторение панели Grafana не поддержано");
        }
        int id = panel.path("id").asInt();
        if (id <= 0 || !ref.equals("panel-" + id) || result.stream().anyMatch(p -> p.id() == id)) {
            throw new IllegalArgumentException("Некорректная базовая ссылка панели Grafana");
        }
        String queries = panel.has("targets") ? panel.path("targets").toString() : panel.path("data").toString();
        boolean scoped = repeated && dependsOnDeployment(queries, variables, new HashSet<>());
        result.add(new Panel(id, ref, panel.path("title").asText(), panel.path("description").asText(), scoped,
                fingerprint(panel.toString() + variables)));
    }

    private boolean dependsOnDeployment(String text, Map<String, Variable> variables, Set<String> visited) {
        var matcher = VARIABLE.matcher(text);
        while (matcher.find()) {
            String name = matcher.group(1) == null ? matcher.group(2) : matcher.group(1);
            if ("deployment".equals(name)) {
                return true;
            }
            Variable variable = variables.get(name);
            if (variable != null && visited.add(name) && dependsOnDeployment(variable.query(), variables, visited)) {
                return true;
            }
        }
        return false;
    }

    private Map<String, Variable> variables(JsonNode dashboard) {
        Map<String, Variable> result = new LinkedHashMap<>();
        boolean classic = dashboard.path("templating").path("list").isArray();
        JsonNode items = classic ? dashboard.path("templating").path("list") : dashboard.path("variables");
        for (JsonNode item : items) {
            JsonNode spec = classic ? item : item.path("spec");
            String name = spec.path("name").asText();
            JsonNode query = spec.path("query");
            if ("DataQuery".equals(query.path("kind").asText())) {
                query = query.path("spec");
            }
            JsonNode source = classic ? spec.path("datasource") : spec.path("query").path("datasource");
            String datasource = source.isTextual() ? source.asText() : source.path(classic ? "uid" : "name").asText();
            JsonNode current = spec.path("current").path("value");
            String value = current.isTextual() ? current.asText()
                    : current.isArray() && current.size() == 1 ? current.get(0).asText() : "";
            result.put(name, new Variable(name, classic ? spec.path("type").asText() : item.path("kind").asText(),
                    datasource, query.isTextual() ? query.asText() : query.path("query").asText(),
                    query.path("qryType").asInt(-1), spec.path("regex").asText(),
                    spec.path("regexApplyTo").asText("value"), spec.path("multi").asBoolean(),
                    spec.path("includeAll").asBoolean(), spec.hasNonNull("allValue") ? spec.path("allValue").asText() : null, value));
        }
        return result;
    }

    public static String fingerprint(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }
}
