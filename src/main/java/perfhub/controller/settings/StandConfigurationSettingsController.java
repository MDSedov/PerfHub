package perfhub.controller.settings;

import perfhub.dto.settings.StandConfigurationSettingsDto;
import perfhub.dto.settings.StandConfigurationSettingsUpdateRequest;
import perfhub.service.StandConfigurationSettingsService;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** REST-контроллер настроек конфигурации стенда. */
@RestController
@RequestMapping(path = "/api/settings/stand-configuration", produces = MediaType.APPLICATION_JSON_VALUE)
public class StandConfigurationSettingsController {

    private final StandConfigurationSettingsService settingsService;

    public StandConfigurationSettingsController(StandConfigurationSettingsService settingsService) {
        this.settingsService = settingsService;
    }

    @GetMapping
    public StandConfigurationSettingsDto getSettings() {
        return settingsService.getSettings();
    }

    @PatchMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    public StandConfigurationSettingsDto updateSettings(
            @RequestBody(required = false) StandConfigurationSettingsUpdateRequest request) {
        return settingsService.updateSettings(request);
    }
}
