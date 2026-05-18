package perfhub.dto.dropapp;

/** Выбранный пользователем сервис DropApp для выгрузки конфигурации. */
public record DropAppServiceSelectionDto(String namespace, String serviceName) {}
