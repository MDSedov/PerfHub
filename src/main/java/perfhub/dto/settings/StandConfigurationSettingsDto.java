package perfhub.dto.settings;

import perfhub.dto.dropapp.DropAppServiceOptionDto;
import perfhub.dto.dropapp.DropAppServiceSelectionDto;
import java.util.List;

/** Настройки состава конфигурации стенда для сохранения из DropApp. */
public record StandConfigurationSettingsDto(
        List<String> namespaces,
        List<String> components,
        List<DropAppServiceSelectionDto> services,
        List<DropAppServiceOptionDto> availableServices) {}
