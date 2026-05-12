package com.example.chronicler.model;

import org.springframework.util.StringUtils;

public enum ConfluenceTemplateType {
    RELEASE,
    TEST;

    public static ConfluenceTemplateType fromDatabaseValue(String value) {
        if (!StringUtils.hasText(value)) {
            return TEST;
        }
        try {
            return ConfluenceTemplateType.valueOf(value.trim().toUpperCase());
        } catch (IllegalArgumentException ex) {
            return TEST;
        }
    }
}
