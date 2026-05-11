package com.example.chronicler.config;

import com.example.chronicler.service.ApplicationUserService;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

@Component
public class BootstrapAdminInitializer implements ApplicationRunner {

    private final ApplicationUserService userService;

    public BootstrapAdminInitializer(ApplicationUserService userService) {
        this.userService = userService;
    }

    @Override
    public void run(ApplicationArguments args) {
        userService.ensureBootstrapAdmin();
    }
}
