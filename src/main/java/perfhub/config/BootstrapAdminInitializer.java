package perfhub.config;

import perfhub.model.ApplicationUser;
import perfhub.repository.ConfigurationRepository;
import perfhub.service.ApplicationUserService;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

@Component
public class BootstrapAdminInitializer implements ApplicationRunner {

    private final ApplicationUserService userService;
    private final ConfigurationRepository configurationRepository;

    public BootstrapAdminInitializer(
            ApplicationUserService userService,
            ConfigurationRepository configurationRepository) {
        this.userService = userService;
        this.configurationRepository = configurationRepository;
    }

    @Override
    public void run(ApplicationArguments args) {
        ApplicationUser admin = userService.ensureBootstrapAdmin();
        configurationRepository.copyGlobalConnectionValuesToUser(admin.id());
    }
}
