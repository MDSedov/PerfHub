package perfhub.exception;

public class GigachatClientException extends RuntimeException {

    public GigachatClientException(String message) {
        super(message);
    }

    public GigachatClientException(String message, Throwable cause) {
        super(message, cause);
    }
}
