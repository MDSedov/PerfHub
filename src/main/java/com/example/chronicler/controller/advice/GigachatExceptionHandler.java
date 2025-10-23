package com.example.chronicler.controller.advice;

import com.example.chronicler.controller.GigachatController;
import com.example.chronicler.exception.GigachatClientException;
import com.example.chronicler.exception.GigachatPromptLoadingException;
import com.example.chronicler.exception.GigachatPromptNotFoundException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;

@ControllerAdvice(assignableTypes = GigachatController.class)
public class GigachatExceptionHandler {

    @ExceptionHandler(GigachatPromptNotFoundException.class)
    ResponseEntity<GigachatErrorResponse> handlePromptNotFound(GigachatPromptNotFoundException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(new GigachatErrorResponse(ex.getMessage()));
    }

    @ExceptionHandler(GigachatPromptLoadingException.class)
    ResponseEntity<GigachatErrorResponse> handlePromptLoading(GigachatPromptLoadingException ex) {
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(new GigachatErrorResponse(ex.getMessage()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<GigachatErrorResponse> handleValidation(MethodArgumentNotValidException ex) {
        String message = ex.getBindingResult().getFieldErrors().stream()
                .findFirst()
                .map(error -> error.getField() + ": " + error.getDefaultMessage())
                .orElse("Validation failed");
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(new GigachatErrorResponse(message));
    }

    @ExceptionHandler(GigachatClientException.class)
    ResponseEntity<GigachatErrorResponse> handleClient(GigachatClientException ex) {
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                .body(new GigachatErrorResponse(ex.getMessage()));
    }

    private record GigachatErrorResponse(String message) {
    }
}
