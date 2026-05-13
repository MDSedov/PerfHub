package com.example.chronicler.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.chronicler.dto.BackgroundErrorDto;
import java.util.List;
import org.junit.jupiter.api.Test;

class BackgroundErrorServiceTest {

    @Test
    void defaultDrainDoesNotConsumeLoadTestScopedErrors() {
        BackgroundErrorService service = new BackgroundErrorService();

        service.publish(BackgroundErrorService.LOAD_TESTS_SCOPE, "load test error");
        service.publish("global error");

        List<BackgroundErrorDto> globalErrors = service.drain();
        List<BackgroundErrorDto> loadTestErrors = service.drain(BackgroundErrorService.LOAD_TESTS_SCOPE);

        assertEquals(1, globalErrors.size());
        assertEquals("global error", globalErrors.get(0).message());
        assertEquals(BackgroundErrorService.GLOBAL_SCOPE, globalErrors.get(0).scope());
        assertEquals(1, loadTestErrors.size());
        assertEquals("load test error", loadTestErrors.get(0).message());
        assertEquals(BackgroundErrorService.LOAD_TESTS_SCOPE, loadTestErrors.get(0).scope());
    }

    @Test
    void scopedDrainAlsoConsumesGlobalErrors() {
        BackgroundErrorService service = new BackgroundErrorService();

        service.publish("global error");
        service.publish(BackgroundErrorService.LOAD_TESTS_SCOPE, "load test error");

        List<BackgroundErrorDto> errors = service.drain(BackgroundErrorService.LOAD_TESTS_SCOPE);

        assertEquals(2, errors.size());
        assertTrue(errors.stream().anyMatch(error -> error.message().equals("global error")));
        assertTrue(errors.stream().anyMatch(error -> error.message().equals("load test error")));
    }
}
