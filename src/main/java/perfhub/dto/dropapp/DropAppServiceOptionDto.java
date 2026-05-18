package perfhub.dto.dropapp;

/** Сервис DropApp, доступный для выгрузки в выбранном namespace. */
public record DropAppServiceOptionDto(
        String namespace,
        String serviceName,
        Integer podCount,
        String release) {}
