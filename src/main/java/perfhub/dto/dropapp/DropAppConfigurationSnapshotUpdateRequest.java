package perfhub.dto.dropapp;

/** Запрос на обновление метаданных сохраненной конфигурации DropApp. */
public record DropAppConfigurationSnapshotUpdateRequest(
        String release,
        String comment) {}
