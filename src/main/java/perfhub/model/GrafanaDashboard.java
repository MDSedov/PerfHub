package perfhub.model;

import java.util.List;

public record GrafanaDashboard(String uid, String slug, List<Integer> panels, boolean enabled) {}
