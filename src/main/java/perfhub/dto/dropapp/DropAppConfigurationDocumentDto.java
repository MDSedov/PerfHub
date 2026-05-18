package perfhub.dto.dropapp;

/** Метаданные сохраненного YAML-документа ресурса Kubernetes. */
public record DropAppConfigurationDocumentDto(
        String namespace,
        String component,
        String resourceName,
        String fileName) {}
