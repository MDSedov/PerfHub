package perfhub.exception;

public class GrafanaClientException extends RuntimeException {

    public GrafanaClientException(String message) {
        super(message);
    }

    public GrafanaClientException(String message, Throwable cause) {
        super(message, cause);
    }
}
