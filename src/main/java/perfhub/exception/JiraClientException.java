package perfhub.exception;

public class JiraClientException extends RuntimeException {

    public JiraClientException(String message) {
        super(message);
    }

    public JiraClientException(String message, Throwable cause) {
        super(message, cause);
    }
}
