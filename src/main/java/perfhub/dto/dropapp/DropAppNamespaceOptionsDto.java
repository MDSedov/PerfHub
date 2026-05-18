package perfhub.dto.dropapp;

import java.util.List;

/** Список доступных namespace и namespace, выбранных в настройках по умолчанию. */
public record DropAppNamespaceOptionsDto(List<String> available, List<String> selected) {}
