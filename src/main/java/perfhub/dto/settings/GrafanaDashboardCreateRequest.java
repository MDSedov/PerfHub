package perfhub.dto.settings;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.List;

public record GrafanaDashboardCreateRequest(
        @NotBlank String uid,
        @NotBlank String slug,
        @NotNull List<Integer> panels,
        Boolean enabled) {}
