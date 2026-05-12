package com.example.chronicler.controller.advice;

import com.example.chronicler.controller.DropAppController;
import com.example.chronicler.controller.LoadTestController;
import com.example.chronicler.exception.DropAppClientException;
import java.util.Objects;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

@RestControllerAdvice(assignableTypes = {DropAppController.class, LoadTestController.class})
public class DropAppExceptionHandler {

    @ExceptionHandler(DropAppClientException.class)
    public ResponseEntity<DropAppErrorResponse> handleDropAppClientException(DropAppClientException ex) {
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(new DropAppErrorResponse(resolveMessage(ex)));
    }

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<DropAppErrorResponse> handleResponseStatusException(ResponseStatusException ex) {
        return ResponseEntity.status(ex.getStatusCode()).body(new DropAppErrorResponse(resolveMessage(ex)));
    }

    @ExceptionHandler({IllegalArgumentException.class, IllegalStateException.class})
    public ResponseEntity<DropAppErrorResponse> handleBadRequest(RuntimeException ex) {
        return ResponseEntity.badRequest().body(new DropAppErrorResponse(resolveMessage(ex)));
    }

    private String resolveMessage(ResponseStatusException ex) {
        if (StringUtils.hasText(ex.getReason())) {
            return ex.getReason();
        }
        return resolveMessage((Exception) ex);
    }

    private String resolveMessage(Exception ex) {
        String message = ex.getMessage();
        if (!StringUtils.hasText(message)) {
            message = ex.getClass().getSimpleName();
        }
        return Objects.toString(message, "Ошибка при выгрузке конфигурации");
    }

    private record DropAppErrorResponse(String message) {}
}
