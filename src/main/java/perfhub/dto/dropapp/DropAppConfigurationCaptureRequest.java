package perfhub.dto.dropapp;

import java.util.List;

public record DropAppConfigurationCaptureRequest(
        List<String> namespaces,
        List<String> components,
        String comment,
        List<DropAppServiceSelectionDto> services) {

    public DropAppConfigurationCaptureRequest(
            List<String> namespaces,
            List<String> components,
            String comment) {
        this(namespaces, components, comment, List.of());
    }
}
