package com.example.chronicler.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class LoadTestTypeTest {

    @Test
    void fromValueMapsLegacyConfirmToStability() {
        assertThat(LoadTestType.fromValue("confirm")).isEqualTo(LoadTestType.STABILITY);
        assertThat(LoadTestType.fromDatabaseValue("confirm")).isEqualTo(LoadTestType.STABILITY);
    }

    @Test
    void fromValueRejectsUnsupportedValues() {
        assertThatThrownBy(() -> LoadTestType.fromValue("unexpected"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Unsupported load test type: unexpected");
    }
}
