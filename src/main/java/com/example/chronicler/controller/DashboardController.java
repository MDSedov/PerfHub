package com.example.chronicler.controller;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

/** Отвечает за отображение главной страницы с формой поиска тестов. */
@Controller
public class DashboardController {

    @GetMapping({"/", "/tests"})
    public String tests(Model model) {
        model.addAttribute("activePage", "tests");
        return "index";
    }

    @GetMapping("/reports")
    public String reports(Model model) {
        model.addAttribute("activePage", "reports");
        return "reports";
    }

    /** Отображает страницу конфигурации DropApp. */
    @GetMapping("/configuration")
    public String configuration(Model model) {
        model.addAttribute("activePage", "configuration");
        return "configuration";
    }

    @GetMapping("/settings")
    public String settings(Model model) {
        model.addAttribute("activePage", "settings");
        return "settings";
    }
}
