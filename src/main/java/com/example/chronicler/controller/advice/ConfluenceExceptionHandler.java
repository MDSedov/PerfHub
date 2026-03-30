package com.example.chronicler.controller.advice;

import com.example.chronicler.controller.ConfluenceController;
import com.example.chronicler.exception.ConfluenceClientException;
import com.example.chronicler.exception.ConfluenceOperationException;
import com.example.chronicler.exception.GrafanaClientException;
import com.example.chronicler.exception.GrafanaInvalidTimeRangeException;
import com.example.chronicler.exception.GrafanaRenderNotCachedException;
import com.example.chronicler.exception.GrafanaRenderingException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;

@ControllerAdvice(assignableTypes = ConfluenceController.class)
public class ConfluenceExceptionHandler {

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ConfluenceErrorResponse> handleValidation(MethodArgumentNotValidException ex) {
        String message = ex.getBindingResult().getFieldErrors().stream()
                .findFirst()
                .map(error -> error.getField() + ": " + error.getDefaultMessage())
                .orElse("Validation failed");
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(new ConfluenceErrorResponse(message));
    }

    @ExceptionHandler(ConfluenceOperationException.class)
    ResponseEntity<ConfluenceErrorResponse> handleOperation(ConfluenceOperationException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(new ConfluenceErrorResponse(ex.getMessage()));
    }

    @ExceptionHandler(GrafanaRenderNotCachedException.class)
    ResponseEntity<ConfluenceErrorResponse> handleMissingCache(GrafanaRenderNotCachedException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(new ConfluenceErrorResponse(ex.getMessage()));
    }

    @ExceptionHandler(GrafanaRenderingException.class)
    ResponseEntity<ConfluenceErrorResponse> handleGrafanaRendering(GrafanaRenderingException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(new ConfluenceErrorResponse(ex.getMessage()));
    }

    @ExceptionHandler(GrafanaInvalidTimeRangeException.class)
    ResponseEntity<ConfluenceErrorResponse> handleGrafanaTimeRange(GrafanaInvalidTimeRangeException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(new ConfluenceErrorResponse(ex.getMessage()));
    }

    @ExceptionHandler(GrafanaClientException.class)
    ResponseEntity<ConfluenceErrorResponse> handleGrafanaClient(GrafanaClientException ex) {
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(new ConfluenceErrorResponse(ex.getMessage()));
    }

    @ExceptionHandler(ConfluenceClientException.class)
    ResponseEntity<ConfluenceErrorResponse> handleClient(ConfluenceClientException ex) {
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(new ConfluenceErrorResponse(ex.getMessage()));
    }

    private record ConfluenceErrorResponse(String message) {}
}
