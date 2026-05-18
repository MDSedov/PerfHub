package perfhub.controller.advice;

import perfhub.controller.RequirementController;
import perfhub.exception.RequirementEvaluationException;
import perfhub.exception.VictoriaMetricsClientException;
import perfhub.exception.VictoriaMetricsResponseProcessingException;
import perfhub.exception.VmQueryNotFoundException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;

@ControllerAdvice(assignableTypes = RequirementController.class)
public class RequirementExceptionHandler {

    @ExceptionHandler({RequirementEvaluationException.class, VmQueryNotFoundException.class})
    ResponseEntity<RequirementErrorResponse> handleClientErrors(Exception ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(new RequirementErrorResponse(ex.getMessage()));
    }

    @ExceptionHandler({
        VictoriaMetricsClientException.class,
        VictoriaMetricsResponseProcessingException.class
    })
    ResponseEntity<RequirementErrorResponse> handleUpstreamErrors(Exception ex) {
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                .body(new RequirementErrorResponse(ex.getMessage()));
    }

    private record RequirementErrorResponse(String message) {
    }
}
