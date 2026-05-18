package perfhub.dto.settings;

import java.util.List;

public record GrafanaDashboardDto(String uid, String slug, List<Integer> panels, boolean enabled) {}
