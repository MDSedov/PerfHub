package perfhub.exception;

public class GigachatPromptLoadingException extends RuntimeException {

    public GigachatPromptLoadingException(String message) {
        super(message);
    }

    public GigachatPromptLoadingException(String message, Throwable cause) {
        super(message, cause);
    }
}
