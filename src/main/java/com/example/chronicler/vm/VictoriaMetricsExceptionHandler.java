package com.example.chronicler.vm;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;

@ControllerAdvice(assignableTypes = VictoriaMetricsController.class)
class VictoriaMetricsExceptionHandler {

    @ExceptionHandler(VmQueryNotFoundException.class)
    ResponseEntity<VmErrorResponse> handleQueryNotFound(VmQueryNotFoundException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(new VmErrorResponse(ex.getMessage()));
    }

    @ExceptionHandler(InvalidTimeRangeException.class)
    ResponseEntity<VmErrorResponse> handleInvalidTimeRange(InvalidTimeRangeException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(new VmErrorResponse(ex.getMessage()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<VmErrorResponse> handleValidation(MethodArgumentNotValidException ex) {
        String message = ex.getBindingResult().getFieldErrors().stream()
                .findFirst()
                .map(error -> error.getField() + ": " + error.getDefaultMessage())
                .orElse("Validation failed");
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(new VmErrorResponse(message));
    }

    @ExceptionHandler(VictoriaMetricsClientException.class)
    ResponseEntity<VmErrorResponse> handleClient(VictoriaMetricsClientException ex) {
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                .body(new VmErrorResponse(ex.getMessage()));
    }

    private record VmErrorResponse(String message) {
    }
}
