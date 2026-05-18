package perfhub.controller.advice;

import perfhub.controller.GrafanaController;
import perfhub.exception.GrafanaClientException;
import perfhub.exception.GrafanaInvalidTimeRangeException;
import perfhub.exception.GrafanaRenderingException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;

@ControllerAdvice(assignableTypes = GrafanaController.class)
public class GrafanaExceptionHandler {

    @ExceptionHandler(GrafanaInvalidTimeRangeException.class)
    ResponseEntity<GrafanaErrorResponse> handleInvalidTimeRange(GrafanaInvalidTimeRangeException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(new GrafanaErrorResponse(ex.getMessage()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<GrafanaErrorResponse> handleValidation(MethodArgumentNotValidException ex) {
        String message = ex.getBindingResult().getFieldErrors().stream()
                .findFirst()
                .map(error -> error.getField() + ": " + error.getDefaultMessage())
                .orElse("Validation failed");
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(new GrafanaErrorResponse(message));
    }

    @ExceptionHandler(GrafanaRenderingException.class)
    ResponseEntity<GrafanaErrorResponse> handleRendering(GrafanaRenderingException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(new GrafanaErrorResponse(ex.getMessage()));
    }

    @ExceptionHandler(GrafanaClientException.class)
    ResponseEntity<GrafanaErrorResponse> handleClient(GrafanaClientException ex) {
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                .body(new GrafanaErrorResponse(ex.getMessage()));
    }

    private record GrafanaErrorResponse(String message) {
    }
}
