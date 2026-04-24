package com.example.chronicler.controller;

import com.example.chronicler.model.RequirementMetricType;
import java.util.Arrays;
import java.util.List;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;

/** Отвечает за отображение страниц пользовательского интерфейса. */
@Controller
public class DashboardController {

    @ModelAttribute("activeSettingsSection")
    public String activeSettingsSection() {
        return null;
    }

    @GetMapping("/")
    public String index() {
        return "redirect:/main";
    }

    @GetMapping("/main")
    public String main(Model model) {
        model.addAttribute("activePage", "main");
        return "main";
    }

    @GetMapping("/tests")
    public String tests(Model model) {
        model.addAttribute("activePage", "tests");
        return "tests";
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
    public String settingsRoot() {
        return "redirect:/settings/connections";
    }

    @GetMapping("/settings/connections")
    public String settingsConnections(Model model) {
        model.addAttribute("activePage", "settings");
        model.addAttribute("activeSettingsSection", "connections");
        return "settings";
    }

    @GetMapping("/settings/templates")
    public String settingsTemplates(Model model) {
        model.addAttribute("activePage", "settings");
        model.addAttribute("activeSettingsSection", "templates");
        return "settings-templates";
    }

    @GetMapping("/settings/requirements")
    public String settingsRequirements(Model model) {
        model.addAttribute("activePage", "settings");
        model.addAttribute("activeSettingsSection", "requirements");
        model.addAttribute("metricTypes", metricTypes());
        return "settings-requirements";
    }

    @GetMapping("/settings/victoria-metrics")
    public String settingsVictoriaMetrics(Model model) {
        model.addAttribute("activePage", "settings");
        model.addAttribute("activeSettingsSection", "victoria-metrics");
        return "settings-victoria-metrics";
    }

    @GetMapping("/settings/grafana-dashboards")
    public String settingsGrafana(Model model) {
        model.addAttribute("activePage", "settings");
        model.addAttribute("activeSettingsSection", "grafana-dashboards");
        return "settings-grafana-dashboards";
    }

    @GetMapping("/grafana-panels")
    public String grafanaPanels(Model model) {
        model.addAttribute("activePage", "grafana-panels");
        return "grafana-panels";
    }

    private List<String> metricTypes() {
        return Arrays.stream(RequirementMetricType.values())
                .map(Enum::name)
                .toList();
    }
}
