package com.example.chronicler.model;

import java.util.List;

public record ConfluenceTemplate(
        int id,
        String shortName,
        String fullName,
        String text,
        List<ConfluenceTemplateChart> charts) {}
