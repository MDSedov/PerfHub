package com.example.chronicler.dto.confluence;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.util.Locale;
import java.util.List;
import java.util.UUID;

public record ConfluenceReportRequest(
        @NotBlank(message = "parentPageId is required") String parentPageId,
        @NotEmpty(message = "tests must not be empty") List<@NotNull UUID> tests) {

    public enum TestType {
        MAX("max-template", "Max Test Report"),
        CONFIRM("confirm-template", "Stability Test Report"),
        SOAK("soak-template", "Soak Test Report");

        private final String templateName;
        private final String displayName;

        TestType(String templateName, String displayName) {
            this.templateName = templateName;
            this.displayName = displayName;
        }

        public String templateName() {
            return templateName;
        }

        @JsonValue
        public String jsonValue() {
            return name().toLowerCase(Locale.ROOT);
        }

        public String displayName() {
            return displayName;
        }

        @JsonCreator
        public static TestType fromJson(String value) {
            if (value == null) {
                return null;
            }
            try {
                return TestType.valueOf(value.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException ex) {
                throw new IllegalArgumentException("Unsupported testType: " + value);
            }
        }
    }
}
