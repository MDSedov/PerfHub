package perfhub.exception;

/** Исключение клиента DropApp. */
public class DropAppClientException extends RuntimeException {

    public DropAppClientException(String message) {
        super(message);
    }

    public DropAppClientException(String message, Throwable cause) {
        super(message, cause);
    }
}
