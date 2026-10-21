package perfhub.service;

import perfhub.client.GrafanaClient;
import perfhub.client.GrafanaRenderCatalogParser;
import perfhub.dto.grafana.GrafanaCatalogResult;
import perfhub.dto.grafana.GrafanaCatalogSnapshot;
import perfhub.dto.grafana.GrafanaRenderDashboard;
import perfhub.dto.grafana.GrafanaRenderDashboard.Option;
import perfhub.repository.GrafanaRenderCatalogRepository;
import perfhub.security.CurrentUserProvider;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class GrafanaRenderCatalogService {
    public static final String DEPLOYMENT_SCOPE = "DROPAPP_DEPLOYMENT";
    private static final Pattern DEPLOYMENT = Pattern.compile("[a-z0-9](?:[a-z0-9.-]{0,251}[a-z0-9])?");
    private static final Pattern LABEL_VALUES = Pattern.compile("^label_values\\((.+),\\s*deployment\\)\\s*$", Pattern.DOTALL);
    private static final Logger log = LoggerFactory.getLogger(GrafanaRenderCatalogService.class);
    private final GrafanaClient client;
    private final GrafanaRenderCatalogParser parser;
    private final GrafanaRenderCatalogRepository repository;
    private final CurrentUserProvider currentUser;
    private final GrafanaVariableService variableService;

    public GrafanaRenderCatalogService(GrafanaClient client, GrafanaRenderCatalogParser parser,
            GrafanaRenderCatalogRepository repository, CurrentUserProvider currentUser, GrafanaVariableService variableService) {
        this.client = client;
        this.parser = parser;
        this.repository = repository;
        this.currentUser = currentUser;
        this.variableService = variableService;
    }

    public GrafanaCatalogResult refresh() {
        long owner = owner();
        String revision = repository.revision(owner);
        int updated = 0;
        int failed = 0;
        int options = 0;
        for (var previous : repository.find(owner, revision)) {
            repository.stale(owner, revision, previous.dashboard().uid());
        }
        try {
            for (String uid : client.listRenderDashboardUids()) {
                try {
                    var dashboard = parser.parse(uid, client.loadRenderDashboard(uid));
                    Map<String, String> values = values(dashboard);
                    boolean scoped = dashboard.panels().stream().anyMatch(GrafanaRenderDashboard.Panel::deploymentScoped);
                    String datasource = scoped ? datasource(dashboard, values) : values.getOrDefault("datasource", "");
                    String namespace = values.getOrDefault("namespace", "");
                    List<Option> deployments = scoped ? deploymentOptions(dashboard, values, datasource) : List.of();
                    if (!sameContext(dashboard, values, values(dashboard))) {
                        failed++;
                        continue;
                    }
                    var snapshot = new GrafanaCatalogSnapshot(dashboard, datasource, namespace, values,
                            deployments, Instant.now(), false, owner, revision);
                    if (repository.save(owner, revision, snapshot)) {
                        updated++;
                        options += deployments.size();
                    } else {
                        failed++;
                    }
                } catch (RuntimeException ex) {
                    failed++;
                    log.warn("Grafana render catalog update failed for dashboard {} ({})", uid, ex.getClass().getSimpleName());
                }
            }
        } catch (RuntimeException ex) {
            failed++;
            log.warn("Grafana render catalog discovery failed ({})", ex.getClass().getSimpleName());
        }
        if (failed > 0) {
            String state = updated > 0 ? "PARTIAL" : localSnapshots().isEmpty() ? "UNAVAILABLE" : "STALE";
            return new GrafanaCatalogResult(state,
                    "Каталог Grafana обновлён не полностью. Последний доступный список помечен как устаревший; повторите проверку подключения.",
                    Instant.now());
        }
        return new GrafanaCatalogResult(options == 0 ? "EMPTY" : "READY",
                options == 0 ? "Каталог обновлён: доступных deployment нет." : "Каталог графиков и deployment обновлён.", Instant.now());
    }

    public List<GrafanaCatalogSnapshot> localSnapshots() {
        long owner = owner();
        return repository.find(owner, repository.revision(owner)).stream()
                .filter(snapshot -> sameContext(snapshot.dashboard(), snapshot.variables(), values(snapshot.dashboard())))
                .toList();
    }

    public Optional<CatalogPanel> findPanel(String uid, int panelId) {
        return localSnapshots().stream().filter(s -> s.dashboard().uid().equals(uid))
                .flatMap(s -> s.dashboard().panels().stream().filter(p -> p.id() == panelId).map(p -> new CatalogPanel(s, p)))
                .findFirst();
    }

    public String cacheContext(GrafanaCatalogSnapshot snapshot, String deployment, String reference) {
        return "dropapp-render-v1\n" + snapshot.ownerId() + "\n" + snapshot.sourceRevision() + "\n"
                + snapshot.datasource() + "\n" + snapshot.namespace() + "\n" + deployment + "\n" + reference + "\n$__all";
    }

    public boolean isCurrent(GrafanaCatalogSnapshot snapshot) {
        return snapshot.ownerId() == owner() && snapshot.sourceRevision().equals(repository.revision(owner()))
                && sameContext(snapshot.dashboard(), snapshot.variables(), values(snapshot.dashboard()));
    }

    public Map<String, String> values(GrafanaRenderDashboard dashboard) {
        Map<String, String> result = new LinkedHashMap<>();
        dashboard.variables().forEach((name, variable) -> {
            if (!variable.current().isBlank()) {
                result.put(name, variable.current());
            }
        });
        result.putAll(variableService.loadValues(dashboard.uid()));
        return Map.copyOf(result);
    }

    private boolean sameContext(GrafanaRenderDashboard dashboard, Map<String, String> before, Map<String, String> after) {
        return before.getOrDefault("namespace", "").equals(after.getOrDefault("namespace", ""))
                && before.getOrDefault("datasource", "").equals(after.getOrDefault("datasource", ""));
    }

    private String datasource(GrafanaRenderDashboard dashboard, Map<String, String> values) {
        var variable = dashboard.variables().get("deployment");
        if (variable == null) {
            throw new IllegalArgumentException("Нет переменной deployment");
        }
        String datasource = variable.datasource();
        if ("${datasource}".equals(datasource) || "$datasource".equals(datasource)) {
            datasource = values.getOrDefault("datasource", "");
        }
        if (datasource.isBlank() || datasource.contains("$") || datasource.contains(",")) {
            throw new IllegalArgumentException("Выберите конкретный datasource Grafana");
        }
        return datasource;
    }

    private List<Option> deploymentOptions(GrafanaRenderDashboard dashboard, Map<String, String> values, String datasource) {
        var variable = dashboard.variables().get("deployment");
        var matcher = LABEL_VALUES.matcher(variable.query());
        if ((variable.queryType() != 1 && variable.queryType() != -1) || !matcher.matches()) {
            throw new IllegalArgumentException("Неподдержанный запрос переменной deployment");
        }
        String namespace = values.getOrDefault("namespace", "");
        if (!namespace.matches("[a-z0-9](?:[-a-z0-9]{0,61}[a-z0-9])?")) {
            throw new IllegalArgumentException("Выберите конкретный namespace Grafana");
        }
        // Selector взят из определения. Кавычки и названия метрик не исправляются и не достраиваются.
        String selector = matcher.group(1).replace("${namespace:regex}", namespace)
                .replace("${namespace}", namespace).replace("$namespace", namespace);
        if (selector.contains("$")) {
            throw new IllegalArgumentException("Неподдержанная зависимость переменной deployment");
        }
        Pattern regex = optionRegex(variable.regex());
        List<Option> result = new ArrayList<>();
        for (String raw : client.loadDeploymentLabelValues(datasource, selector)) {
            String text = raw;
            String value = raw;
            if (regex != null) {
                var match = regex.matcher(raw);
                if (!match.find()) {
                    continue;
                }
                value = namedGroup(match, "value", match.groupCount() > 0 ? match.group(1) : match.group());
                text = namedGroup(match, "text", value);
            }
            if (isConcreteDeployment(value)) {
                result.add(new Option(text, value));
            }
        }
        return result.stream().distinct().sorted(Comparator.comparing(Option::text).thenComparing(Option::value)).toList();
    }

    private Pattern optionRegex(String regex) {
        if (regex.isBlank()) {
            return null;
        }
        String expression = regex;
        int flags = 0;
        if (regex.startsWith("/")) {
            int end = regex.lastIndexOf('/');
            if (end <= 0 || !List.of("", "i").contains(regex.substring(end + 1))) {
                throw new IllegalArgumentException("Неподдержанный regex deployment");
            }
            flags = regex.endsWith("/i") ? Pattern.CASE_INSENSITIVE : 0;
            expression = regex.substring(1, end);
        }
        return Pattern.compile(expression, flags);
    }

    private String namedGroup(java.util.regex.Matcher matcher, String group, String fallback) {
        try {
            String value = matcher.group(group);
            return value == null ? fallback : value;
        } catch (IllegalArgumentException ex) {
            return fallback;
        }
    }

    public static boolean isConcreteDeployment(String value) {
        return value != null && DEPLOYMENT.matcher(value).matches() && !"all".equalsIgnoreCase(value);
    }

    public static String requireDeployment(String value) {
        if (!isConcreteDeployment(value)) {
            throw new IllegalArgumentException("Для графика DropApp выберите один конкретный deployment");
        }
        return value;
    }

    private long owner() {
        return currentUser.currentUserId().orElse(0L);
    }

    public record CatalogPanel(GrafanaCatalogSnapshot snapshot, GrafanaRenderDashboard.Panel panel) {}
}
