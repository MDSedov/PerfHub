package perfhub.exception;

public class GrafanaRenderingException extends RuntimeException {

    public GrafanaRenderingException(String message) {
        super(message);
    }

    public GrafanaRenderingException(String message, Throwable cause) {
        super(message, cause);
    }
}
