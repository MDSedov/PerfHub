package com.example.chronicler.registry;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

class VmQueryRegistryTest {

    @Test
    void loadsQueriesFromPropertiesFile() throws IOException {
        VmQueryRegistry registry = new VmQueryRegistry(new ClassPathResource("vm-queries.properties"));

        assertThat(registry.getAll()).containsEntry("intensity", "jmeterall.a.count");
        assertThat(registry.findQuery("response_time_99")).contains("jmeterall.a.pct99");
    }
}
