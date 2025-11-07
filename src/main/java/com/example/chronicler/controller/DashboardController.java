package com.example.chronicler.controller;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/** Отвечает за отображение главной страницы с формой поиска тестов. */
@Controller
public class DashboardController {

    @GetMapping("/")
    public String index() {
        return "index";
    }
}
