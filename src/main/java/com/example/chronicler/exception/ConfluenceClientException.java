package com.example.chronicler.exception;

public class ConfluenceClientException extends RuntimeException {

    public ConfluenceClientException(String message) {
        super(message);
    }

    public ConfluenceClientException(String message, Throwable cause) {
        super(message, cause);
    }
}
