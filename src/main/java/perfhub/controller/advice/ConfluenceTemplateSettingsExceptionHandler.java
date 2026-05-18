package com.example.chronicler.controller.advice;

import com.example.chronicler.controller.settings.ConfluenceTemplateSettingsController;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;

@ControllerAdvice(assignableTypes = ConfluenceTemplateSettingsController.class)
public class ConfluenceTemplateSettingsExceptionHandler {

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<TemplateSettingsErrorResponse> handleValidation(MethodArgumentNotValidException ex) {
        String message = ex.getBindingResult().getFieldErrors().stream()
                .findFirst()
                .map(error -> error.getField() + ": " + error.getDefaultMessage())
                .orElse("Validation failed");
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(new TemplateSettingsErrorResponse(message));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<TemplateSettingsErrorResponse> handleIllegalArgument(IllegalArgumentException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(new TemplateSettingsErrorResponse(ex.getMessage()));
    }

    private record TemplateSettingsErrorResponse(String message) {}
}
