package com.example.chronicler.model;

import java.util.List;

public record ConfluenceTemplate(
        int id,
        String shortName,
        String fullName,
        String reportTitle,
        String reportTitleShort,
        ConfluenceTemplateType templateType,
        String templatePageId,
        String text,
        List<ConfluenceTemplateChart> charts) {

    public ConfluenceTemplate(
            int id,
            String shortName,
            String fullName,
            String reportTitle,
            ConfluenceTemplateType templateType,
            String templatePageId,
            String text,
            List<ConfluenceTemplateChart> charts) {
        this(id, shortName, fullName, reportTitle, reportTitle, templateType, templatePageId, text, charts);
    }

    public ConfluenceTemplate(
            int id,
            String shortName,
            String fullName,
            String reportTitle,
            String reportTitleShort,
            String templatePageId,
            String text,
            List<ConfluenceTemplateChart> charts) {
        this(id, shortName, fullName, reportTitle, reportTitleShort, ConfluenceTemplateType.TEST, templatePageId, text, charts);
    }

    public ConfluenceTemplate(
            int id,
            String shortName,
            String fullName,
            String reportTitle,
            String templatePageId,
            String text,
            List<ConfluenceTemplateChart> charts) {
        this(id, shortName, fullName, reportTitle, reportTitle, ConfluenceTemplateType.TEST, templatePageId, text, charts);
    }

    public ConfluenceTemplate(
            int id,
            String shortName,
            String fullName,
            String reportTitle,
            String text,
            List<ConfluenceTemplateChart> charts) {
        this(id, shortName, fullName, reportTitle, reportTitle, ConfluenceTemplateType.TEST, null, text, charts);
    }
}
